package com.winlator.cheat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Diablo II keeps stats as sorted {id << 16, value} pairs. Gold is stat 14, stash gold 15; when the
 * gold hits 0 its entry goes and the stash gold slides into its address (2026-10, on a device).
 */
public class TargetResolverTest {
    static final long GOLD_TAG = 0x000E0000, STASH_TAG = 0x000F0000;
    static final long STATS = 0x1713300;
    static final long ENTRY = STATS + 0x368;      // tag at ENTRY, value at ENTRY + 4
    static final long GOLD = ENTRY + 4;

    ModuleMap modules;
    FakeMemory memory;
    List<PointerChain> chains;

    @Before
    public void setUp() throws Exception {
        modules = PointerScannerTest.modules();
        memory = new FakeMemory(new long[]{0x400000, 0x9ba000}, new long[]{0x1600000, 0x1f00000});
        memory.put32(0x400000 + 0x379d94, 0x1e86600);
        memory.put32(0x1e86600 + 0x144, STATS);
        chains = Arrays.asList(
            new PointerChain("Game.exe", 0x379d94, new long[]{0x144, 0x36c}),
            new PointerChain("Game.exe", 0x379d90, new long[]{0x10}));   // noise: reads 0
        writeEntries(GOLD_TAG, 9500, STASH_TAG, 20000);
    }

    private void writeEntries(long... pairs) {
        for (int i = 0; i < pairs.length; i++) memory.put32(ENTRY + i * 4L, pairs[i]);
    }

    @Test
    public void tagMatchesInPlace() {
        assertEquals(GOLD_TAG, TargetResolver.captureTag(memory, GOLD));
        TargetResolver.Resolution resolution = TargetResolver.resolve(memory, modules, chains, GOLD_TAG);
        assertEquals(GOLD, resolution.address);
        assertArrayEquals(new boolean[]{true, false}, resolution.agreed);
    }

    @Test
    public void goldGoneDoesNotHitTheStash() {
        // Gold spent to 0: the stash entry now sits where the gold was.
        writeEntries(STASH_TAG, 20000, 0x00130000, 0x11);
        assertFalse(TargetResolver.resolve(memory, modules, chains, GOLD_TAG).found());
    }

    @Test
    public void slidEntryIsFollowed() {
        // A new stat was inserted before the gold: its entry moved one pair (8 bytes) up.
        writeEntries(0x000D0000, 915, GOLD_TAG, 9500, STASH_TAG, 20000);
        assertEquals(GOLD + 8, TargetResolver.resolve(memory, modules, chains, GOLD_TAG).address);
    }

    @Test
    public void noTagTrustsTheChain() {
        writeEntries(STASH_TAG, 20000);
        assertEquals(GOLD, TargetResolver.resolve(memory, modules, chains, TargetResolver.NO_TAG).address);
    }

    @Test
    public void zeroIsNoTag() {
        writeEntries(0, 9500);
        assertEquals(TargetResolver.NO_TAG, TargetResolver.captureTag(memory, GOLD));
    }
}
