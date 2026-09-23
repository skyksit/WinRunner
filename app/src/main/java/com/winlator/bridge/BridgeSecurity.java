package com.winlator.bridge;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import com.winlator.BuildConfig;

/**
 * Caller authentication shared by every exported bridge entry point.
 *
 * <p>Both {@link GameLaunchActivity} and {@link ControlsEditActivity} are {@code exported="true"} and
 * neither is guarded by an {@code android:permission}, so this is the only thing standing between the
 * bridge and an arbitrary app on the device.
 */
final class BridgeSecurity {

    private BridgeSecurity() {}

    /**
     * SHA-256 of the DGPlayer signing certificates accepted besides this APK's own. Pinning exactly
     * these keeps every real DGPlayer build working without opening the bridge to arbitrary callers.
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
     * Rejects callers that are not signed with our certificate. A null calling package means the
     * caller used {@code startActivity} rather than {@code startActivityForResult} (this is also what
     * {@code adb shell am start} looks like), which is only tolerated in debug builds so the intent
     * contract stays testable from a shell.
     */
    static boolean isCallerTrusted(Activity activity) {
        String callingPackage = activity.getCallingPackage();
        if (callingPackage == null) return BuildConfig.DEBUG;

        PackageManager packageManager = activity.getPackageManager();
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
}
