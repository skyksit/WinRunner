package com.winlator.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class GameToolsTest {
    /** The exact command verified on device (Diablo II + UdieToo, 2026-10-11). */
    @Test
    public void chainStartsToolWaitsThenRestartsGameFromItsFolder() {
        assertEquals("/min cmd.exe /c start \"\" /d C:\\DGPlayer\\diablo2 /wait UdieToo.exe"
                        +" & start \"\" /d D:\\ Game.exe -3dfx",
                GameTools.chainParameters("C:\\DGPlayer\\diablo2\\UdieToo.exe", "D:\\Game.exe", "-3dfx"));
    }

    @Test
    public void noArgumentsLeavesNoTrailingSpace() {
        assertEquals("/min cmd.exe /c start \"\" /d C:\\DGPlayer\\g /wait t.exe & start \"\" /d C:\\DGPlayer\\g g.exe",
                GameTools.chainParameters("C:\\DGPlayer\\g\\t.exe", "C:\\DGPlayer\\g\\g.exe", "  "));
    }

    @Test
    public void driveRootKeepsItsBackslashUnquoted() {
        // "D:" alone is the drive's current directory; "D:\" in quotes reaches start as D:".
        assertEquals("D:\\", GameTools.dosDir("D:\\Game.exe"));
    }

    @Test
    public void pathsWithSpacesAreQuoted() {
        assertEquals("\"C:\\DGPlayer\\g\\Save Editor\"", GameTools.dosDir("C:\\DGPlayer\\g\\Save Editor\\Edit It.exe"));
        assertEquals("\"Edit It.exe\"", GameTools.quote("Edit It.exe"));
        assertEquals("Edit.exe", GameTools.quote("Edit.exe"));
    }

    @Test
    public void stagingSkipsNamesTheTargetAlreadyHasAndRepeats() {
        // The player's own export is never overwritten; Windows names ignore case.
        boolean[] copy = GameTools.planStaging(
                Arrays.asList("Enigma.d2i", "shako.d2i", "Enigma.d2i", "Ring.d2i"),
                Arrays.asList("dos.d2s", "Shako.D2I"));
        assertEquals("[true, false, false, true]", Arrays.toString(copy));
    }

    @Test
    public void toolLibraryIsAScalarKey() {
        GameManifest manifest = GameManifest.parse(Arrays.asList(
                "tool=UdieToo.exe -> Item Editor",
                "toolLibrary=UdieToo_items -> C:\\users\\xuser\\Saved Games\\Diablo II"));
        assertEquals("UdieToo_items -> C:\\users\\xuser\\Saved Games\\Diablo II", manifest.get(GameTools.KEY_LIBRARY));
    }

    @Test
    public void manifestToolLinesRepeatWithOptionalName() {
        GameManifest manifest = GameManifest.parse(Arrays.asList(
                "exe=Game.exe",
                "tool=UdieToo.exe -> Item Editor",
                "tool = tools/Config.exe",
                "tool=",
                "tool= -> Nameless"));
        List<String[]> tools = manifest.getTools();
        assertEquals(2, tools.size());
        assertEquals("UdieToo.exe", tools.get(0)[0]);
        assertEquals("Item Editor", tools.get(0)[1]);
        assertEquals("tools/Config.exe", tools.get(1)[0]);
        assertNull(tools.get(1)[1]);
        // A tool line is not a scalar: exe is still the game.
        assertEquals("Game.exe", manifest.get("exe"));
        assertNull(manifest.get("tool"));
    }
}
