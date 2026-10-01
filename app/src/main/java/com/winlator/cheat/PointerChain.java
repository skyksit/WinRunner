package com.winlator.cheat;

import java.util.Arrays;
import java.util.Locale;

/**
 * "[[module + moduleOffset] + offsets[0]] + offsets[1] ..." - how a saved cheat finds its value
 * again after a restart, when the heap has moved. No offsets means a static address in the image.
 * Pointers are 4 bytes: only 32-bit games are covered (see {@link ProcessMaps#LIMIT_4G}).
 */
public final class PointerChain {
    public final String module;
    public final long moduleOffset;
    public final long[] offsets;

    public PointerChain(String module, long moduleOffset, long[] offsets) {
        this.module = module;
        this.moduleOffset = moduleOffset;
        this.offsets = offsets;
    }

    /** The address the chain points at now, or -1 when the module is missing or a link is null/unreadable. */
    public long follow(MemoryReader memory, ModuleMap modules) {
        ModuleMap.Module image = modules.find(module);
        if (image == null) return -1;
        long address = image.base + moduleOffset;
        for (long offset : offsets) {
            long pointer = memory.readU32(address);
            if (pointer <= 0) return -1;
            address = pointer + offset;
        }
        return address;
    }

    public int depth() {
        return offsets.length;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%s+0x%X", module, moduleOffset));
        for (long offset : offsets) text.append(String.format(Locale.ROOT, " -> +0x%X", offset));
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PointerChain)) return false;
        PointerChain chain = (PointerChain)other;
        return moduleOffset == chain.moduleOffset && module.equalsIgnoreCase(chain.module) && Arrays.equals(offsets, chain.offsets);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * module.toLowerCase(Locale.ROOT).hashCode() + Long.hashCode(moduleOffset)) + Arrays.hashCode(offsets);
    }
}
