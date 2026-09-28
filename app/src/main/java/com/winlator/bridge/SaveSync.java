package com.winlator.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a Windows game's in-game saves alive outside this app.
 *
 * <p>Everything a game writes lands in this app's private storage: the game folder under
 * {@code drive_c/DGPlayer/<gameId>}, the Wine user profile, {@code C:\windows}. DGPlayer cannot read
 * any of it, and three ordinary events destroy it — repackaging the game (the bridge wipes the game
 * folder so old and new files cannot merge), deleting the container, and uninstalling this app.
 *
 * <p>So the saves are mirrored into an archive that lives in DGPlayer's own storage, reached through
 * a content URI DGPlayer grants with the launch intent ({@link #EXTRA_SAVE_URI}). From there they
 * ride along with DGPlayer's existing per-game and full cloud backup. The flow per launch is:
 *
 * <ol>
 *   <li>{@link #hasPendingExport} + {@link #exportOnExit} — rescue a previous session that never
 *       reached {@code exit()} (crash, force-stop), before a re-import can wipe the game folder</li>
 *   <li>{@link #onPayloadInstalled} — after an import, record what the package itself contains so
 *       those files are never mistaken for saves</li>
 *   <li>{@link #restoreIfNeeded} — pull the archive in when it is newer than what this device last
 *       restored, or when the game folder was just wiped</li>
 *   <li>{@link #beginSession} — photograph the shared roots so this game's writes can be told apart
 *       from what was already there</li>
 *   <li>{@link #exportOnExit} — at game exit, diff, accumulate, and write the archive back</li>
 * </ol>
 *
 * <p>Which files count as saves is decided by {@link SaveSnapshot}; the archive format and its
 * {@code stamp} by {@link SaveArchive}; the per-game bookkeeping by {@link SaveState}.
 *
 * <p>Nothing here may throw into a caller: a failed save sync must never take a game launch or a
 * game exit down with it.
 */
public abstract class SaveSync {
    static final String TAG = "DGPlayerBridge";

    /** Content URI of DGPlayer's {@code files/win/save/<fileName>.zip}, granted read + write. */
    public static final String EXTRA_SAVE_URI = "save_uri";
    /** Passed on to {@code XServerDisplayActivity} so both halves key the same state file. */
    public static final String EXTRA_GAME_ID = GameLaunchActivity.EXTRA_GAME_ID;

    static final int FORMAT_VERSION = 1;
    /** Stat value meaning "known to this game, currently absent" (a save the game deleted). */
    static final long[] MISSING = new long[0];

    /** A single save file this large is a disc image or a crash dump, not a save. */
    static final long MAX_FILE_BYTES = 64L << 20;
    /** DGPlayer drops anything over 80 MB from cloud backup; stay clear of that ceiling. */
    static final long MAX_TOTAL_BYTES = 64L << 20;

    /** Staging area for archives being read or written. Inside filesDir, never in the cache. */
    static File tempDir(Context context) {
        File dir = new File(SaveState.dirFor(context), "tmp");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Holds the {@link SaveState#sessionToken} of the most recent session in the shared container. */
    private static final String SESSION_MARKER = "current_session";

    static boolean hasPendingExport(Context context, String gameId) {
        if (context == null || gameId == null) return false;
        return SaveState.load(context, gameId).pendingExport;
    }

    /**
     * Exports every session that never reached {@code exit()} — of <em>any</em> game — before this
     * launch touches the container.
     *
     * <p>The container is shared by the whole library and sessions run one at a time, so right now it
     * holds exactly what the last session left. That is the only moment a dead session's changes to
     * the shared roots can still be told apart. Deferring the catch-up to that game's own next launch
     * (as before) diffed it against a baseline older than every game played in between, and pulled
     * their saves into its archive: A crashes, B is played, A is launched → B's saves in A.save.zip.
     *
     * <p>A game whose session is still running (the player went back to DGPlayer without exiting and
     * pressed Play on another game) is pending too, and is exported here for the same reason — the
     * restore below is about to write the launching game's saves into the shared roots under it.
     *
     * <p>Each game is exported through its own archive: the launching game's through the fresh
     * {@code save_uri}, others through the one recorded at their last session. A revoked grant
     * (reboot) just leaves that game pending; its later export then skips the shared roots (see
     * {@link #export}).
     */
    static void flushPendingSessions(Context context, Container container, String launchingGameId,
                                     Uri launchingSaveUri) {
        for (String gameId : SaveState.pendingGameIds(context)) {
            try {
                Uri saveUri;
                if (gameId.equals(launchingGameId)) saveUri = launchingSaveUri;
                else {
                    String recorded = SaveState.load(context, gameId).saveUri;
                    saveUri = recorded != null ? Uri.parse(recorded) : null;
                }
                if (saveUri == null) {
                    Log.w(TAG, "pending save export for " + gameId + " has no archive uri, leaving it");
                    continue;
                }
                Log.i(TAG, "flushing pending save export gameId=" + gameId
                        + (gameId.equals(launchingGameId) ? " (this game)" : " (launching " + launchingGameId + ")"));
                if (archiveReplacedSinceSession(context, gameId, saveUri)) discardPendingExport(context, gameId);
                else exportOnExit(context, container, gameId, saveUri);
            }
            catch (Throwable t) {
                Log.e(TAG, "could not flush the pending export of " + gameId, t);
            }
        }
    }

    private static File sessionMarker(Context context) {
        return new File(SaveState.dirFor(context), SESSION_MARKER);
    }

    /** Null when no session has recorded itself yet (state from a build before the marker existed). */
    private static String currentSessionToken(Context context) {
        File marker = sessionMarker(context);
        if (!marker.isFile()) return null;
        String token = FileUtils.readString(marker);
        return token != null && !token.trim().isEmpty() ? token.trim() : null;
    }

    /**
     * True when the archive is no longer the one {@link #beginSession} saw — the player restored from
     * the cloud, imported a zip, or brought one from another device after that session died.
     *
     * <p>Compared against the session's own observation, not {@code lastStamp}: that is "last
     * applied", and stays null when the first launch could not read the archive, which would make a
     * crashed session's real progress look replaceable. Unknown on either side means false, i.e. the
     * old behaviour (export the dead session).
     */
    static boolean archiveReplacedSinceSession(Context context, String gameId, Uri saveUri) {
        try {
            SaveState state = SaveState.load(context, gameId);
            if (state.sessionStamp == null) return false;
            String now = SaveArchive.peekStamp(context, saveUri);
            if (now == null || now.equals(state.sessionStamp)) return false;
            Log.w(TAG, "save archive replaced since the last session (" + state.sessionStamp + " -> "
                    + now + "), keeping it instead of exporting that session");
            return true;
        }
        catch (Throwable t) {
            Log.e(TAG, "could not compare the save archive", t);
            return false;
        }
    }

    /**
     * Drops a dead session's pending export in favour of an archive the player replaced since.
     *
     * <p>Baselines are deliberately left alone: rebuilding them now would record files that session
     * created in the game folder as part of the package, hiding them from every later export. Left
     * as they are, the persistent game folder baseline still catches them at the next exit. What the
     * dead session wrote into the shared roots is lost — the new archive is restored over it anyway.
     */
    static void discardPendingExport(Context context, String gameId) {
        try {
            SaveState state = SaveState.load(context, gameId);
            state.pendingExport = false;
            state.save(context);
        }
        catch (Throwable t) {
            Log.e(TAG, "could not discard the pending export", t);
        }
    }

    /**
     * True when {@code file} is one of this game's tracked saves — something the game itself wrote,
     * as opposed to a file the bridge placed there.
     */
    static boolean isTrackedSave(Context context, Container container, String gameId, File file) {
        try {
            String rel = SaveSnapshot.rel(new File(container.getRootDir(), ".wine/drive_c"), file);
            if (rel == null) return false;
            long[] stat = SaveState.load(context, gameId).tracked.get(rel);
            return stat != null && stat.length >= 2;
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * Records the freshly extracted package as the game folder's baseline.
     *
     * <p>Without this every file the package ships would read as "new" at the next exit and the
     * archive would swell to the size of the game. Called right after a successful import, when the
     * folder holds exactly the package and nothing else.
     */
    static void onPayloadInstalled(Context context, Container container, String gameId) {
        try {
            SaveState state = SaveState.load(context, gameId);
            SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId);
            state.gameDirBaseline = SaveSnapshot.snapshot(roots, SaveSnapshot.Scope.GAME_DIR);
            state.containerId = container.id;
            state.save(context);
            Log.i(TAG, "save baseline after install: " + state.gameDirBaseline.size() + " files");
        }
        catch (Throwable t) {
            Log.e(TAG, "could not record the install baseline", t);
        }
    }

    /**
     * Restores the archive into the container when appropriate.
     *
     * <p>The decision is a stamp comparison, not a timestamp one: clocks and mtimes are unreliable
     * across a cloud round trip, while the stamp is a fresh id minted at each export and recorded
     * locally once applied.
     *
     * <ul>
     *   <li>different stamp (or nothing restored here yet) — restore everything. This is cloud
     *       restore, a manual import, a reinstalled app</li>
     *   <li>same stamp but the payload was just re-imported — the game folder was wiped, so it is
     *       restored in full; the shared roots only get what is missing. Usually nothing is: they
     *       survived and this device's copies are at least as new. But a re-import also happens when
     *       the container itself was recreated, and then the profile saves exist only in the archive
     *       (restoring the game folder alone lost them)</li>
     *   <li>same stamp, no re-import — fill in only what is missing. The container's copies are at
     *       least as new so they are left alone, but a file that is simply gone (the container was
     *       cleared, a save was deleted) has the archive as its last copy</li>
     * </ul>
     *
     * @return true when files were written into the container
     */
    static boolean restoreIfNeeded(Context context, Container container, String gameId, Uri saveUri,
                                   boolean payloadReinstalled) {
        if (context == null || container == null || gameId == null || saveUri == null) return false;

        try {
            long started = SystemClock.elapsedRealtime();
            SaveState state = SaveState.load(context, gameId);
            SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId);

            SaveArchive.Archive archive = SaveArchive.open(context, saveUri, tempDir(context), gameId);
            if (archive == null) {
                Log.i(TAG, "save restore gameId=" + gameId + " -> skip (no usable archive)");
                return false;
            }

            try {
                boolean sameStamp = archive.stamp.equals(state.lastStamp);
                boolean overwriteGameDir = !sameStamp || payloadReinstalled;
                boolean overwriteShared = !sameStamp;

                Map<String, long[]> restored = new LinkedHashMap<>();
                int written = SaveArchive.extract(archive, roots, overwriteGameDir, overwriteShared, restored);

                String mode = overwriteShared ? "full" : (overwriteGameDir ? "gameDir" : "missingOnly");
                if (written == 0) {
                    Log.i(TAG, "save restore gameId=" + gameId + " stamp=" + archive.stamp
                            + " -> nothing to do (" + mode + ")");
                    return false;
                }

                state.tracked.putAll(restored);
                // Only extend an existing baseline. A null one means the game folder has never been
                // photographed, and seeding it with just these files would make the whole package
                // look like a save at the next export.
                if (state.gameDirBaseline != null) {
                    for (Map.Entry<String, long[]> entry : restored.entrySet()) {
                        if (entry.getKey().startsWith(roots.gameDirPrefix)) {
                            state.gameDirBaseline.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                state.lastStamp = archive.stamp;
                state.containerId = container.id;
                state.save(context);

                Log.i(TAG, String.format(Locale.ENGLISH,
                        "save restore gameId=%s zipStamp=%s reinstalled=%b -> %s entries=%d ms=%d",
                        gameId, archive.stamp, payloadReinstalled, mode, written,
                        SystemClock.elapsedRealtime() - started));
                return true;
            }
            finally {
                archive.close();
            }
        }
        catch (Throwable t) {
            Log.e(TAG, "save restore failed", t);
            return false;
        }
    }

    /**
     * Photographs the shared roots at launch.
     *
     * <p>Per session on purpose: the container is shared by the whole library, so a file another
     * game wrote between two sessions of this one would otherwise surface here as this game's save.
     * The game folder baseline is not reset here — it is persistent, so in-folder changes made by a
     * session that crashed are still caught at the next exit.
     */
    static void beginSession(Context context, Container container, String gameId, Uri saveUri) {
        try {
            SaveState state = SaveState.load(context, gameId);
            SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId);
            state.sharedBaseline = SaveSnapshot.snapshot(roots, SaveSnapshot.Scope.SHARED);
            state.containerId = container.id;
            // Unreadable (null) is stored as unknown, so a later comparison falls back to exporting.
            state.sessionStamp = SaveArchive.peekStamp(context, saveUri);
            state.saveUri = saveUri != null ? saveUri.toString() : null;
            state.sessionToken = UUID.randomUUID().toString();
            state.pendingExport = true;
            state.save(context);
            // After the state: a marker pointing at a token no state holds would distrust everyone.
            File dir = SaveState.dirFor(context);
            if (dir.isDirectory() || dir.mkdirs()) FileUtils.writeString(sessionMarker(context), state.sessionToken);
            Log.i(TAG, "save session gameId=" + gameId + " containerId=" + container.id
                    + " sharedBaseline=" + state.sharedBaseline.size() + " files tracked="
                    + state.tracked.size());
        }
        catch (Throwable t) {
            Log.e(TAG, "could not start the save session", t);
        }
    }

    /**
     * Diffs against the baselines, adds whatever changed to this game's tracked set, and writes the
     * archive back to DGPlayer.
     *
     * <p>Runs before {@code finish()} so Android cannot reclaim the process halfway through, and
     * swallows everything — an exit path must not be able to crash.
     */
    public static void exportOnExit(Context context, Container container, String gameId, Uri saveUri) {
        if (context == null || container == null || gameId == null || saveUri == null) return;
        try {
            export(context, container, gameId, saveUri);
        }
        catch (Throwable t) {
            Log.e(TAG, "save export failed", t);
        }
    }

    private static void export(Context context, Container container, String gameId, Uri saveUri) {
        long started = SystemClock.elapsedRealtime();
        SaveState state = SaveState.load(context, gameId);
        SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId);

        Map<String, long[]> current = SaveSnapshot.snapshot(roots, SaveSnapshot.Scope.ALL);

        // Re-filter what is already tracked. Without this a path that a past build wrongly picked
        // up would be exported for the rest of the game's life, since tracking is cumulative and
        // never re-examined - widening the exclusion list has to clean up after itself.
        int untracked = 0;
        for (Iterator<String> it = state.tracked.keySet().iterator(); it.hasNext(); ) {
            if (SaveSnapshot.isExcluded(roots, it.next())) {
                it.remove();
                untracked++;
            }
        }
        if (untracked > 0) Log.i(TAG, "save export dropped " + untracked + " newly excluded path(s)");

        boolean gameDirBaselineKnown = state.gameDirBaseline != null;
        Map<String, long[]> baseline = new LinkedHashMap<>();
        if (gameDirBaselineKnown) baseline.putAll(state.gameDirBaseline);
        baseline.putAll(state.sharedBaseline);

        // Another game's session started after this one (it is still exiting under a new launch, or it
        // died and a flush could not reach its archive). The shared roots now hold that game's
        // restored and written files, which this baseline would read as ours. Only the game folder
        // is still this game's alone. Tracked paths keep being exported with their current content.
        String latestSession = currentSessionToken(context);
        boolean sharedTrusted = latestSession == null || latestSession.equals(state.sessionToken);
        int sharedSkipped = 0;

        List<String> changed = new ArrayList<>();
        for (String rel : SaveSnapshot.diff(baseline, current)) {
            // Games imported before this feature existed have no install baseline. Claiming their
            // whole folder as "new" would upload the game itself, so skip the folder for one run and
            // rebuild the baseline below; only later changes are then picked up.
            boolean inGameDir = rel.startsWith(roots.gameDirPrefix);
            if (!gameDirBaselineKnown && inGameDir) continue;
            if (!sharedTrusted && !inGameDir) {
                sharedSkipped++;
                continue;
            }
            changed.add(rel);
        }
        if (sharedSkipped > 0) {
            Log.w(TAG, "save export gameId=" + gameId + ": another game's session started since this one,"
                    + " ignoring " + sharedSkipped + " shared-root change(s)");
        }
        if (!gameDirBaselineKnown) {
            Log.w(TAG, "no install baseline for " + gameId + ", rebuilding it and skipping the "
                    + "game folder diff this once");
        }

        int added = 0;
        for (String rel : changed) {
            if (state.tracked.put(rel, current.get(rel)) == null) added++;
        }

        int existing = 0;
        for (String rel : state.tracked.keySet()) {
            if (new File(roots.driveC, rel).isFile()) existing++;
        }
        if (existing == 0) {
            // Includes the case where the container was deleted: the archive DGPlayer already holds
            // is then the only copy of these saves, and must not be overwritten with an empty one.
            state.pendingExport = false;
            rebuildBaselines(state, roots, current);
            state.save(context);
            Log.i(TAG, "save export gameId=" + gameId + " -> nothing to save");
            return;
        }

        if (changed.isEmpty() && unchangedSinceLastExport(state, current)
                && archiveLooksPresent(context, saveUri)) {
            state.pendingExport = false;
            rebuildBaselines(state, roots, current);
            state.save(context);
            Log.i(TAG, "save export gameId=" + gameId + " -> skipped: unchanged");
            return;
        }

        long[] bytes = new long[1];
        String stamp = SaveArchive.write(context, saveUri, tempDir(context), gameId, container.id,
                new ArrayList<>(state.tracked.keySet()), roots, current, bytes);

        if (stamp == null) {
            // Leave pendingExport set so the next launch retries before anything can wipe the files.
            state.containerId = container.id;
            state.save(context);
            Log.e(TAG, "save export gameId=" + gameId + " -> failed, will retry on next launch");
            return;
        }

        int missing = 0;
        for (String rel : new ArrayList<>(state.tracked.keySet())) {
            long[] stat = current.get(rel);
            if (stat == null) {
                state.tracked.put(rel, MISSING);
                missing++;
            }
            else state.tracked.put(rel, stat);
        }

        state.lastStamp = stamp;
        state.lastExportAt = System.currentTimeMillis();
        state.pendingExport = false;
        state.containerId = container.id;
        rebuildBaselines(state, roots, current);
        state.save(context);

        Log.i(TAG, String.format(Locale.ENGLISH,
                "save export gameId=%s stamp=%s files=%d bytes=%d new=%d changed=%d missing=%d "
                        + "dropped=%d ms=%d",
                gameId, stamp, existing, bytes[0], added, changed.size(), missing, untracked,
                SystemClock.elapsedRealtime() - started));
    }

    private static void rebuildBaselines(SaveState state, SaveSnapshot.Roots roots,
                                         Map<String, long[]> current) {
        Map<String, long[]> gameDir = new LinkedHashMap<>();
        Map<String, long[]> shared = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : current.entrySet()) {
            if (entry.getKey().startsWith(roots.gameDirPrefix)) gameDir.put(entry.getKey(), entry.getValue());
            else shared.put(entry.getKey(), entry.getValue());
        }
        state.gameDirBaseline = gameDir;
        state.sharedBaseline = shared;
    }

    /** True when every tracked file is exactly as the last export left it. */
    private static boolean unchangedSinceLastExport(SaveState state, Map<String, long[]> current) {
        for (Map.Entry<String, long[]> entry : state.tracked.entrySet()) {
            long[] before = entry.getValue();
            long[] now = current.get(entry.getKey());
            boolean beforeMissing = before == null || before.length < 2;
            if (now == null) {
                if (!beforeMissing) return false;
            }
            else if (beforeMissing || !SaveSnapshot.sameStat(before, now)) return false;
        }
        return true;
    }

    private static boolean archiveLooksPresent(Context context, Uri saveUri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(saveUri, "r")) {
            return pfd != null && pfd.getStatSize() > 22;
        }
        catch (Exception e) {
            return false;
        }
    }
}
