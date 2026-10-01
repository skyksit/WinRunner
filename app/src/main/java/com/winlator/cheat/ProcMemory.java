package com.winlator.cheat;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;

/** /proc/&lt;pid&gt;/mem opened read-only; see {@link MemoryScanner} for why this works without ptrace. */
public final class ProcMemory implements MemoryReader, Closeable {
    private final RandomAccessFile mem;

    public ProcMemory(int pid) throws IOException {
        mem = new RandomAccessFile("/proc/"+pid+"/mem", "r");
    }

    @Override
    public synchronized int read(long address, byte[] buffer, int offset, int length) throws IOException {
        mem.seek(address);
        return mem.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        mem.close();
    }
}
