package com.winlator.cheat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Value search over a guest process's memory, read straight from /proc/&lt;pid&gt;/mem.
 *
 * Why this works at all: the guest is our own child (same uid, same SELinux domain), and box64 is
 * a fresh execve, which makes it dumpable again even though this app is not debuggable. Verified
 * on a release build (Diablo II gold found, changed and frozen, 2026-10). No ptrace attach - the
 * tracer slot is wineserver's, and /proc/pid/mem only needs the access check, not an attach.
 *
 * Why only below 4 GB: Wine runs in new-WoW64 mode, so a 32-bit game is one 64-bit Linux process
 * whose 32-bit view sits below 4 GB, and box64 maps guest addresses 1:1. The game's data is there;
 * above it are box64, the 64-bit side of Wine and the dynarec cache.
 *
 * Unlike the libretro engine this one never snapshots the whole range: a game has hundreds of MB of
 * writable memory. The first pass is always an exact value; after it only the candidates (address +
 * the value seen last) are kept, which is what every later compare mode needs.
 *
 * Not thread-safe: {@link CheatSession} drives it from one worker thread.
 */
public final class MemoryScanner {
    public enum Compare {EXACT, INCREASED, DECREASED, UNCHANGED, CHANGED}

    private static final long LIMIT_4G = 0x1_0000_0000L;
    private static final int CHUNK = 1 << 20;
    /** Reads for a later pass are grouped into windows this big, so dense candidates cost one read. */
    private static final int WINDOW = 64 * 1024;
    public static final int MAX_CANDIDATES = 2_000_000;

    private final int pid;
    private int valueSize = 4;
    private long[] addresses = new long[0];
    private long[] previous = new long[0];
    private int count = 0;
    private boolean truncated = false;
    private boolean started = false;

    public MemoryScanner(int pid) {
        this.pid = pid;
    }

    public int getPid() {
        return pid;
    }

    public int getValueSize() {
        return valueSize;
    }

    public int getCount() {
        return count;
    }

    /** The first pass hit {@link #MAX_CANDIDATES}; later passes only see the first ones. */
    public boolean isTruncated() {
        return truncated;
    }

    public boolean isStarted() {
        return started;
    }

    public void reset() {
        addresses = new long[0];
        previous = new long[0];
        count = 0;
        truncated = false;
        started = false;
    }

    /** Every writable mapping below 4 GB, as {start, end} pairs in ascending order. */
    private List<long[]> writableRegions() throws IOException {
        List<long[]> regions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\s+", 6);
                if (fields.length < 2 || fields[1].length() < 2 || fields[1].charAt(0) != 'r' || fields[1].charAt(1) != 'w') continue;
                String[] range = fields[0].split("-");
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                if (start >= LIMIT_4G) continue;
                regions.add(new long[]{start, Math.min(end, LIMIT_4G)});
            }
        }
        return regions;
    }

    /** Starts a new search: every aligned location of {@code size} bytes that holds {@code value}. */
    public void firstScan(int size, long value) throws IOException {
        reset();
        valueSize = size;
        long wanted = value & mask(size);
        addresses = new long[4096];
        previous = new long[4096];

        byte[] buffer = new byte[CHUNK];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/"+pid+"/mem", "r")) {
            for (long[] region : writableRegions()) {
                for (long pos = region[0]; pos < region[1] && !truncated; pos += CHUNK) {
                    int length = (int)Math.min(CHUNK, region[1] - pos);
                    int read;
                    try {
                        mem.seek(pos);
                        read = mem.read(buffer, 0, length);
                    }
                    catch (IOException e) {
                        // A region can vanish or hold a guard page between reading maps and here.
                        continue;
                    }
                    for (int i = 0; i + size <= read; i += size) {
                        if (decode(buffer, i, size) == wanted) add(pos + i, wanted);
                    }
                }
                if (truncated) break;
            }
        }
        started = true;
    }

    private void add(long address, long value) {
        if (count >= MAX_CANDIDATES) {
            truncated = true;
            return;
        }
        if (count == addresses.length) {
            int grown = Math.min(addresses.length * 2, MAX_CANDIDATES);
            addresses = Arrays.copyOf(addresses, grown);
            previous = Arrays.copyOf(previous, grown);
        }
        addresses[count] = address;
        previous[count] = value;
        count++;
    }

    /**
     * Narrows the candidates. {@code operand} is only read by {@link Compare#EXACT}. The kept
     * candidates remember the value just read, so the next relative pass compares against it.
     */
    public void nextScan(Compare compare, long operand) throws IOException {
        long wanted = operand & mask(valueSize);
        byte[] window = new byte[WINDOW];
        long windowStart = -1;
        int windowLength = 0;
        int kept = 0;

        try (RandomAccessFile mem = new RandomAccessFile("/proc/"+pid+"/mem", "r")) {
            for (int i = 0; i < count; i++) {
                long address = addresses[i];
                if (windowStart < 0 || address < windowStart || address + valueSize > windowStart + windowLength) {
                    windowStart = address;
                    try {
                        mem.seek(address);
                        windowLength = Math.max(mem.read(window, 0, WINDOW), 0);
                    }
                    catch (IOException e) {
                        windowLength = 0;
                    }
                    // Unreadable now (unmapped since the last pass): the candidate is gone.
                    if (windowLength < valueSize) {
                        windowStart = -1;
                        continue;
                    }
                }

                long current = decode(window, (int)(address - windowStart), valueSize);
                if (matches(compare, current, previous[i], wanted)) {
                    addresses[kept] = address;
                    previous[kept] = current;
                    kept++;
                }
            }
        }
        count = kept;
    }

    private boolean matches(Compare compare, long current, long before, long wanted) {
        switch (compare) {
            case EXACT: return current == wanted;
            case INCREASED: return signed(current) > signed(before);
            case DECREASED: return signed(current) < signed(before);
            case UNCHANGED: return current == before;
            case CHANGED: return current != before;
        }
        return false;
    }

    public long getAddress(int index) {
        return addresses[index];
    }

    public long read(long address) throws IOException {
        return read(pid, address, valueSize);
    }

    public static long read(int pid, long address, int size) throws IOException {
        byte[] bytes = new byte[size];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/"+pid+"/mem", "r")) {
            mem.seek(address);
            mem.readFully(bytes);
        }
        return decode(bytes, 0, size);
    }

    /**
     * /proc/pid/mem, not process_vm_writev: the kernel writes it with FOLL_FORCE, so it also lands
     * on a page box64 made read-only to catch self-modifying code.
     */
    public static void write(RandomAccessFile mem, long address, int size, long value) throws IOException {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte)(value >>> (8 * i));
        mem.seek(address);
        mem.write(bytes);
    }

    public static void write(int pid, long address, int size, long value) throws IOException {
        try (RandomAccessFile mem = new RandomAccessFile("/proc/"+pid+"/mem", "rw")) {
            write(mem, address, size, value);
        }
    }

    /** Little-endian, unsigned within {@code size} bytes. */
    private static long decode(byte[] bytes, int offset, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) value |= (bytes[offset + i] & 0xFFL) << (8 * i);
        return value;
    }

    public static long mask(int size) {
        return size >= 8 ? -1L : (1L << (8 * size)) - 1;
    }

    /** The 4-byte value as the game's int sees it; 1- and 2-byte values stay unsigned. */
    private long signed(long value) {
        return valueSize == 4 ? (int)value : value;
    }

    public static String format(int size, long value) {
        return String.valueOf(size == 4 ? (long)(int)value : value & mask(size));
    }
}
