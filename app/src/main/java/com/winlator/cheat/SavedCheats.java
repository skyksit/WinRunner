package com.winlator.cheat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The game's saved cheats - the JSON DGPlayer keeps as {@code win/save/<fileName>.cheats.json}
 * next to the save archive (so it is backed up with the game), lists in the game's details and
 * shares as text. DGPlayer reads the same format: change both sides together.
 *
 * <pre>
 * {"format":"dgplayer-win-cheats","version":1,"module":"Game.exe","moduleSize":6004736,
 *  "cheats":[{"name":"Gold","size":4,"value":20000,"freeze":false,"enabled":true,
 *    "targets":[{"tag":"000E0000","chains":[{"module":"Game.exe","base":"0x3A669C","offsets":["0x36C"]}]}]}]}
 * </pre>
 *
 * One cheat holds several targets: Diablo II only takes a new gold value when all four copies of it
 * change together. "module"/"moduleSize" name the image the chains were found in; another size is
 * another build of the game, where the offsets mean nothing.
 */
public final class SavedCheats {
    public static final String FORMAT = "dgplayer-win-cheats";
    public static final int VERSION = 1;

    public static final class Target {
        public final List<PointerChain> chains;
        /** {@link TargetResolver#NO_TAG} when the value is found by its chains alone. */
        public long tag;

        public Target(List<PointerChain> chains, long tag) {
            this.chains = chains;
            this.tag = tag;
        }
    }

    public static final class Cheat {
        public String name;
        public int size;
        public long value;
        public boolean freeze;
        public boolean enabled;
        public final List<Target> targets;

        public Cheat(String name, int size, long value, boolean freeze, boolean enabled, List<Target> targets) {
            this.name = name;
            this.size = size;
            this.value = value;
            this.freeze = freeze;
            this.enabled = enabled;
            this.targets = targets;
        }
    }

    public String module;
    public long moduleSize;
    public final List<Cheat> cheats = new ArrayList<>();

    public static SavedCheats parse(String json) throws JSONException {
        SavedCheats saved = new SavedCheats();
        if (json == null || json.trim().isEmpty()) return saved;
        JSONObject root = new JSONObject(json);
        if (!FORMAT.equals(root.optString("format"))) throw new JSONException("not a "+FORMAT+" file");
        saved.module = root.optString("module", null);
        saved.moduleSize = root.optLong("moduleSize", 0);
        JSONArray cheats = root.optJSONArray("cheats");
        if (cheats == null) return saved;
        for (int i = 0; i < cheats.length(); i++) {
            JSONObject cheat = cheats.getJSONObject(i);
            List<Target> targets = new ArrayList<>();
            JSONArray targetArray = cheat.optJSONArray("targets");
            for (int t = 0; targetArray != null && t < targetArray.length(); t++) {
                JSONObject target = targetArray.getJSONObject(t);
                List<PointerChain> chains = new ArrayList<>();
                JSONArray chainArray = target.optJSONArray("chains");
                for (int c = 0; chainArray != null && c < chainArray.length(); c++) {
                    JSONObject chain = chainArray.getJSONObject(c);
                    JSONArray offsetArray = chain.optJSONArray("offsets");
                    long[] offsets = new long[offsetArray != null ? offsetArray.length() : 0];
                    for (int o = 0; o < offsets.length; o++) offsets[o] = hex(offsetArray.getString(o));
                    chains.add(new PointerChain(chain.getString("module"), hex(chain.getString("base")), offsets));
                }
                if (chains.isEmpty()) continue;
                String tag = target.optString("tag", "");
                targets.add(new Target(chains, tag.isEmpty() ? TargetResolver.NO_TAG : Long.parseLong(tag, 16)));
            }
            int size = cheat.optInt("size", 4);
            if (targets.isEmpty() || (size != 1 && size != 2 && size != 4)) continue;
            saved.cheats.add(new Cheat(cheat.optString("name", ""), size, cheat.optLong("value", 0),
                    cheat.optBoolean("freeze", false), cheat.optBoolean("enabled", true), targets));
        }
        return saved;
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            if (module != null) root.put("module", module);
            if (moduleSize > 0) root.put("moduleSize", moduleSize);
            JSONArray cheatArray = new JSONArray();
            for (Cheat cheat : cheats) {
                JSONObject object = new JSONObject();
                object.put("name", cheat.name);
                object.put("size", cheat.size);
                object.put("value", cheat.value);
                object.put("freeze", cheat.freeze);
                object.put("enabled", cheat.enabled);
                JSONArray targetArray = new JSONArray();
                for (Target target : cheat.targets) {
                    JSONObject targetObject = new JSONObject();
                    if (target.tag != TargetResolver.NO_TAG) targetObject.put("tag", String.format(Locale.ROOT, "%08X", target.tag));
                    JSONArray chainArray = new JSONArray();
                    for (PointerChain chain : target.chains) {
                        JSONObject chainObject = new JSONObject();
                        chainObject.put("module", chain.module);
                        chainObject.put("base", String.format(Locale.ROOT, "0x%X", chain.moduleOffset));
                        JSONArray offsets = new JSONArray();
                        for (long offset : chain.offsets) offsets.put(String.format(Locale.ROOT, "0x%X", offset));
                        chainObject.put("offsets", offsets);
                        chainArray.put(chainObject);
                    }
                    targetObject.put("chains", chainArray);
                    targetArray.put(targetObject);
                }
                object.put("targets", targetArray);
                cheatArray.put(object);
            }
            root.put("cheats", cheatArray);
            return root.toString(1);
        }
        catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long hex(String text) {
        String digits = text.trim();
        if (digits.startsWith("0x") || digits.startsWith("0X")) digits = digits.substring(2);
        return Long.parseLong(digits, 16);
    }
}
