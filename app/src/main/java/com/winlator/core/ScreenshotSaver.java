package com.winlator.core;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.widget.Toast;

import com.winlator.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DGPlayer VPAD's camera button (KEY_DGP_SCREENSHOT): copies what the game surface shows into the
 * phone's gallery, in the same Pictures/dosgameplayer folder DGPlayer itself uses.
 *
 * PixelCopy reads the composited surface, so the on-screen controls (a separate view) are not in the
 * picture while any active effects are. The letterbox is cropped using the renderer's view rect.
 */
public final class ScreenshotSaver {
    private static final String TAG = "DGPlayerScreenshot";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/dosgameplayer";
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    private ScreenshotSaver() {}

    /** @param crop the rect of the surface that holds the game image, or null for the whole surface */
    public static void capture(Activity activity, SurfaceView view, Rect crop, String baseName) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Below Q, writing to Pictures would need a runtime storage permission we never ask for.
            toast(activity, false);
            return;
        }
        if (!inFlight.compareAndSet(false, true)) return;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(view, bitmap, result -> {
                if (result != PixelCopy.SUCCESS) {
                    Log.w(TAG, "PixelCopy failed: " + result);
                    bitmap.recycle();
                    inFlight.set(false);
                    toast(activity, false);
                    return;
                }
                new Thread(() -> {
                    Bitmap image = cropTo(bitmap, crop);
                    boolean ok = save(activity.getContentResolver(), image, fileName(baseName));
                    if (image != bitmap) image.recycle();
                    bitmap.recycle();
                    inFlight.set(false);
                    activity.runOnUiThread(() -> toast(activity, ok));
                }, "DGPlayerScreenshot").start();
            }, new Handler(Looper.getMainLooper()));
        }
        catch (IllegalArgumentException e) {
            Log.w(TAG, "PixelCopy rejected", e);
            bitmap.recycle();
            inFlight.set(false);
            toast(activity, false);
        }
    }

    private static Bitmap cropTo(Bitmap source, Rect crop) {
        if (crop == null) return source;
        Rect r = new Rect(crop);
        if (!r.intersect(0, 0, source.getWidth(), source.getHeight()) || r.width() <= 0 || r.height() <= 0) return source;
        if (r.width() == source.getWidth() && r.height() == source.getHeight()) return source;
        return Bitmap.createBitmap(source, r.left, r.top, r.width(), r.height());
    }

    private static String fileName(String baseName) {
        String safe = baseName == null || baseName.trim().isEmpty() ? "screenshot" : baseName;
        int dot = safe.lastIndexOf('.');
        if (dot > 0) safe = safe.substring(0, dot);
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        // Numbers only, so pin the locale (a Thai default would render the Buddhist-era year).
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return safe + "_" + stamp + ".png";
    }

    private static boolean save(ContentResolver resolver, Bitmap bitmap, String displayName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) return false;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("compress failed");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return true;
        }
        catch (Exception e) {
            Log.w(TAG, "saving " + displayName + " failed", e);
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private static void toast(Activity activity, boolean ok) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity, ok ? R.string.dgp_screenshot_saved : R.string.dgp_screenshot_failed, Toast.LENGTH_SHORT).show();
    }
}
