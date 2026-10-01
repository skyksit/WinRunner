package com.winlator.cheat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds a saved value's address in the running game from its {@link PointerChain}s and checks that
 * the address still holds that value and not something else.
 *
 * Why the check: in Diablo II the gold is one entry of a sorted stat array ({stat id, value} pairs).
 * When the gold drops to 0 its entry is removed and the next one - the stash gold - slides into the
 * same address (2026-10, seen with nothing frozen). The chain still leads there. So each target also
 * keeps the 4 bytes just before the value (the "tag" - the stat id in that case): if they changed,
 * the entry moved, so it is looked for nearby, and if it is not there the value is reported missing
 * rather than written over whatever took its place.
 *
 * A tag the game rewrites while the value stays put (an unrelated variable next to a plain global)
 * would make a good cheat look missing, so a target can be marked as having no usable tag.
 */
public final class TargetResolver {
    /** How far an entry may have slid, either way. */
    public static final int TAG_SEARCH_RANGE = 0x100;
    public static final long NO_TAG = -1;

    public static final class Resolution {
        /** -1 when not found. */
        public final long address;
        /** Per chain: did it lead to the address that was chosen. */
        public final boolean[] agreed;
        /**
         * Chains that reached some address, tag or not. None means the game has not built the
         * structures yet (still in its menus); some means they are there but the value is not.
         */
        public final int followed;

        Resolution(long address, boolean[] agreed, int followed) {
            this.address = address;
            this.agreed = agreed;
            this.followed = followed;
        }

        public boolean found() {
            return address >= 0;
        }
    }

    private TargetResolver() {}

    public static long readTag(MemoryReader memory, long address) {
        return address >= 4 ? memory.readU32(address - 4) : NO_TAG;
    }

    /**
     * The tag to remember for a value, or {@link #NO_TAG}: 0 and 0xFFFFFFFF are everywhere, so
     * finding them "nearby" would prove nothing.
     */
    public static long captureTag(MemoryReader memory, long address) {
        long tag = readTag(memory, address);
        return tag == 0 || tag == 0xFFFFFFFFL ? NO_TAG : tag;
    }

    /**
     * The address most chains agree on, after moving each to where its tag is; ties go to the
     * earlier chain (the scanner lists the shortest first).
     */
    public static Resolution resolve(MemoryReader memory, ModuleMap modules, List<PointerChain> chains, long tag) {
        long[] landed = new long[chains.size()];
        Map<Long, Integer> votes = new LinkedHashMap<>();
        int followed = 0;
        for (int i = 0; i < chains.size(); i++) {
            long address = chains.get(i).follow(memory, modules);
            if (address >= 0) followed++;
            if (address >= 0 && tag != NO_TAG) address = findTag(memory, address, tag);
            landed[i] = address;
            if (address >= 0) {
                Integer count = votes.get(address);
                votes.put(address, count == null ? 1 : count + 1);
            }
        }

        long best = -1;
        int bestVotes = 0;
        for (Map.Entry<Long, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                best = vote.getKey();
                bestVotes = vote.getValue();
            }
        }
        boolean[] agreed = new boolean[chains.size()];
        for (int i = 0; i < landed.length; i++) agreed[i] = best >= 0 && landed[i] == best;
        return new Resolution(best, agreed, followed);
    }

    /** {@code address} if its tag matches, else the nearest aligned address whose tag does, else -1. */
    static long findTag(MemoryReader memory, long address, long tag) {
        if (readTag(memory, address) == tag) return address;
        for (int distance = 4; distance <= TAG_SEARCH_RANGE; distance += 4) {
            if (readTag(memory, address + distance) == tag) return address + distance;
            if (address - distance >= 4 && readTag(memory, address - distance) == tag) return address - distance;
        }
        return -1;
    }
}
