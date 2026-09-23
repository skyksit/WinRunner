package com.winlator.speed;

import java.util.Locale;

/**
 * The speed steps and preference keys, kept identical to DGPlayer/dsam3 so a user who learns the
 * feature there finds the same numbers here. dsam3 stores these globally (its DataStore keys
 * {@code fast_forward_speed_x} / {@code slow_motion_speed}); this fork mirrors the names in the
 * default SharedPreferences.
 */
public final class GameSpeed {
    /** Fast forward multipliers, dsam3's list verbatim. */
    public static final float[] STEPS = {1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f};

    public static final String PREF_FAST_FORWARD_SPEED = "fast_forward_speed_x";
    public static final String PREF_SLOW_MOTION_SPEED = "slow_motion_speed";
    public static final String PREF_SHOW_TOAST = "show_toast_fast_slow";

    public static final float DEFAULT_FAST_FORWARD_SPEED = 5.0f;
    public static final float DEFAULT_SLOW_MOTION_SPEED = 0.5f;

    private GameSpeed() {}

    /** Slow motion factors are the reciprocals of the same list, as in dsam3. */
    public static float slowMotionFactor(int index) {
        return 1f / STEPS[index];
    }

    public static String[] fastForwardLabels() {
        String[] labels = new String[STEPS.length];
        for (int i = 0; i < STEPS.length; i++) labels[i] = formatMultiplier(STEPS[i]);
        return labels;
    }

    public static String[] slowMotionLabels() {
        String[] labels = new String[STEPS.length];
        for (int i = 0; i < STEPS.length; i++) labels[i] = "1/"+trim(STEPS[i]);
        return labels;
    }

    public static String formatMultiplier(float factor) {
        return trim(factor)+"x";
    }

    /** How a factor reads on the HUD: {@code 2x} when speeding up, {@code 1/2} when slowing down. */
    public static String formatFactor(float factor) {
        if (factor >= 1f) return formatMultiplier(factor);
        return "1/"+trim(1f / factor);
    }

    /** Index of the step nearest to {@code factor}, so a stored value always lands on a spinner row. */
    public static int nearestStep(float factor) {
        int nearest = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < STEPS.length; i++) {
            float distance = Math.abs(STEPS[i] - factor);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private static String trim(float value) {
        if (value == Math.rint(value)) return String.valueOf((int)value);
        return String.format(Locale.ENGLISH, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
