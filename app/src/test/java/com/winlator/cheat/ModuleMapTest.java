package com.winlator.cheat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Against /proc/pid/maps of Diablo II 1.14d under the fork (dumped on a device, 2026-10). */
public class ModuleMapTest {
    static List<ProcessMaps.Mapping> diablo2() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ModuleMapTest.class.getResourceAsStream("maps_diablo2.txt"), StandardCharsets.UTF_8))) {
            return ProcessMaps.parse(reader);
        }
    }

    @Test
    public void parsesEveryLine() throws Exception {
        List<ProcessMaps.Mapping> maps = diablo2();
        assertEquals(1256, maps.size());
        ProcessMaps.Mapping data = null;
        for (ProcessMaps.Mapping mapping : maps) if (mapping.start == 0x705000) data = mapping;
        assertNotNull(data);
        assertTrue(data.isWritable());
        assertEquals(0x305000, data.offset);
        assertTrue(data.path.endsWith("/diablo2/Game.exe"));
    }

    @Test
    public void gameExeSpansItsAnonymousBss() throws Exception {
        ModuleMap modules = ModuleMap.build(diablo2());
        ModuleMap.Module game = modules.find("game.exe");
        assertNotNull(game);
        assertEquals("Game.exe", game.name);
        assertEquals(0x400000, game.base);
        // The last Game.exe mapping (.rsrc) ends here; 0x74d000-0x995000 between is anonymous .bss.
        assertEquals(0x9ba000, game.end);
        assertSame(game, modules.containing(0x7a2a68));
        assertFalse(game.contains(0x9c0000));
    }

    @Test
    public void heapIsNoModule() throws Exception {
        ModuleMap modules = ModuleMap.build(diablo2());
        assertNull(modules.containing(0x0c4d956c));
        assertNull(modules.containing(0x0bff0ca0));
        assertNotNull(modules.find("ijl11.dll"));
        assertEquals(0xa80000, modules.find("IJL11.DLL").base);
    }

    @Test
    public void writableRegionsStayBelow4G() throws Exception {
        for (long[] region : ProcessMaps.writableBelow4G(diablo2())) {
            assertTrue(region[0] < region[1]);
            assertTrue(region[1] <= ProcessMaps.LIMIT_4G);
        }
    }
}
