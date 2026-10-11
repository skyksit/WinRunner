package com.winlator.inputcontrols;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DGPlayer: a button that plays its keys one step after another (a fighting game's down, down-right,
 * right, punch) instead of pressing them all at once. Mirrors DGPlayer's VPAD MACRO dial so the same
 * layout means the same thing on both sides.
 *
 * Stored on a BUTTON element as {@code sequence} (one array of Binding names per step, an empty array
 * is a pause), {@code seqHold} (ms per step, omitted at the default) and optionally {@code seqTimes}
 * (ms for each step, same length as the sequence). The element's own bindings stay NONE, so a build
 * that predates macros loads it as a button that does nothing rather than one that fires every key
 * at once - DGPlayer also strips such elements before sending a layout to an older build.
 */
public final class MacroSequence {
    public static final int DEFAULT_HOLD_MS = 50;
    /** Two frames at 60 Hz: a step shorter than this may never be seen by a game polling once a frame. */
    public static final int MIN_HOLD_MS = 33;
    public static final int MAX_HOLD_MS = 300;
    /** Per-step times may be longer than the uniform hold - charge moves hold a direction for a while. */
    public static final int MAX_STEP_MS = 2000;
    public static final int MAX_STEPS = 32;

    private final List<Binding[]> steps;
    private final int holdMs;
    private final int[] stepTimes;

    public MacroSequence(List<Binding[]> steps, int holdMs, int[] stepTimes) {
        List<Binding[]> copy = new ArrayList<>();
        for (Binding[] step : steps) {
            if (copy.size() >= MAX_STEPS) break;
            copy.add(step.clone());
        }
        this.steps = Collections.unmodifiableList(copy);
        this.holdMs = clamp(holdMs, MIN_HOLD_MS, MAX_HOLD_MS);
        if (stepTimes != null && stepTimes.length == copy.size()) {
            int[] times = new int[stepTimes.length];
            for (int i = 0; i < times.length; i++) times[i] = clamp(stepTimes[i], MIN_HOLD_MS, MAX_STEP_MS);
            this.stepTimes = times;
        }
        else this.stepTimes = null;
    }

    public List<Binding[]> getSteps() {
        return steps;
    }

    public int getHoldMs() {
        return holdMs;
    }

    /** Null when every step uses {@link #getHoldMs()}. */
    public int[] getStepTimes() {
        return stepTimes != null ? stepTimes.clone() : null;
    }

    public int getStepMs(int index) {
        return stepTimes != null ? stepTimes[index] : holdMs;
    }

    /** A macro with nothing to press is not worth keeping - the button would look alive and do nothing. */
    public boolean hasKeys() {
        for (Binding[] step : steps) if (step.length > 0) return true;
        return false;
    }

    public void writeTo(JSONObject element) throws JSONException {
        JSONArray sequence = new JSONArray();
        for (Binding[] step : steps) {
            JSONArray names = new JSONArray();
            for (Binding binding : step) names.put(binding.name());
            sequence.put(names);
        }
        element.put("sequence", sequence);
        if (holdMs != DEFAULT_HOLD_MS) element.put("seqHold", holdMs);
        if (stepTimes != null) {
            JSONArray times = new JSONArray();
            for (int t : stepTimes) times.put(t);
            element.put("seqTimes", times);
        }
    }

    /**
     * Reads the macro fields of an element, or null when it has none. A key this build does not know
     * is dropped from its step rather than failing the element: the rest of the macro still plays.
     */
    public static MacroSequence fromJSON(JSONObject element) {
        JSONArray sequence = element.optJSONArray("sequence");
        if (sequence == null || sequence.length() == 0) return null;

        List<Binding[]> steps = new ArrayList<>();
        for (int i = 0; i < sequence.length(); i++) {
            JSONArray names = sequence.optJSONArray(i);
            LinkedHashSet<Binding> step = new LinkedHashSet<>();
            if (names != null) {
                for (int j = 0; j < names.length(); j++) {
                    try {
                        Binding binding = Binding.fromString(names.getString(j));
                        if (isPlayable(binding)) step.add(binding);
                    }
                    catch (JSONException | IllegalArgumentException e) {
                        Log.w("DGPlayerBridge", "macro: dropping unknown key in step "+i+": "+e);
                    }
                }
            }
            steps.add(step.toArray(new Binding[0]));
        }

        int[] times = null;
        JSONArray timesJSON = element.optJSONArray("seqTimes");
        if (timesJSON != null && timesJSON.length() == steps.size()) {
            times = new int[timesJSON.length()];
            for (int i = 0; i < times.length; i++) times[i] = timesJSON.optInt(i, DEFAULT_HOLD_MS);
        }

        MacroSequence macro = new MacroSequence(steps, element.optInt("seqHold", DEFAULT_HOLD_MS), times);
        return macro.hasKeys() ? macro : null;
    }

    /**
     * Host commands (volume keys included) fire on release and open drawers or editors or change the
     * phone's volume - none of that belongs in the middle of a sequence of game inputs.
     */
    public static boolean isPlayable(Binding binding) {
        return binding != Binding.NONE && !binding.isCommand();
    }

    /** Short text for one step: arrows for directions (diagonals folded), names for the rest. */
    public static String stepLabel(Binding[] step) {
        if (step.length == 0) return "-";
        List<Binding> rest = new ArrayList<>(Arrays.asList(step));
        boolean up = rest.remove(Binding.KEY_UP) | rest.remove(Binding.GAMEPAD_DPAD_UP);
        boolean down = rest.remove(Binding.KEY_DOWN) | rest.remove(Binding.GAMEPAD_DPAD_DOWN);
        boolean left = rest.remove(Binding.KEY_LEFT) | rest.remove(Binding.GAMEPAD_DPAD_LEFT);
        boolean right = rest.remove(Binding.KEY_RIGHT) | rest.remove(Binding.GAMEPAD_DPAD_RIGHT);

        StringBuilder sb = new StringBuilder();
        String arrow = arrow(up, down, left, right);
        if (arrow != null) sb.append(arrow);
        else {
            // Opposite directions together: spell them out rather than pretend they cancel.
            if (up) sb.append("↑");
            if (down) sb.append("↓");
            if (left) sb.append("←");
            if (right) sb.append("→");
        }
        for (Binding binding : rest) {
            if (sb.length() > 0) sb.append("+");
            sb.append(shortName(binding));
        }
        return sb.toString();
    }

    /** Whole-macro text for a button with no custom label, e.g. "↓↘→Z". */
    public String label() {
        StringBuilder sb = new StringBuilder();
        for (Binding[] step : steps) {
            if (step.length == 0) continue;
            String part = stepLabel(step);
            if (sb.length() > 0 && part.length() > 1 && !isArrowOnly(part)) sb.append(" ");
            sb.append(part);
        }
        return sb.toString();
    }

    private static boolean isArrowOnly(String text) {
        for (int i = 0; i < text.length(); i++) if ("↑↓←→↖↗↙↘".indexOf(text.charAt(i)) < 0) return false;
        return true;
    }

    private static String arrow(boolean up, boolean down, boolean left, boolean right) {
        if ((up && down) || (left && right)) return null;
        if (up && left) return "↖";
        if (up && right) return "↗";
        if (down && left) return "↙";
        if (down && right) return "↘";
        if (up) return "↑";
        if (down) return "↓";
        if (left) return "←";
        if (right) return "→";
        return null;
    }

    private static String shortName(Binding binding) {
        return binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
