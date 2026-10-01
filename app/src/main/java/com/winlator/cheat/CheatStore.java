package com.winlator.cheat;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DGPlayer's cheats file for this game, reached through a writable URI it grants - the same
 * arrangement as the save archive ({@code save_uri}) and the controls return. DGPlayer only sends it
 * to players allowed to use cheats (premium), so a launch without it has no saved cheats at all.
 *
 * Written on every change, not only on exit: the file is small, and a session can end in a crash.
 */
public final class CheatStore {
    private static final String TAG = "CheatSearch";
    /** Introduced in versionCode 50; DGPlayer only sends it to that version and later. */
    public static final String EXTRA_CHEATS_URI = "cheats_uri";

    private final Context context;
    private final Uri uri;

    private CheatStore(Context context, Uri uri) {
        this.context = context.getApplicationContext();
        this.uri = uri;
    }

    /** Null when the launch sent no cheats file, so callers only need a null check. */
    public static CheatStore from(Context context, Uri uri) {
        return context != null && uri != null ? new CheatStore(context, uri) : null;
    }

    /** Empty when the file does not exist yet or cannot be read; never throws. */
    public SavedCheats load() {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return new SavedCheats();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            SavedCheats saved = SavedCheats.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            Log.i(TAG, "loaded "+saved.cheats.size()+" saved cheats");
            return saved;
        }
        catch (Throwable t) {
            // A missing file (first cheat ever) lands here too.
            Log.i(TAG, "no saved cheats: "+t);
            return new SavedCheats();
        }
    }

    /** File I/O: not on the UI thread. Swallows everything, because the exit path calls it. */
    public synchronized boolean save(SavedCheats saved) {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("no output stream for "+uri);
            out.write(saved.toJson().getBytes(StandardCharsets.UTF_8));
            return true;
        }
        catch (Throwable t) {
            Log.e(TAG, "saving cheats failed", t);
            return false;
        }
    }
}
