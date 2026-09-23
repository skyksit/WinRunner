package com.winlator.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import com.winlator.BuildConfig;
import com.winlator.R;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Caller authentication shared by every exported bridge entry point.
 *
 * <p>{@link GameLaunchActivity}, {@link ControlsEditActivity} and {@link ControlsSyncActivity} are
 * {@code exported="true"} and none is guarded by an {@code android:permission}, so this is the only
 * thing standing between the bridge and an arbitrary app on the device. That matters: a caller can
 * hand over any archive and have its exe run under Wine, rewrite the container through
 * {@code manifest_ini}'s {@code reg=}/{@code copy=} lines, and overwrite or prune controls profiles.
 *
 * <p>Three tiers, checked in order by {@link #authorize}:
 * <ol>
 *   <li>DGPlayer builds are trusted outright: same signature as this APK, or one of
 *       {@link #TRUSTED_CALLER_CERTS_SHA256}.</li>
 *   <li>Any other app the user has already allowed. The grant is remembered per package <em>and</em>
 *       signing certificate, so a different app reinstalled under the same package name is asked
 *       again instead of inheriting it.</li>
 *   <li>Everyone else gets a consent dialog naming the app. Refusing is not remembered: the next
 *       call asks again, which is only ever triggered by that app, so it cannot be used to nag.</li>
 * </ol>
 * Clearing WinRunner's data forgets every grant (there is no per-app revoke UI yet).
 */
final class BridgeSecurity {

    private static final String TAG = "DGPlayerBridge";
    private static final String PREFS_NAME = "dgplayer_bridge_consent";

    private BridgeSecurity() {}

    /** Receives the outcome of {@link #authorize}, always on the main thread. */
    interface Callback {
        void onResult(boolean allowed);
    }

    /**
     * SHA-256 of the DGPlayer signing certificates accepted besides this APK's own, without asking.
     *
     * <ul>
     *   <li>The shared dev certificate (dsam3/debug.keystore). Debug DGPlayer builds are signed with
     *       it while this APK's release builds carry the skyksit release key, so a plain
     *       checkSignatures() would lock debug DGPlayer out of release WinRunner.</li>
     *   <li>The Google Play app signing certificate of com.skyksit.dsam3. Play re-signs the bundle
     *       with a key Google holds, so the Play build does not share the skyksit upload key with
     *       this APK and checkSignatures() fails for it as well ("Caller not authorized" on every
     *       Play install). Read from the installed Play build with apksigner, 2026-09-23.</li>
     * </ul>
     */
    private static final byte[][] TRUSTED_CALLER_CERTS_SHA256 = {
            { // dsam3/debug.keystore
                    (byte) 0xE6, (byte) 0x5F, (byte) 0x40, (byte) 0x32, (byte) 0xB0, (byte) 0x09, (byte) 0xDD, (byte) 0xB3,
                    (byte) 0x7E, (byte) 0xB5, (byte) 0x2F, (byte) 0xA3, (byte) 0xD0, (byte) 0xF8, (byte) 0x84, (byte) 0xCF,
                    (byte) 0x21, (byte) 0x37, (byte) 0xCD, (byte) 0x23, (byte) 0xAD, (byte) 0x43, (byte) 0xA6, (byte) 0xEF,
                    (byte) 0x08, (byte) 0x05, (byte) 0x85, (byte) 0x8B, (byte) 0x68, (byte) 0x1F, (byte) 0xBF, (byte) 0x9D
            },
            { // com.skyksit.dsam3 Google Play app signing key
                    (byte) 0x90, (byte) 0xAA, (byte) 0x20, (byte) 0xB7, (byte) 0xB5, (byte) 0x6B, (byte) 0x13, (byte) 0x6B,
                    (byte) 0x02, (byte) 0x04, (byte) 0xC3, (byte) 0xC7, (byte) 0x72, (byte) 0xC0, (byte) 0x24, (byte) 0x2E,
                    (byte) 0x60, (byte) 0x7D, (byte) 0xC7, (byte) 0x98, (byte) 0xD2, (byte) 0xBC, (byte) 0x13, (byte) 0x20,
                    (byte) 0x3E, (byte) 0xE3, (byte) 0x04, (byte) 0xBB, (byte) 0xE5, (byte) 0x49, (byte) 0x01, (byte) 0xB1
            }
    };

    /**
     * Decides whether the app that started {@code activity} may use the bridge, asking the user when
     * it is neither DGPlayer nor already allowed. The callback runs exactly once, synchronously when
     * no dialog is needed.
     *
     * <p>A null calling package means the caller used {@code startActivity} rather than
     * {@code startActivityForResult} (this is also what {@code adb shell am start} looks like). There
     * is no app to name in a dialog, so it is only tolerated in debug builds so the intent contract
     * stays testable from a shell.
     */
    static void authorize(Activity activity, Callback callback) {
        String callingPackage = activity.getCallingPackage();
        if (callingPackage == null) {
            callback.onResult(BuildConfig.DEBUG);
            return;
        }

        PackageManager packageManager = activity.getPackageManager();
        if (isDGPlayer(activity, packageManager, callingPackage)) {
            callback.onResult(true);
            return;
        }

        String fingerprint = signingFingerprint(packageManager, callingPackage);
        if (fingerprint == null) {
            // Could not read the caller's certificate, so a grant could not be tied to it either.
            Log.w(TAG, "no signing certificate readable for "+callingPackage);
            callback.onResult(false);
            return;
        }

        if (fingerprint.equals(prefs(activity).getString(callingPackage, null))) {
            callback.onResult(true);
            return;
        }

        askUser(activity, packageManager, callingPackage, fingerprint, callback);
    }

    private static boolean isDGPlayer(Activity activity, PackageManager packageManager, String callingPackage) {
        if (packageManager.checkSignatures(activity.getPackageName(), callingPackage)
                == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        // hasSigningCertificate() exists only from API 28; below that, same-signature is the only path.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        for (byte[] cert : TRUSTED_CALLER_CERTS_SHA256) {
            if (packageManager.hasSigningCertificate(
                    callingPackage, cert, PackageManager.CERT_INPUT_SHA256)) {
                return true;
            }
        }
        return false;
    }

    private static void askUser(Activity activity, PackageManager packageManager, String callingPackage,
                                String fingerprint, Callback callback) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            callback.onResult(false);
            return;
        }

        String label = callingPackage;
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(callingPackage, 0);
            CharSequence loaded = packageManager.getApplicationLabel(info);
            if (loaded != null && loaded.length() > 0) label = loaded.toString();
        }
        catch (PackageManager.NameNotFoundException ignored) {}

        // Guards against the dialog's buttons and its dismissal both reporting.
        final boolean[] answered = {false};
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dgp_consent_title, label))
                .setMessage(activity.getString(R.string.dgp_consent_message, label, callingPackage))
                .setPositiveButton(R.string.dgp_consent_allow, (dialog, which) -> {
                    answered[0] = true;
                    prefs(activity).edit().putString(callingPackage, fingerprint).apply();
                    Log.i(TAG, "bridge access allowed for "+callingPackage);
                    callback.onResult(true);
                })
                .setNegativeButton(R.string.dgp_consent_deny, (dialog, which) -> {
                    answered[0] = true;
                    Log.i(TAG, "bridge access refused for "+callingPackage);
                    callback.onResult(false);
                })
                .setOnDismissListener(dialog -> {
                    // A recreated activity asks again on its own; finishing from this old instance
                    // would share its token and close the new one.
                    if (activity.isChangingConfigurations()) return;
                    // Back button, outside tap, or the activity going away: treat as a refusal.
                    if (!answered[0]) {
                        answered[0] = true;
                        callback.onResult(false);
                    }
                })
                .show();
    }

    /**
     * SHA-256 of every certificate the package is currently signed with, sorted and comma-joined, or
     * null if it cannot be read. Using the current signers (not the rotation history) means a key
     * rotation asks again, which is the conservative direction.
     */
    @SuppressWarnings("deprecation")
    private static String signingFingerprint(PackageManager packageManager, String packageName) {
        Signature[] signatures;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = info.signingInfo;
                if (signingInfo == null) return null;
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
                // Without multiple signers the history ends with the current certificate.
                if (!signingInfo.hasMultipleSigners() && signatures != null && signatures.length > 0) {
                    signatures = new Signature[]{signatures[signatures.length - 1]};
                }
            }
            else {
                signatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
            }
        }
        catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        if (signatures == null || signatures.length == 0) return null;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String[] hexes = new String[signatures.length];
            for (int i = 0; i < signatures.length; i++) {
                byte[] hash = digest.digest(signatures[i].toByteArray());
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) hex.append(String.format("%02x", b));
                hexes[i] = hex.toString();
            }
            Arrays.sort(hexes);
            return String.join(",", hexes);
        }
        catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
