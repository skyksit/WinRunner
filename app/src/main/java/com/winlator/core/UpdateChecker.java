package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.winlator.BuildConfig;
import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Checks the latest GitHub release for a newer APK and installs it in place.
 * The release workflow attaches update.json (versionCode, url, size, sha256, notes) next to the
 * APK, and /releases/latest/download/ always redirects to the newest release's copy of it.
 */
public abstract class UpdateChecker {
    private static final String UPDATE_INFO_URL = "https://github.com/skyksit/WinRunner/releases/latest/download/update.json";
    private static final long AUTO_CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    public static final String PREF_AUTO_CHECK = "auto_check_updates";
    private static final String PREF_LAST_CHECK = "update_last_check";
    private static final String PREF_SKIPPED_VERSION = "update_skipped_version_code";
    private static final String PREF_DOWNLOADED_VERSION = "update_downloaded_version_code";
    private static final String PREF_LATEST_VERSION = "update_latest_version_code";
    private static final String PREF_NOTIFIED_VERSION = "update_notified_version_code";
    private static File pendingInstall;

    private static class UpdateInfo {
        int versionCode;
        String versionName;
        String tag;
        String apk;
        String url;
        long size;
        String sha256;
        String notes;
    }

    public static void check(Activity activity, boolean manual) {
        check(activity, manual, false);
    }

    /** inGame: the install replaces the running app, so the dialog warns that the game closes. */
    public static void check(final Activity activity, final boolean manual, final boolean inGame) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        removeInstalledDownload(activity, preferences);

        if (!manual) {
            if (!preferences.getBoolean(PREF_AUTO_CHECK, true)) return;
            long lastCheck = preferences.getLong(PREF_LAST_CHECK, 0);
            if (Math.abs(System.currentTimeMillis() - lastCheck) < AUTO_CHECK_INTERVAL) return;
        }

        fetch(activity, preferences, (info) -> {
            if (info == null) {
                if (manual) AppUtils.showToast(activity, R.string.update_check_failed);
                return;
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                if (manual) AppUtils.showToast(activity, R.string.up_to_date);
                return;
            }
            if (!manual && preferences.getInt(PREF_SKIPPED_VERSION, 0) == info.versionCode) return;
            showUpdateDialog(activity, preferences, info, inGame);
        });
    }

    /**
     * Quiet check for screens that must not pop a dialog (a running game): at most once a day it
     * refreshes the latest known version, and onUpdateKnown runs (UI thread) whenever that version
     * is newer than this build - also from the stored value between checks.
     */
    public static void checkInBackground(final Activity activity, final Runnable onUpdateKnown) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!preferences.getBoolean(PREF_AUTO_CHECK, true)) return;

        long lastCheck = preferences.getLong(PREF_LAST_CHECK, 0);
        if (Math.abs(System.currentTimeMillis() - lastCheck) < AUTO_CHECK_INTERVAL) {
            if (hasKnownUpdate(activity)) onUpdateKnown.run();
            return;
        }

        fetch(activity, preferences, (info) -> {
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE) onUpdateKnown.run();
        });
    }

    public static boolean hasKnownUpdate(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getInt(PREF_LATEST_VERSION, 0) > BuildConfig.VERSION_CODE;
    }

    /** True once per new version, so the toast pointing at the menu is not shown on every launch. */
    public static boolean shouldNotifyKnownUpdate(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int latest = preferences.getInt(PREF_LATEST_VERSION, 0);
        if (latest <= BuildConfig.VERSION_CODE || preferences.getInt(PREF_NOTIFIED_VERSION, 0) == latest) return false;
        preferences.edit().putInt(PREF_NOTIFIED_VERSION, latest).apply();
        return true;
    }

    /** Downloads update.json; the callback runs on the UI thread, with null on failure. */
    private static void fetch(final Activity activity, final SharedPreferences preferences, final Callback<UpdateInfo> callback) {
        HttpUtils.download(UPDATE_INFO_URL, (content) -> {
            final UpdateInfo info = parse(content);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (info != null) {
                    preferences.edit()
                        .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                        .putInt(PREF_LATEST_VERSION, info.versionCode)
                        .apply();
                }
                callback.call(info);
            });
        });
    }

    /** Called from onResume: picks the install back up after the user allowed unknown sources. */
    public static void onResume(Activity activity) {
        if (pendingInstall != null && canInstallPackages(activity)) {
            File apkFile = pendingInstall;
            pendingInstall = null;
            if (apkFile.isFile()) startInstall(activity, apkFile);
        }
    }

    private static UpdateInfo parse(String content) {
        if (content == null) return null;
        try {
            JSONObject json = new JSONObject(content);
            UpdateInfo info = new UpdateInfo();
            info.versionCode = json.getInt("versionCode");
            info.versionName = json.optString("versionName", "");
            info.tag = json.optString("tag", "");
            info.apk = json.getString("apk");
            info.url = json.getString("url");
            info.size = json.optLong("size", 0);
            info.sha256 = json.optString("sha256", "").toLowerCase(Locale.ENGLISH);
            info.notes = json.optString("notes", "");
            // The file name ends up in the cache path; refuse anything that could leave it.
            if (info.apk.contains("/") || info.apk.contains("\\") || !info.url.startsWith("https://")) return null;
            return info;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static void showUpdateDialog(final Activity activity, final SharedPreferences preferences, final UpdateInfo info, boolean inGame) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.update_dialog);
        dialog.setTitle(R.string.update_available);

        String sizeText = info.size > 0 ? String.format(Locale.ENGLISH, " · %.1f MB", info.size / 1048576.0f) : "";
        String header = info.versionName + (info.tag.isEmpty() ? "" : " ("+info.tag+")") + sizeText;
        ((TextView)dialog.findViewById(R.id.TVUpdateVersion)).setText(header);

        TextView tvNotes = dialog.findViewById(R.id.TVUpdateNotes);
        tvNotes.setText(info.notes);
        if (info.notes.isEmpty()) tvNotes.setVisibility(View.GONE);
        dialog.findViewById(R.id.TVUpdateClosesGame).setVisibility(inGame ? View.VISIBLE : View.GONE);

        final CheckBox cbSkip = dialog.findViewById(R.id.CBSkipVersion);
        ((Button)dialog.findViewById(R.id.BTConfirm)).setText(R.string.update_now);
        ((Button)dialog.findViewById(R.id.BTCancel)).setText(R.string.later);

        dialog.setOnConfirmCallback(() -> {
            preferences.edit().remove(PREF_SKIPPED_VERSION).apply();
            downloadAndInstall(activity, preferences, info);
        });
        dialog.setOnCancelCallback(() -> {
            if (cbSkip.isChecked()) preferences.edit().putInt(PREF_SKIPPED_VERSION, info.versionCode).apply();
        });
        dialog.show();
    }

    private static File getUpdateDir(Activity activity) {
        return new File(activity.getCacheDir(), "update");
    }

    private static void removeInstalledDownload(Activity activity, SharedPreferences preferences) {
        int downloaded = preferences.getInt(PREF_DOWNLOADED_VERSION, 0);
        if (downloaded > 0 && downloaded <= BuildConfig.VERSION_CODE) {
            FileUtils.delete(getUpdateDir(activity));
            preferences.edit().remove(PREF_DOWNLOADED_VERSION).apply();
        }
    }

    private static void downloadAndInstall(final Activity activity, final SharedPreferences preferences, final UpdateInfo info) {
        final File updateDir = getUpdateDir(activity);
        final File apkFile = new File(updateDir, info.apk);

        // An earlier download of the same release that still verifies is installed as is.
        Executors.newSingleThreadExecutor().execute(() -> {
            final boolean cached = apkFile.isFile() && verify(apkFile, info);
            activity.runOnUiThread(() -> {
                if (cached) {
                    install(activity, apkFile);
                    return;
                }

                FileUtils.delete(updateDir);
                updateDir.mkdirs();
                preferences.edit().putInt(PREF_DOWNLOADED_VERSION, info.versionCode).apply();
                HttpUtils.download(activity, info.url, apkFile, (success) -> {
                    if (!success) {
                        AppUtils.showToast(activity, R.string.update_download_failed);
                        return;
                    }
                    Executors.newSingleThreadExecutor().execute(() -> {
                        final boolean valid = verify(apkFile, info);
                        activity.runOnUiThread(() -> {
                            if (valid) install(activity, apkFile);
                            else {
                                apkFile.delete();
                                AppUtils.showToast(activity, R.string.update_verify_failed);
                            }
                        });
                    });
                });
            });
        });
    }

    private static boolean verify(File file, UpdateInfo info) {
        if (info.size > 0 && file.length() != info.size) return false;
        if (info.sha256.isEmpty()) return true;
        try (InputStream inStream = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inStream.read(buffer)) != -1) digest.update(buffer, 0, bytesRead);

            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ENGLISH, "%02x", b));
            return hex.toString().equals(info.sha256);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static boolean canInstallPackages(Activity activity) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void install(final Activity activity, final File apkFile) {
        if (canInstallPackages(activity)) {
            startInstall(activity, apkFile);
            return;
        }

        ContentDialog.confirm(activity, R.string.allow_unknown_sources_hint, () -> {
            pendingInstall = apkFile;
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+activity.getPackageName()));
            activity.startActivity(intent);
        });
    }

    private static void startInstall(Activity activity, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName()+".FileProvider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        }
        catch (Exception e) {
            AppUtils.showToast(activity, R.string.update_install_failed);
        }
    }
}
