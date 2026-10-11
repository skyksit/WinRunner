package com.winlator.inputcontrols;

import android.os.Handler;
import android.os.Looper;

import com.winlator.widget.InputControlsView;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DGPlayer: plays one {@link MacroSequence} at a time through the same path an ordinary button uses
 * ({@link InputControlsView#handleInputEvent(Binding, boolean)}), so keys, mouse buttons and gamepad
 * buttons all work in a macro exactly as they do on a button.
 *
 * Between steps only the difference is sent - keys the next step shares stay held, the rest are
 * released, the new ones pressed - so "down, down+right, right" is one rolling motion rather than
 * three separate taps. Only one macro plays at a time: starting another releases whatever the
 * previous one still held before the new one presses anything.
 *
 * Everything runs on the main thread, where touch events (and therefore every other input this
 * widget sends) already arrive.
 */
public final class MacroPlayer {
    private final InputControlsView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<Binding> held = new LinkedHashSet<>();
    private MacroSequence macro;
    private int stepIndex;
    private final Runnable advance = this::advance;

    public MacroPlayer(InputControlsView view) {
        this.view = view;
    }

    public void play(MacroSequence macro) {
        cancel();
        this.macro = macro;
        stepIndex = 0;
        advance();
    }

    public boolean isPlaying() {
        return macro != null;
    }

    /** Stops the macro and releases everything it holds. Safe to call at any time, any number of times. */
    public void cancel() {
        handler.removeCallbacks(advance);
        macro = null;
        releaseHeld();
    }

    private void advance() {
        MacroSequence macro = this.macro;
        if (macro == null) return;

        List<Binding[]> steps = macro.getSteps();
        if (stepIndex >= steps.size()) {
            this.macro = null;
            releaseHeld();
            return;
        }

        LinkedHashSet<Binding> next = new LinkedHashSet<>(Arrays.asList(steps.get(stepIndex)));
        // Release before press: "left" then "right" must never put both down at once.
        for (Binding binding : held.toArray(new Binding[0])) {
            if (!next.contains(binding)) {
                held.remove(binding);
                view.handleInputEvent(binding, false);
            }
        }
        for (Binding binding : next) {
            if (held.add(binding)) view.handleInputEvent(binding, true);
        }

        long delay = scaledDelay(macro.getStepMs(stepIndex));
        stepIndex++;
        handler.postDelayed(advance, delay);
    }

    /**
     * The game sees time through the speed controller, so a step held 50 ms of wall time lasts only
     * 25 ms of game time in half-speed slow motion - possibly less than one frame. Hold steps for the
     * same game time instead. Fast forward shortens them, but never below the minimum.
     */
    private long scaledDelay(int stepMs) {
        float factor = view.getGameSpeedFactor();
        if (!(factor > 0f) || Float.isInfinite(factor)) factor = 1f;
        return Math.max(MacroSequence.MIN_HOLD_MS, Math.round(stepMs / factor));
    }

    private void releaseHeld() {
        if (held.isEmpty()) return;
        Binding[] toRelease = held.toArray(new Binding[0]);
        held.clear();
        for (Binding binding : toRelease) view.handleInputEvent(binding, false);
    }
}
