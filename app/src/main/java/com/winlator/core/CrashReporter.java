package com.winlator.core;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.winlator.BuildConfig;

/**
 * Firebase Crashlytics, for crashes and ANRs on users' devices (releases ship from GitHub, so
 * there is no Play Console to see them). Collection stays off in debug builds and when the user
 * turns off "Send crash reports" in Settings.
 *
 * <p>google-services.json is not in the repository; a build without it has no Firebase app, and
 * every call here does nothing.
 */
public final class CrashReporter {
    public static final String PREF_ENABLED = "send_crash_reports";
    public static final boolean DEFAULT_ENABLED = true;

    private CrashReporter() {}

    /** Applies the current setting. Call at startup and after the setting changes. */
    public static void apply(Context context) {
        if (!isAvailable(context)) return;
        boolean enabled = !BuildConfig.DEBUG &&
                PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.setCrashlyticsCollectionEnabled(enabled);
        if (!enabled) crashlytics.deleteUnsentReports();
    }

    private static boolean isAvailable(Context context) {
        return !FirebaseApp.getApps(context).isEmpty();
    }
}
