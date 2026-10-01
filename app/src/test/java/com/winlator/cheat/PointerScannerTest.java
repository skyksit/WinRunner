package com.winlator.cheat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

/**
 * The layout found in Diablo II 1.14d (2026-10): two gold copies 0x500 apart in one struct reached
 * through [[Game.exe+0x379D94]+0x144], and one reached directly from [Game.exe+0x3A669C].
 */
public class PointerScannerTest {
    static final long EXE = 0x400000;
    static final long EXE_DATA = 0x705000, EXE_END = 0x9ba000;
    static final long HEAP = 0x1600000, HEAP_END = 0x1f00000;
    static final long HEAP2 = 0xc4c0000, HEAP2_END = 0xc510000;

    static final long STATS = 0x1713300;      // struct holding two gold copies
    static final long OWNER = 0x1e86600;      // struct pointing at it (+0x144)
    static final long GOLD_A = STATS + 0x36c;
    static final long GOLD_B = STATS + 0x86c; // beyond 0x400: missed with the old limit
    static final long DIRECT = 0xc4d9200;
    static final long GOLD_C = DIRECT + 0x36c;

    ModuleMap modules;
    List<long[]> regions;
    FakeMemory memory;

    static ModuleMap modules() throws Exception {
        String maps =
            "00400000-00705000 r-xp 00000000 00:00 0 /x/drive_c/DGPlayer/diablo2/Game.exe\n" +
            "00705000-0074d000 rwxp 00305000 00:00 0 /x/drive_c/DGPlayer/diablo2/Game.exe\n" +
            "0074d000-00995000 rwxp 00000000 00:00 0 \n" +
            "00995000-009ba000 r-xp 0034d000 00:00 0 /x/drive_c/DGPlayer/diablo2/Game.exe\n";
        return ModuleMap.build(ProcessMaps.parse(new BufferedReader(new StringReader(maps))));
    }

    @Before
    public void setUp() throws Exception {
        modules = modules();
        regions = Arrays.asList(new long[]{EXE_DATA, 0x995000}, new long[]{HEAP, HEAP_END}, new long[]{HEAP2, HEAP2_END});
        memory = new FakeMemory(new long[]{EXE, EXE_END}, new long[]{HEAP, HEAP_END}, new long[]{HEAP2, HEAP2_END});
        memory.put32(EXE + 0x379d94, OWNER);
        memory.put32(OWNER + 0x144, STATS);
        memory.put32(EXE + 0x3a669c, DIRECT);
        memory.put32(GOLD_A, 9500);
        memory.put32(GOLD_B, 9500);
        memory.put32(GOLD_C, 9500);
        // An unaligned look-alike pointer next to the target: the noise seen on the device.
        memory.put32(EXE + 0x5160fc, GOLD_C - 0x2a1);
    }

    @Test
    public void findsTheChainsSeenOnTheDevice() throws Exception {
        List<List<PointerChain>> chains = PointerScanner.scan(memory, regions, modules,
                new long[]{GOLD_A, GOLD_B, GOLD_C}, new PointerScanner.Options(), null);

        assertTrue(chains.get(0).contains(new PointerChain("Game.exe", 0x379d94, new long[]{0x144, 0x36c})));
        assertTrue(chains.get(1).contains(new PointerChain("Game.exe", 0x379d94, new long[]{0x144, 0x86c})));
        assertEquals(new PointerChain("Game.exe", 0x3a669c, new long[]{0x36c}), chains.get(2).get(0));
        long[] targets = {GOLD_A, GOLD_B, GOLD_C};
        for (int i = 0; i < targets.length; i++) {
            for (PointerChain chain : chains.get(i)) assertEquals(chain.toString(), targets[i], chain.follow(memory, modules));
        }
    }

    /**
     * The second device run: Game.exe holds an array of pointers to structs 0x100 apart, the gold in
     * one of them. Keeping the first 8 chains found kept the far ones (through structs before the
     * gold's, +0x66C..+0xE6C), which missed the next run; the nearest are the ones to keep.
     */
    @Test
    public void keepsTheNearestOfAPointerArray() throws Exception {
        long block = 0xc4c8000, gold = block + 0x1200 + 0x36c;
        for (int i = 0; i < 32; i++) memory.put32(EXE + 0x3a6600 + i * 4L, block + i * 0x100L);
        List<PointerChain> chains = PointerScanner.scan(memory, regions, modules, new long[]{gold},
                new PointerScanner.Options(), null).get(0);

        assertEquals(8, chains.size());
        // Nearest first: the struct right below the gold (+0x6C), then +0x16C, +0x26C, ...
        for (int i = 0; i < chains.size(); i++) {
            assertEquals(chains.get(i).toString(), 0x6c + i * 0x100L, chains.get(i).offsets[0]);
            assertEquals(gold, chains.get(i).follow(memory, modules));
        }
    }

    @Test
    public void ignoresUnalignedPointers() throws Exception {
        List<PointerChain> chains = PointerScanner.scan(memory, regions, modules,
                new long[]{GOLD_C}, new PointerScanner.Options(), null).get(0);
        for (PointerChain chain : chains) assertFalse(chain.moduleOffset == 0x5160fc);
    }

    @Test
    public void oldOffsetLimitMissesTheSecondCopy() throws Exception {
        PointerScanner.Options narrow = new PointerScanner.Options();
        narrow.maxOffset = 0x400;
        assertTrue(PointerScanner.scan(memory, regions, modules, new long[]{GOLD_B}, narrow, null).get(0).isEmpty());
    }

    @Test
    public void staticAddressNeedsNoPointer() throws Exception {
        List<PointerChain> chains = PointerScanner.scan(memory, regions, modules,
                new long[]{0x7a2a68}, new PointerScanner.Options(), null).get(0);
        assertEquals(new PointerChain("Game.exe", 0x3a2a68, new long[0]), chains.get(0));
    }

    @Test
    public void chainsSurviveTheHeapMoving() throws Exception {
        PointerChain chain = new PointerChain("Game.exe", 0x379d94, new long[]{0x144, 0x86c});
        assertEquals(GOLD_B, chain.follow(memory, modules));
        // Next run: the structs are elsewhere, the static pointer in Game.exe follows them.
        long movedOwner = 0x1a00000, movedStats = 0x1c00000;
        memory.put32(EXE + 0x379d94, movedOwner);
        memory.put32(movedOwner + 0x144, movedStats);
        assertEquals(movedStats + 0x86c, chain.follow(memory, modules));
    }

    @Test
    public void brokenLinkGivesNoAddress() throws Exception {
        memory.put32(OWNER + 0x144, 0);
        assertEquals(-1, new PointerChain("Game.exe", 0x379d94, new long[]{0x144, 0x36c}).follow(memory, modules));
        assertEquals(-1, new PointerChain("Other.exe", 0x379d94, new long[]{0x144}).follow(memory, modules));
    }
}
