package com.winlator.bridge;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.WineRegistryEditor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers the Video for Windows decoders Wine ships into the prefix's {@code Drivers32} key.
 *
 * <p>The prefix arrives with that key empty — not one {@code vidc.*} mapping. Wine's own
 * {@code wine.inf} writes them during a normal prefix creation, but this rootfs is shipped
 * pre-built and never went through it, so {@code msvfw32} can open no codec at all and quartz's
 * "AVI Decompressor" filter is dead weight: it declines every AVI because ICM has nothing to give
 * it. The DLLs are present the whole time; only the mapping from FOURCC to DLL is missing.
 *
 * <p>The consequence reaches well past VfW. A 1990s game that plays its cutscenes through
 * DirectShow/amstream while running a 16-bit display mode asks the video pin for
 * {@code MEDIASUBTYPE_RGB565}; the decoder Wine otherwise falls back to (winegstreamer, decoding
 * inside the AVI splitter) only ever offers RGB32/RGB24/YUV, so the pin cannot connect and the
 * game dies on its intro movie. The AVI Decompressor does offer RGB565, because the VfW codecs
 * behind it convert to whatever bit depth the caller asks for. Registering these mappings is
 * therefore what makes such a game start at all — confirmed with a Cinepak-encoded
 * The Rhapsody of Zephyr, which reached its title screen only once this key was populated.
 *
 * <p>Runs on every launch and writes only what is missing, so prefixes created by earlier builds
 * heal themselves and anything the user put there (a third-party codec pack, say) is left alone.
 * Like the rest of the bridge's registry work it edits the hive as a plain file and so must happen
 * while Wine is stopped. The steady-state cost is reading the hive; nothing is rewritten once the
 * mappings are in place.
 */
abstract class VfwCodecs {
    private static final String TAG = "DGPlayerBridge";

    /**
     * Wine keeps the 64-bit and the WOW64 view of HKLM in separate sections of the same hive, and
     * a 32-bit game — which is what these codecs exist for — reads only the Wow6432Node one. Both
     * are written because the prefix serves 32- and 64-bit executables alike.
     */
    private static final String[] DRIVERS32_KEYS = {
        "Software\\Microsoft\\Windows NT\\CurrentVersion\\Drivers32",
        "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion\\Drivers32",
    };

    /**
     * {@code vidc.<fourcc>} → the DLL that implements it. Only codecs Wine actually implements are
     * listed. {@code ir50_32.dll} (Indeo 5) is deliberately absent: Wine's copy is a stub that
     * fails every query, so mapping it would add no format while handing the graph builder one
     * more filter to try — and a chance to displace a path that already works.
     */
    private static final String[][] CODECS = {
        {"vidc.cvid", "iccvid.dll"},    // Cinepak
        {"vidc.msvc", "msvidc32.dll"},  // Microsoft Video 1
        {"vidc.cram", "msvidc32.dll"},  // Microsoft Video 1, as tagged by Video for Windows 1.0
        {"vidc.wham", "msvidc32.dll"},  // Microsoft Video 1, third alias in the wild
        {"vidc.mrle", "msrle32.dll"},   // Microsoft RLE
    };

    /** Adds every mapping this prefix is missing; returns quietly when there is nothing to do. */
    static void ensureRegistered(Container container) {
        File prefix = new File(container.getRootDir(), ".wine");
        File systemReg = new File(prefix, "system.reg");
        if (!systemReg.isFile()) {
            Log.w(TAG, "no system.reg under "+prefix+", skipping VfW codec registration");
            return;
        }

        String[][] present = installedCodecs(prefix);
        if (present.length == 0) return;

        try (WineRegistryEditor registry = new WineRegistryEditor(systemReg)) {
            int added = 0;
            for (String key : DRIVERS32_KEYS) added += registerMissing(registry, key, present);
            if (added > 0) Log.i(TAG, "Drivers32: registered "+added+" VfW codec mapping(s)");
        }
    }

    /** Drops mappings whose DLL this rootfs does not carry, so the key never points at nothing. */
    private static String[][] installedCodecs(File prefix) {
        File system32 = new File(prefix, "drive_c/windows/system32");
        File syswow64 = new File(prefix, "drive_c/windows/syswow64");
        List<String[]> present = new ArrayList<>();

        for (String[] codec : CODECS) {
            if (new File(system32, codec[1]).isFile() || new File(syswow64, codec[1]).isFile()) {
                present.add(codec);
            }
            else Log.w(TAG, "VfW codec "+codec[1]+" missing from prefix, skipping "+codec[0]);
        }
        return present.toArray(new String[0][]);
    }

    /**
     * Existing values win: a codec pack the user installed may already own the FOURCC, and taking
     * it over would silently change which decoder their other games use.
     */
    private static int registerMissing(WineRegistryEditor registry, String key, String[][] codecs) {
        List<String[]> missing = new ArrayList<>();
        for (String[] codec : codecs) {
            if (registry.getStringValue(key, codec[0]) == null) missing.add(codec);
        }
        if (missing.isEmpty()) return 0;

        // One rewrite for the whole batch; setStringValue would redo the file per value.
        registry.setStringValues(key, missing.toArray(new String[0][]));
        return missing.size();
    }
}
