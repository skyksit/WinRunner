package com.winlator.cheat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;

/** The cheats file DGPlayer also reads and shares: the format is a contract between the two apps. */
public class SavedCheatsTest {
    static SavedCheats diablo2Gold() {
        SavedCheats saved = new SavedCheats();
        saved.module = "Game.exe";
        saved.moduleSize = 0x5ba000;
        saved.cheats.add(new SavedCheats.Cheat("Gold", 4, 20000, false, true, Arrays.asList(
            new SavedCheats.Target(Arrays.asList(new PointerChain("Game.exe", 0x3a669c, new long[]{0x36c})), 0x000E0000),
            new SavedCheats.Target(Arrays.asList(
                new PointerChain("Game.exe", 0x379d94, new long[]{0x144, 0x86c}),
                new PointerChain("D2Common.dll", 0x10, new long[0])), TargetResolver.NO_TAG))));
        return saved;
    }

    @Test
    public void roundTrips() throws Exception {
        SavedCheats back = SavedCheats.parse(diablo2Gold().toJson());
        assertEquals("Game.exe", back.module);
        assertEquals(0x5ba000, back.moduleSize);
        assertEquals(1, back.cheats.size());
        SavedCheats.Cheat gold = back.cheats.get(0);
        assertEquals("Gold", gold.name);
        assertEquals(20000, gold.value);
        assertFalse(gold.freeze);
        assertTrue(gold.enabled);
        assertEquals(2, gold.targets.size());
        assertEquals(0x000E0000, gold.targets.get(0).tag);
        assertEquals(new PointerChain("Game.exe", 0x3a669c, new long[]{0x36c}), gold.targets.get(0).chains.get(0));
        assertEquals(TargetResolver.NO_TAG, gold.targets.get(1).tag);
        assertEquals(new PointerChain("D2Common.dll", 0x10, new long[0]), gold.targets.get(1).chains.get(1));
    }

    @Test
    public void writesHexForPeople() {
        String json = diablo2Gold().toJson();
        assertTrue(json, json.contains("\"base\": \"0x3A669C\""));
        assertTrue(json, json.contains("\"0x86C\""));
        assertTrue(json, json.contains("\"tag\": \"000E0000\""));
    }

    @Test
    public void emptyFileIsNoCheats() throws Exception {
        assertTrue(SavedCheats.parse("").cheats.isEmpty());
        assertTrue(SavedCheats.parse(null).cheats.isEmpty());
    }

    @Test(expected = JSONException.class)
    public void rejectsOtherFiles() throws Exception {
        SavedCheats.parse("{\"cheats\":[]}");
    }

    @Test
    public void skipsCheatsWithoutChainsOrBadSize() throws Exception {
        String json = "{\"format\":\"dgplayer-win-cheats\",\"version\":1,\"cheats\":["
            + "{\"name\":\"empty\",\"size\":4,\"value\":1,\"targets\":[{\"chains\":[]}]},"
            + "{\"name\":\"odd\",\"size\":3,\"value\":1,\"targets\":[{\"chains\":[{\"module\":\"a.exe\",\"base\":\"0x10\",\"offsets\":[]}]}]},"
            + "{\"name\":\"ok\",\"size\":2,\"value\":7,\"targets\":[{\"chains\":[{\"module\":\"a.exe\",\"base\":\"10\",\"offsets\":[]}]}]}]}";
        SavedCheats saved = SavedCheats.parse(json);
        assertEquals(1, saved.cheats.size());
        assertEquals("ok", saved.cheats.get(0).name);
        assertEquals(0x10, saved.cheats.get(0).targets.get(0).chains.get(0).moduleOffset);
    }
}
