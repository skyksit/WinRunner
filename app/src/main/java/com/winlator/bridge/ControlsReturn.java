package com.winlator.bridge;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hands an in-game layout edit back to DGPlayer.
 *
 * <p>DGPlayer owns the layout library and pushes its copy on every launch, which {@link
 * BridgeControlsProfiles#upsert} writes over the profile file here. So an edit made in-game - the
 * DGP EDIT CONTROLS button, or the drawer's Input Controls settings - would be gone by the next
 * launch. DGPlayer cannot pull it either: it never learns when a session ends. So it sends a
 * writable URI, the same arrangement as {@link SaveSync#EXTRA_SAVE_URI}, and this writes the
 * profile there whenever an editor closes with the file changed, and once more on exit.
 *
 * <p>Only the profile the session was launched with is returned, never whichever one is on screen:
 * the URI belongs to that one library entry.
 */
public final class ControlsReturn {
    private static final String TAG = "DGPlayerBridge";

    /** Introduced in versionCode 39; DGPlayer does not gate on it, older builds ignore the extra. */
    public static final String EXTRA_CONTROLS_RETURN_URI = "controls_return_uri";

    private final Context context;
    private final int profileId;
    private final Uri uri;
    /** The file as DGPlayer last knew it. Only a change from this is worth sending back. */
    private String baseline;

    private ControlsReturn(Context context, int profileId, Uri uri) {
        this.context = context.getApplicationContext();
        this.profileId = profileId;
        this.uri = uri;
        this.baseline = read();
    }

    /** Null when the launch did not ask for a return, so callers only need a null check. */
    public static ControlsReturn from(Context context, int profileId, Uri uri) {
        if (context == null || profileId <= 0 || uri == null) return null;
        return new ControlsReturn(context, profileId, uri);
    }

    /**
     * The file now matches what DGPlayer has - it has just pushed it again (a resumed launch goes
     * through the upsert before it reaches the running session).
     */
    public synchronized void rebase() {
        baseline = read();
    }

    /**
     * Writes the profile to DGPlayer if it changed since the baseline. File I/O, so never on the UI
     * thread; swallows everything, because the exit path calls it.
     */
    public synchronized void exportIfChanged() {
        try {
            String current = read();
            if (current == null || current.isEmpty() || current.equals(baseline)) return;

            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IllegalStateException("no output stream for "+uri);
                out.write(current.getBytes(StandardCharsets.UTF_8));
            }
            baseline = current;
            Log.i(TAG, "returned controls profile edited in-game (id "+profileId+", "+current.length()+" chars)");
        }
        catch (Throwable t) {
            Log.e(TAG, "controls profile return failed", t);
        }
    }

    private String read() {
        File file = ControlsProfile.getProfileFile(context, profileId);
        // FileUtils.readString throws on a missing file rather than returning null.
        return file.isFile() ? FileUtils.readString(file) : null;
    }
}
