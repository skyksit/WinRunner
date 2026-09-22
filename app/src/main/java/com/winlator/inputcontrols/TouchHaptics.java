package com.winlator.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.winlator.math.Mathf;

/**
 * One-shot touch feedback for the on-screen controls.
 *
 * Deliberately separate from {@link GamepadVibration}: that one forwards continuous XInput rumble
 * coming back from the guest and keeps a two-motor on/off state machine, which is the wrong shape
 * for a tap. Here every press is an independent short effect.
 *
 * The effect is picked by what the device can actually do, in descending order of fidelity:
 * <ol>
 *     <li>amplitude control (LRA) — amplitude <i>and</i> duration scale with the strength;</li>
 *     <li>API 30+ haptic primitives — the primitive's own scale carries the strength;</li>
 *     <li>plain ERM motor — it only knows on/off, so duration alone simulates the strength.</li>
 * </ol>
 * Note that the system-wide vibration intensity setting is multiplied on top of all three and
 * cannot be bypassed, so a device set to "light" will still feel light at 100%.
 */
public class TouchHaptics {
    public static final int MODE_OFF = 0;
    public static final int MODE_PRESS = 1;
    public static final int MODE_PRESS_AND_RELEASE = 2;

    public static final int DEFAULT_MODE = MODE_PRESS;
    public static final float DEFAULT_STRENGTH = 0.6f;
    public static final float MIN_STRENGTH = 0.2f;

    public static final String PREF_MODE = "touch_vibration_mode";
    public static final String PREF_STRENGTH = "touch_vibration_strength";

    private final Vibrator vibrator;
    private int mode = DEFAULT_MODE;
    private float strength = DEFAULT_STRENGTH;
    private VibrationEffect pressEffect;
    private VibrationEffect releaseEffect;

    public TouchHaptics(Context context) {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager != null ? manager.getDefaultVibrator() : null;
        }
        else vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);

        this.vibrator = vibrator != null && vibrator.hasVibrator() ? vibrator : null;
    }

    public void loadFromPreferences(SharedPreferences preferences) {
        setMode(preferences.getInt(PREF_MODE, DEFAULT_MODE));
        setStrength(preferences.getFloat(PREF_STRENGTH, DEFAULT_STRENGTH));
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = Mathf.clamp(mode, MODE_OFF, MODE_PRESS_AND_RELEASE);
    }

    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        strength = Mathf.clamp(strength, MIN_STRENGTH, 1.0f);
        if (this.strength != strength) {
            this.strength = strength;
            pressEffect = null;
            releaseEffect = null;
        }
    }

    public void performPress() {
        if (vibrator == null || mode < MODE_PRESS) return;
        if (pressEffect == null) pressEffect = createEffect(true);
        vibrator.vibrate(pressEffect);
    }

    public void performRelease() {
        if (vibrator == null || mode != MODE_PRESS_AND_RELEASE) return;
        if (releaseEffect == null) releaseEffect = createEffect(false);
        vibrator.vibrate(releaseEffect);
    }

    private VibrationEffect createEffect(boolean press) {
        // The release tick is one notch below the press so a tap doesn't feel like two presses.
        float scale = Mathf.clamp(press ? strength : strength - MIN_STRENGTH, 0.05f, 1.0f);
        int duration = press ? 15 + Math.round(scale * 112) : 10 + Math.round(scale * 56);

        if (vibrator.hasAmplitudeControl()) {
            return VibrationEffect.createOneShot(duration, Mathf.clamp(Math.round(scale * 255), 1, 255));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int primitive = press ? VibrationEffect.Composition.PRIMITIVE_CLICK : VibrationEffect.Composition.PRIMITIVE_TICK;
            try {
                if (vibrator.areAllPrimitivesSupported(primitive)) {
                    return VibrationEffect.startComposition().addPrimitive(primitive, scale).compose();
                }
            }
            catch (Exception e) {}
        }

        return VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE);
    }
}
