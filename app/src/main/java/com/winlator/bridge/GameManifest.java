package com.winlator.bridge;

import android.util.Log;

import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional {@code dgplayer.ini} shipped at the root of a game archive.
 *
 * <p>It exists so per-game tuning travels with the game instead of living in DGPlayer's catalog: the
 * app's game database has no column for a Windows executable path, and adding one would mean a Room
 * migration for something only this console needs. Whoever packages the archive already knows which
 * .exe to run and which wrapper it needs, so that knowledge is recorded next to the files.
 *
 * <p>Format is one {@code key=value} per line; {@code #} starts a comment, {@code [section]} lines
 * are ignored, keys are case-sensitive and a later scalar overrides an earlier one. Recognized
 * scalar keys mirror the intent extras where one exists: {@code exe}, {@code args},
 * {@code screenSize}, {@code graphicsDriver}, {@code dxwrapper}, {@code dxwrapperConfig},
 * {@code box64Preset}, {@code envVars}, {@code forceFullscreen}, {@code controlsProfile}; and the
 * manifest-only container knobs {@code startupSelection}, {@code wincomponents},
 * {@code audioDriver}, {@code showFPS}. Intent extras from the caller take precedence over anything
 * found here.
 *
 * <p>{@code copy} is the exception: it may repeat, and it is manifest-only. Plenty of 90s Windows
 * titles keep their settings in {@code %WINDIR%} rather than beside the executable — the original
 * install would have dropped them there — so a package needs a way to say "this file belongs
 * outside my folder":
 * <pre>copy=Sizuku/Sizuku.ini -&gt; C:\windows\Sizuku.ini</pre>
 *
 * <p>{@code reg} may repeat too: each names a {@code .reg} file to merge into the prefix before
 * launch, for games whose installer wrote registry keys the disk image never carried.
 *
 * <p>{@code cd} may also repeat, one line per disc in disc order. Each names a folder inside the
 * game directory holding that disc's file tree, optionally with the volume label the game expects:
 * <pre>cd=CD1 -&gt; FF8_DISC1</pre>
 * The discs share the container's single CD-ROM drive (X:) and are swapped from the in-game menu,
 * mirroring how multi-disc games ran on a one-drive PC.
 *
 * <h3>On-device edits — the override file</h3>
 *
 * <p>DGPlayer lets the player edit these settings on the device and sends the result as the
 * {@code manifest_ini} intent extra. Rewriting the archive for that would be wrong twice over: the
 * payload fingerprint would change and force a full re-import, and a 1 GB zip cannot be rewritten
 * in place cheaply. So the bridge materializes the text as {@link #OVERRIDE_FILENAME} next to the
 * package's own file and {@link #read} prefers it. A file — not an in-memory merge — because the
 * save machinery ({@code SaveSync}, {@code SaveSnapshot}) re-reads the manifest later for its
 * {@code copy=} exclusions and must see the same configuration this launch used. The package's
 * {@code dgplayer.ini} is never modified; deleting the override reverts to it.
 */
class GameManifest {
    private static final String TAG = "DGPlayerBridge";

    static final String FILENAME = "dgplayer.ini";
    /**
     * Bridge-owned copy of the settings DGPlayer sent for this launch. Wins over {@link #FILENAME}
     * when present; absent means the package's own file applies. Excluded from save tracking.
     */
    static final String OVERRIDE_FILENAME = ".dgp_manifest.ini";
    private static final String KEY_COPY = "copy";
    private static final String KEY_REG = "reg";
    private static final String KEY_CD = "cd";

    private final Map<String, String> values = new HashMap<>();
    private final List<String[]> copies = new ArrayList<>();
    private final List<String> regFiles = new ArrayList<>();
    private final List<String[]> cds = new ArrayList<>();

    private GameManifest() {}

    /**
     * Reads the effective manifest of a game folder: the DGPlayer override when one exists, else
     * the package's own {@code dgplayer.ini}, else an empty manifest.
     */
    static GameManifest read(File gameDir) {
        File override = new File(gameDir, OVERRIDE_FILENAME);
        if (override.isFile()) return parse(FileUtils.readLines(override));

        File file = new File(gameDir, FILENAME);
        if (!file.isFile()) return new GameManifest();
        return parse(FileUtils.readLines(file));
    }

    /**
     * The exact text {@link #read} would parse, or an empty string when the game has no manifest.
     *
     * <p>Every container knob a launch can change lives in this file, so its bytes are what tell
     * one launch configuration from another - see {@code GameLaunchActivity}'s session key.
     */
    static String rawText(File gameDir) {
        File override = new File(gameDir, OVERRIDE_FILENAME);
        File file = override.isFile() ? override : new File(gameDir, FILENAME);
        if (!file.isFile()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : FileUtils.readLines(file)) sb.append(line).append('\n');
        return sb.toString();
    }

    /**
     * Writes or removes the override file for this launch.
     *
     * <p>Non-blank text is written through a temp file and renamed, so a crash mid-write cannot
     * leave a truncated manifest that would then be trusted on the next launch. Null or blank text
     * removes the override, which is how "delete my edits" in DGPlayer takes effect here.
     *
     * @return the source the next {@link #read} will use: {@code "intent"}, {@code "archive"} or
     *         {@code "none"} — for the launch log
     */
    static String applyOverride(File gameDir, String iniText) {
        File override = new File(gameDir, OVERRIDE_FILENAME);
        if (iniText == null || iniText.trim().isEmpty()) {
            if (override.exists() && !override.delete()) {
                Log.w(TAG, "could not remove stale manifest override "+override);
            }
            return new File(gameDir, FILENAME).isFile() ? "archive" : "none";
        }

        File tmp = new File(gameDir, OVERRIDE_FILENAME+".tmp");
        if (!FileUtils.writeString(tmp, iniText) || !tmp.renameTo(override)) {
            tmp.delete();
            Log.w(TAG, "could not write manifest override, using the archive's file");
            return new File(gameDir, FILENAME).isFile() ? "archive" : "none";
        }
        return "intent";
    }

    /** Parses manifest lines. Package-visible so the grammar has exactly one implementation. */
    static GameManifest parse(List<String> lines) {
        GameManifest manifest = new GameManifest();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;

            int index = line.indexOf('=');
            if (index <= 0) continue;

            String key = line.substring(0, index).trim();
            String value = line.substring(index+1).trim();
            if (key.isEmpty() || value.isEmpty()) continue;

            if (key.equals(KEY_COPY)) {
                int arrow = value.indexOf("->");
                if (arrow > 0) {
                    String from = value.substring(0, arrow).trim();
                    String to = value.substring(arrow+2).trim();
                    if (!from.isEmpty() && !to.isEmpty()) manifest.copies.add(new String[]{from, to});
                }
            }
            else if (key.equals(KEY_REG)) manifest.regFiles.add(value);
            else if (key.equals(KEY_CD)) {
                // cd=<dir relative to the game folder> [-> <volume label>], repeatable in disc order.
                int arrow = value.indexOf("->");
                String dir = (arrow > 0 ? value.substring(0, arrow) : value).trim();
                String label = arrow > 0 ? value.substring(arrow+2).trim() : "";
                if (!dir.isEmpty()) manifest.cds.add(new String[]{dir, label.isEmpty() ? null : label});
            }
            else manifest.values.put(key, value);
        }
        return manifest;
    }

    /** Each entry is {sourceRelativeToGameDir, destinationDosPath}. */
    List<String[]> getCopies() {
        return copies;
    }

    /** {@code .reg} files, relative to the game directory, to merge into the prefix before launch. */
    List<String> getRegFiles() {
        return regFiles;
    }

    /** Each entry is {discDirRelativeToGameDir, volumeLabelOrNull}, in disc order. */
    List<String[]> getCds() {
        return cds;
    }

    String get(String key) {
        return values.get(key);
    }

    boolean getBoolean(String key) {
        String value = values.get(key);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
