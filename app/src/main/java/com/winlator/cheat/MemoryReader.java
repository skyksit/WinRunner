package com.winlator.cheat;

import java.io.IOException;

/**
 * Reads a guest process's memory. {@link ProcMemory} is the real one (/proc/pid/mem); tests use a
 * fake so the pointer scan and chain resolution run on the JVM.
 */
public interface MemoryReader {
    /** Like {@link java.io.RandomAccessFile#read}: bytes read, or -1/IOException when unreadable. */
    int read(long address, byte[] buffer, int offset, int length) throws IOException;

    /** A 4-byte little-endian value as unsigned, or -1 when it cannot be read. */
    default long readU32(long address) {
        byte[] bytes = new byte[4];
        try {
            if (read(address, bytes, 0, 4) != 4) return -1;
        }
        catch (IOException e) {
            return -1;
        }
        return (bytes[0] & 0xFFL) | (bytes[1] & 0xFFL) << 8 | (bytes[2] & 0xFFL) << 16 | (bytes[3] & 0xFFL) << 24;
    }
}
