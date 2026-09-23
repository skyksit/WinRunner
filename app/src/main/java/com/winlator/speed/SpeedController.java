package com.winlator.speed;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.winlator.R;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.xserver.extensions.PresentExtension;

/**
 * Single source of truth for the session's game speed.
 *
 * <p>One factor drives three layers, and all three are needed for the speed to actually hold:
 * <ul>
 * <li>{@link Timescale} - the clock the guest reads, which is what makes the game itself run faster
 *     or slower.</li>
 * <li>{@link ALSAClient} - the AudioTrack drain rate. Left alone, the blocking write in
 *     {@code writeDataToTrack} back-pressures the guest's audio thread and drags a fast forward
 *     straight back down to 1x.</li>
 * <li>{@link PresentExtension} - the synthesised vblank the GLX/wined3d path paces on.</li>
 * </ul>
 *
 * <p>Fast forward and slow motion are mutually exclusive, as in dsam3: they are the same knob.
 */
public class SpeedController {
    public enum Mode {NORMAL, FAST_FORWARD, SLOW_MOTION}

    public interface Listener {
        void onSpeedChanged(Mode mode, float factor);
    }

    private final Context context;
    private final SharedPreferences preferences;
    private Mode mode = Mode.NORMAL;
    private float fastForwardSpeed;
    private float slowMotionSpeed;
    private Listener listener;
    private Toast toast;

    public SpeedController(Context context, SharedPreferences preferences) {
        this.context = context;
        this.preferences = preferences;
        this.fastForwardSpeed = preferences.getFloat(GameSpeed.PREF_FAST_FORWARD_SPEED, GameSpeed.DEFAULT_FAST_FORWARD_SPEED);
        this.slowMotionSpeed = preferences.getFloat(GameSpeed.PREF_SLOW_MOTION_SPEED, GameSpeed.DEFAULT_SLOW_MOTION_SPEED);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Mode getMode() {
        return mode;
    }

    public float getFastForwardSpeed() {
        return fastForwardSpeed;
    }

    public float getSlowMotionSpeed() {
        return slowMotionSpeed;
    }

    public boolean isToastEnabled() {
        return preferences.getBoolean(GameSpeed.PREF_SHOW_TOAST, true);
    }

    public float currentFactor() {
        switch (mode) {
            case FAST_FORWARD:
                return fastForwardSpeed;
            case SLOW_MOTION:
                return slowMotionSpeed;
            default:
                return 1.0f;
        }
    }

    public void toggleFastForward() {
        setMode(mode == Mode.FAST_FORWARD ? Mode.NORMAL : Mode.FAST_FORWARD, true);
    }

    public void toggleSlowMotion() {
        setMode(mode == Mode.SLOW_MOTION ? Mode.NORMAL : Mode.SLOW_MOTION, true);
    }

    public void setMode(Mode mode, boolean showToast) {
        if (this.mode == mode) return;
        this.mode = mode;
        apply();
        if (showToast) showToast();
    }

    /** Persisted globally, like dsam3 - the same value shows up on the next game. */
    public void setSpeeds(float fastForwardSpeed, float slowMotionSpeed, boolean toastEnabled) {
        this.fastForwardSpeed = fastForwardSpeed;
        this.slowMotionSpeed = slowMotionSpeed;
        preferences.edit().putFloat(GameSpeed.PREF_FAST_FORWARD_SPEED, fastForwardSpeed)
                          .putFloat(GameSpeed.PREF_SLOW_MOTION_SPEED, slowMotionSpeed)
                          .putBoolean(GameSpeed.PREF_SHOW_TOAST, toastEnabled).apply();
        if (mode != Mode.NORMAL) apply();
    }

    /** Pushes the current factor down again - used once the guest environment has come up. */
    public void reapply() {
        apply();
    }

    public void reset() {
        mode = Mode.NORMAL;
        apply();
    }

    private void apply() {
        float factor = currentFactor();
        Timescale.setScale(factor);
        ALSAClient.setGlobalPlaybackSpeed(factor);
        PresentExtension.setTimeScale(factor);
        if (listener != null) listener.onSpeedChanged(mode, factor);
    }

    private void showToast() {
        if (!isToastEnabled()) return;
        if (toast != null) toast.cancel();

        String message;
        switch (mode) {
            case FAST_FORWARD:
                message = context.getString(R.string.fast_forward)+" "+GameSpeed.formatFactor(fastForwardSpeed);
                break;
            case SLOW_MOTION:
                message = context.getString(R.string.slow_motion)+" "+GameSpeed.formatFactor(slowMotionSpeed);
                break;
            default:
                message = context.getString(R.string.normal_speed);
                break;
        }

        toast = Toast.makeText(context, message, Toast.LENGTH_SHORT);
        toast.show();
    }
}
