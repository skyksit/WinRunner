package com.winlator.speed;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Host half of the guest time scale.
 *
 * <p>Wine games are not emulated - there is no core loop to run more or fewer times, the way an
 * emulator frontend does it. The only thing that changes their speed is the clock they read, so the
 * patched ntdll in the rootfs maps this file and derives its monotonic counter from it:
 *
 * <pre>virtual = baseVirtual + (real - baseReal) * scale</pre>
 *
 * <p>Every deadline that ntdll hands to the kernel or to wineserver runs through the inverse. The
 * mapping is rebased on every change so virtual time stays monotonic and continuous across a toggle;
 * a backwards jump there breaks practically every game, which is why the host - not each guest
 * process - computes the new base. {@link System#nanoTime()} is {@code CLOCK_MONOTONIC}, the same
 * clock the guest reads, so the two sides share an epoch.
 *
 * <p>Publication is double-buffered rather than lock-based: the writer fills the inactive slot and
 * then flips a single aligned 32-bit {@code active} field, which is atomic on arm64. A reader that
 * samples {@code active} can only be torn by two further updates, and updates come from a person
 * tapping a button while reads take nanoseconds.
 */
public final class Timescale {
    private static final String TAG = "Timescale";

    /** Names the file for the guest; the container has no chroot, so this is a host path. */
    public static final String ENV_VAR = "WINRUNNER_TIMESCALE_FILE";
    public static final String FILE_NAME = "tmp/.winrunner_timescale";

    /** 'WRTS'. Guest side must agree on this, on VERSION, and on the offsets below. */
    private static final int MAGIC = 0x57525453;
    private static final int VERSION = 1;

    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_ACTIVE = 8;
    private static final int OFF_SLOTS = 16;
    private static final int SLOT_SIZE = 24;   // int64 baseReal, int64 baseVirtual, double scale
    public static final int FILE_SIZE = OFF_SLOTS + 2 * SLOT_SIZE;

    private static final class Frame {
        final long baseReal;
        final long baseVirtual;
        final double scale;

        Frame(long baseReal, long baseVirtual, double scale) {
            this.baseReal = baseReal;
            this.baseVirtual = baseVirtual;
            this.scale = scale;
        }

        long virtualNanos(long realNanos) {
            return baseVirtual + (long)((realNanos - baseReal) * scale);
        }
    }

    private static volatile Frame frame = new Frame(0, 0, 1.0);
    private static MappedByteBuffer buffer;
    private static int activeSlot;

    private Timescale() {}

    /**
     * Creates and maps the file, seeded with an identity mapping. Call once per session, after the
     * tmp directory has been wiped, and before the guest starts.
     */
    public static synchronized void open(File file) {
        close();
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();

            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.setLength(FILE_SIZE);
            buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            raf.close();

            long now = System.nanoTime();
            activeSlot = 0;
            frame = new Frame(now, now, 1.0);
            writeSlot(0, frame);
            writeSlot(1, frame);
            buffer.putInt(OFF_ACTIVE, 0);
            buffer.putInt(OFF_VERSION, VERSION);
            // Last, so a guest that maps the file mid-initialisation never sees a valid magic over
            // uninitialised slots.
            buffer.putInt(OFF_MAGIC, MAGIC);
        }
        catch (IOException e) {
            Log.w(TAG, "could not open "+file+", game speed control will not reach the guest", e);
            buffer = null;
        }
    }

    public static synchronized void close() {
        if (buffer == null) return;
        // Wipe the magic so a guest process still holding the mapping falls back to real time.
        buffer.putInt(OFF_MAGIC, 0);
        buffer = null;
        frame = new Frame(0, 0, 1.0);
    }

    /** Rebases the mapping at the current instant and publishes {@code scale}. */
    public static synchronized void setScale(float scale) {
        Frame current = frame;
        if (current.scale == scale) return;

        long realNow = System.nanoTime();
        Frame next = new Frame(realNow, current.virtualNanos(realNow), scale);
        frame = next;

        if (buffer == null) return;
        int nextSlot = 1 - activeSlot;
        writeSlot(nextSlot, next);
        buffer.putInt(OFF_ACTIVE, nextSlot);
        activeSlot = nextSlot;
    }

    private static void writeSlot(int slot, Frame f) {
        int base = OFF_SLOTS + slot * SLOT_SIZE;
        buffer.putLong(base, f.baseReal);
        buffer.putLong(base + 8, f.baseVirtual);
        buffer.putDouble(base + 16, f.scale);
    }

    public static float getScale() {
        return (float)frame.scale;
    }

    /** The guest's view of the monotonic clock, in nanoseconds, for host code that must agree with it. */
    public static long virtualNanos() {
        return frame.virtualNanos(System.nanoTime());
    }
}
