package com.winlator.bridge;

import android.os.Process;
import android.system.OsConstants;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Helper programs a package ships next to its game ({@code tool=} in the manifest), run from the
 * drawer in place of the game.
 *
 * <p>The motivating case is a Diablo II save editor, and it shows why the game has to go away
 * while the tool runs: the game holds the open character file (the editor reports it cannot open
 * it), would write its in-memory copy back over the edit, and its hidden cursor is the one drawn
 * over the editor. So the tool is started first, the game is stopped once the tool is up, and the
 * game is started again when the tool closes.
 *
 * <p>All of that is one guest command line, see {@link #chainParameters}: a {@code cmd} that
 * waits for the tool and then starts the game. Keeping it in the guest matters for two reasons.
 * The session ends when no guest program is left, so something has to be alive between the tool
 * closing and the game coming back - the waiting {@code cmd} is. And the game must be restarted
 * from its own folder: winhandler's EXEC does not set a working directory, and a game started
 * elsewhere misses files it reads relative to it (D2DX's d2dx.cfg, whose absence makes it refuse
 * to run).
 */
public final class GameTools {
    private static final String TAG = "DGPlayerBridge";

    public static final String EXTRA_TOOL_PATHS = "tool_paths";
    public static final String EXTRA_TOOL_NAMES = "tool_names";
    /** Unix paths: the library folder inside the game, where picks are staged, and the marker file. */
    public static final String EXTRA_LIBRARY_DIR = "tool_library_dir";
    public static final String EXTRA_LIBRARY_TARGET = "tool_library_target";
    public static final String EXTRA_LIBRARY_MARKER = "tool_library_marker";

    /**
     * Lists the files a launch staged, one absolute path per line, in the game folder. A file rather
     * than memory so a crash or a killed process still gets them cleaned up on the next launch.
     */
    public static final String STAGED_MARKER = ".dgp_tool_staged";
    /** Item Store pages and the tool's file dialog both get unwieldy past this. */
    public static final int MAX_PICKS = 100;

    /**
     * {@code toolLibrary=<folder in the game> -> <DOS folder>}: files the player may pick before a
     * tool starts, copied into the DOS folder for the tool to find and removed when it closes. For
     * UdieToo that folder is the save path, which its Import Item dialog opens on - so the dialog
     * shows exactly the items that were picked.
     */
    static final String KEY_LIBRARY = "toolLibrary";

    private GameTools() {}

    /**
     * Parameters for {@code start.exe}: a minimized {@code cmd} (its console would otherwise sit on
     * the screen behind the tool) that starts the tool from its folder, waits for it, then starts
     * the game from its folder.
     *
     * @param toolDosPath e.g. {@code C:\DGPlayer\diablo2\UdieToo.exe}
     * @param gameDosPath e.g. {@code D:\Game.exe}
     * @param gameArgs    the game's launch arguments, or null
     */
    public static String chainParameters(String toolDosPath, String gameDosPath, String gameArgs) {
        String args = gameArgs != null && !gameArgs.trim().isEmpty() ? " "+gameArgs.trim() : "";
        return "/min cmd.exe /c "
                +"start \"\" /d "+dosDir(toolDosPath)+" /wait "+quote(FileUtils.getName(toolDosPath))
                +" & start \"\" /d "+dosDir(gameDosPath)+" "+quote(FileUtils.getName(gameDosPath))+args;
    }

    /**
     * The folder of a DOS path, in a form {@code start /d} reads correctly. A drive root keeps its
     * backslash ({@code D:} alone would mean the drive's current directory), and nothing that ends
     * in a backslash is quoted: {@code "D:\"} reaches the program as {@code D:"}.
     */
    static String dosDir(String dosPath) {
        String dir = FileUtils.getDirname(dosPath);
        if (dir.endsWith(":")) return dir+"\\";
        return quote(dir);
    }

    static String quote(String token) {
        return token.indexOf(' ') >= 0 ? "\""+token+"\"" : token;
    }

    /**
     * Which picked files to copy. A name already present in the target is skipped - it may be an item
     * the player exported there - and so is a repeated name, since the copies share one folder.
     * Windows names are case-insensitive, so the comparison is too.
     */
    static boolean[] planStaging(List<String> pickedNames, Collection<String> existingNames) {
        Set<String> taken = new HashSet<>();
        for (String name : existingNames) taken.add(name.toLowerCase(Locale.ROOT));
        boolean[] copy = new boolean[pickedNames.size()];
        for (int i = 0; i < copy.length; i++) copy[i] = taken.add(pickedNames.get(i).toLowerCase(Locale.ROOT));
        return copy;
    }

    /** Copies the picks into {@code target} and records them in {@code marker}; returns how many. */
    public static int stage(List<File> picked, File target, File marker) {
        String[] existing = target.list();
        List<String> names = new ArrayList<>();
        for (File file : picked) names.add(file.getName());
        boolean[] copy = planStaging(names, existing != null ? Arrays.asList(existing) : Collections.emptyList());

        StringBuilder staged = new StringBuilder();
        int count = 0;
        target.mkdirs();
        for (int i = 0; i < copy.length; i++) {
            if (!copy[i]) continue;
            File destination = new File(target, names.get(i));
            if (!FileUtils.copy(picked.get(i), destination)) {
                Log.w(TAG, "tool: could not stage "+picked.get(i));
                continue;
            }
            staged.append(destination.getAbsolutePath()).append('\n');
            count++;
        }
        // Replacing an older marker loses nothing: callers unstage() first, so what it listed is gone.
        if (count > 0) FileUtils.writeString(marker, staged.toString());
        Log.i(TAG, "tool: staged "+count+"/"+picked.size()+" into "+target);
        return count;
    }

    /** Deletes what {@link #stage} recorded in {@code marker}, then the marker. Safe to repeat. */
    public static void unstage(File marker) {
        if (marker == null || !marker.isFile()) return;
        int count = 0;
        for (String line : FileUtils.readLines(marker)) {
            if (!line.trim().isEmpty() && new File(line.trim()).delete()) count++;
        }
        marker.delete();
        Log.i(TAG, "tool: removed "+count+" staged file(s)");
    }

    /** A pickable file and its path below the library folder, '/'-separated. */
    public static final class LibraryItem {
        public final File file;
        public final String relativePath;

        LibraryItem(File file, String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
        }

        public String name() {
            return FileUtils.getBasename(file.getName());
        }

        /** The folder part, or "" at the top level. */
        public String folder() {
            int slash = relativePath.lastIndexOf('/');
            return slash < 0 ? "" : relativePath.substring(0, slash);
        }
    }

    /** Every file below {@code root}, sorted by path. Hidden files are left out. */
    public static List<LibraryItem> scanLibrary(File root) {
        List<LibraryItem> items = new ArrayList<>();
        scan(root, "", items);
        Collections.sort(items, (a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
        return items;
    }

    private static void scan(File dir, String prefix, List<LibraryItem> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().startsWith(".")) continue;
            String rel = prefix.isEmpty() ? child.getName() : prefix+"/"+child.getName();
            if (child.isDirectory()) scan(child, rel, out);
            else if (child.isFile()) out.add(new LibraryItem(child, rel));
        }
    }

    /** Running guest processes of an executable, matched by file name like Windows does. */
    public static List<ProcessHelper.PStat> findProcesses(String exePath) {
        String wanted = FileUtils.getName(exePath).toLowerCase(Locale.ROOT);
        List<ProcessHelper.PStat> result = new ArrayList<>();
        for (ProcessHelper.PStat stat : ProcessHelper.getChildProcesses()) {
            if (FileUtils.getName(stat.name).toLowerCase(Locale.ROOT).equals(wanted)) result.add(stat);
        }
        return result;
    }

    /**
     * Ends the game's processes. SIGTERM, which Wine turns into a process exit: winhandler's own
     * kill request and {@code taskkill} both leave Diablo II running.
     */
    public static int stopProcesses(String exePath) {
        int count = 0;
        for (ProcessHelper.PStat stat : findProcesses(exePath)) {
            Process.sendSignal(stat.pid, OsConstants.SIGTERM);
            count++;
        }
        Log.i(TAG, "tool: stopped "+count+" process(es) of "+FileUtils.getName(exePath));
        return count;
    }
}
