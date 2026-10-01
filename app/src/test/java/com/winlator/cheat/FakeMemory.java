package com.winlator.cheat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Sparse guest memory for tests: unwritten pages read as zero inside {@link #mapped}, else fail. */
final class FakeMemory implements MemoryReader {
    private static final int PAGE = 4096;
    private final Map<Long, byte[]> pages = new HashMap<>();
    private final long[][] mapped;

    /** @param mapped the readable {start, end} ranges */
    FakeMemory(long[]... mapped) {
        this.mapped = mapped;
    }

    void put32(long address, long value) {
        for (int i = 0; i < 4; i++) page(address + i)[(int)((address + i) % PAGE)] = (byte)(value >>> (8 * i));
    }

    private byte[] page(long address) {
        long key = address / PAGE;
        byte[] page = pages.get(key);
        if (page == null) pages.put(key, page = new byte[PAGE]);
        return page;
    }

    private boolean isMapped(long address) {
        for (long[] range : mapped) if (address >= range[0] && address < range[1]) return true;
        return false;
    }

    @Override
    public int read(long address, byte[] buffer, int offset, int length) throws IOException {
        if (!isMapped(address)) throw new IOException("unmapped 0x" + Long.toHexString(address));
        int n = 0;
        for (; n < length && isMapped(address + n); n++) {
            byte[] page = pages.get((address + n) / PAGE);
            buffer[offset + n] = page != null ? page[(int)((address + n) % PAGE)] : 0;
        }
        return n;
    }
}
