package com.winlator.contentdialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.core.Callback;
import com.winlator.core.UnitUtils;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.MacroSequence;
import com.winlator.widget.SeekBar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DGPlayer: builds a macro button's sequence - the counterpart of DGPlayer's VPAD macro sheet, so a
 * layout reads the same in both editors. Tap a key to add it as the next step, long-press (or tick
 * "Combine with previous") to add it to the last step instead, tap a step to remove it.
 *
 * The callback receives the new macro, or null when the user removed it.
 */
public class MacroEditorDialog extends ContentDialog {
    private static final int COLOR_DIRECTION = 0xff553388;
    private static final int COLOR_KEY = 0xff335533;
    private static final int COLOR_WAIT = 0xff555555;
    private static final int COLOR_STEP = 0xff2d5a8e;

    /** Arrow, then the keyboard and gamepad keys it stands for. Order: ↖ ↑ ↗ ← → ↙ ↓ ↘. */
    private static final Object[][] DIRECTIONS = {
        {"↖", new Binding[]{Binding.KEY_UP, Binding.KEY_LEFT}, new Binding[]{Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_LEFT}},
        {"↑", new Binding[]{Binding.KEY_UP}, new Binding[]{Binding.GAMEPAD_DPAD_UP}},
        {"↗", new Binding[]{Binding.KEY_UP, Binding.KEY_RIGHT}, new Binding[]{Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_RIGHT}},
        {"←", new Binding[]{Binding.KEY_LEFT}, new Binding[]{Binding.GAMEPAD_DPAD_LEFT}},
        {"→", new Binding[]{Binding.KEY_RIGHT}, new Binding[]{Binding.GAMEPAD_DPAD_RIGHT}},
        {"↙", new Binding[]{Binding.KEY_DOWN, Binding.KEY_LEFT}, new Binding[]{Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_LEFT}},
        {"↓", new Binding[]{Binding.KEY_DOWN}, new Binding[]{Binding.GAMEPAD_DPAD_DOWN}},
        {"↘", new Binding[]{Binding.KEY_DOWN, Binding.KEY_RIGHT}, new Binding[]{Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_RIGHT}},
    };

    /** One-tap keys for the usual attack/jump buttons; everything else is in the spinners below. */
    private static final Binding[] KEYBOARD_QUICK_KEYS = {
        Binding.KEY_Z, Binding.KEY_X, Binding.KEY_C, Binding.KEY_A, Binding.KEY_S, Binding.KEY_D,
        Binding.KEY_Q, Binding.KEY_W, Binding.KEY_E, Binding.KEY_SPACE, Binding.KEY_ENTER,
        Binding.KEY_CTRL_L, Binding.KEY_SHIFT_L, Binding.KEY_ALT_L, Binding.MOUSE_LEFT_BUTTON, Binding.MOUSE_RIGHT_BUTTON,
    };
    private static final Binding[] GAMEPAD_QUICK_KEYS = {
        Binding.GAMEPAD_BUTTON_A, Binding.GAMEPAD_BUTTON_B, Binding.GAMEPAD_BUTTON_X, Binding.GAMEPAD_BUTTON_Y,
        Binding.GAMEPAD_BUTTON_L1, Binding.GAMEPAD_BUTTON_R1, Binding.GAMEPAD_BUTTON_L2, Binding.GAMEPAD_BUTTON_R2,
        Binding.GAMEPAD_BUTTON_START, Binding.GAMEPAD_BUTTON_SELECT,
    };

    private final List<Binding[]> steps = new ArrayList<>();
    private int[] stepTimes;
    private int holdMs;
    private boolean gamepadDirections;

    private final LinearLayout llSteps;
    private final TextView tvEmpty;
    private final CheckBox cbMerge;
    private final CheckBox cbStepTimes;
    private final LinearLayout llDirections;
    private final LinearLayout llQuickKeys;

    public MacroEditorDialog(Context context, MacroSequence initial, Callback<MacroSequence> callback) {
        super(context, R.layout.macro_editor_dialog);
        setTitle(R.string.macro_title);

        View view = getContentView();
        llSteps = view.findViewById(R.id.LLSteps);
        tvEmpty = view.findViewById(R.id.TVEmpty);
        cbMerge = view.findViewById(R.id.CBMerge);
        cbStepTimes = view.findViewById(R.id.CBStepTimes);
        llDirections = view.findViewById(R.id.LLDirections);
        llQuickKeys = view.findViewById(R.id.LLQuickKeys);

        holdMs = MacroSequence.DEFAULT_HOLD_MS;
        if (initial != null) {
            steps.addAll(initial.getSteps());
            holdMs = initial.getHoldMs();
            stepTimes = initial.getStepTimes();
            gamepadDirections = usesGamepadDirections(initial);
        }

        // Recorded per-step timing (made in DGPlayer) only stays while the steps are untouched:
        // after an edit there is no time for the new step, so the uniform hold takes over.
        cbStepTimes.setVisibility(stepTimes != null ? View.VISIBLE : View.GONE);
        cbStepTimes.setChecked(stepTimes != null);

        final TextView tvHold = view.findViewById(R.id.TVHold);
        tvHold.setText(context.getString(R.string.macro_hold, holdMs));
        SeekBar sbHold = view.findViewById(R.id.SBHold);
        sbHold.setValue(holdMs);
        sbHold.setOnValueChangeListener((seekBar, value) -> {
            holdMs = Math.round(value);
            tvHold.setText(context.getString(R.string.macro_hold, holdMs));
        });

        RadioGroup rgDirectionSource = view.findViewById(R.id.RGDirectionSource);
        rgDirectionSource.check(gamepadDirections ? R.id.RBGamepad : R.id.RBKeyboard);
        rgDirectionSource.setOnCheckedChangeListener((group, checkedId) -> {
            gamepadDirections = checkedId == R.id.RBGamepad;
            loadPalette();
        });

        setupKeySpinners(view);

        View btRemove = view.findViewById(R.id.BTRemoveMacro);
        if (initial != null) {
            btRemove.setVisibility(View.VISIBLE);
            btRemove.setOnClickListener((v) -> {
                callback.call(null);
                dismiss();
            });
        }

        loadPalette();
        refreshSteps();

        setOnConfirmCallback(() -> {
            List<Binding[]> trimmed = trimmedSteps();
            // Trimming shifts the steps against their recorded times, so keep the times only when nothing was cut.
            int[] times = cbStepTimes.isChecked() && stepTimes != null && trimmed.size() == steps.size() ? stepTimes : null;
            MacroSequence macro = new MacroSequence(trimmed, holdMs, times);
            callback.call(macro.hasKeys() ? macro : null);
        });
    }

    /** Leading and trailing pauses do nothing but delay or prolong the macro. */
    private List<Binding[]> trimmedSteps() {
        int start = 0;
        int end = steps.size();
        while (start < end && steps.get(start).length == 0) start++;
        while (end > start && steps.get(end - 1).length == 0) end--;
        return new ArrayList<>(steps.subList(start, end));
    }

    private static boolean usesGamepadDirections(MacroSequence macro) {
        for (Binding[] step : macro.getSteps()) {
            for (Binding binding : step) {
                if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_DOWN
                        || binding == Binding.GAMEPAD_DPAD_LEFT || binding == Binding.GAMEPAD_DPAD_RIGHT) return true;
                if (binding == Binding.KEY_UP || binding == Binding.KEY_DOWN
                        || binding == Binding.KEY_LEFT || binding == Binding.KEY_RIGHT) return false;
            }
        }
        return false;
    }

    /**
     * Adds one step, or merges into the last one. A pause is never merged into, and an empty list
     * (the Wait chip) always makes a new step.
     */
    private void addStep(Binding[] keys, boolean mergeIntoLast) {
        if (keys.length > 0 && mergeIntoLast && !steps.isEmpty() && steps.get(steps.size() - 1).length > 0) {
            LinkedHashSet<Binding> merged = new LinkedHashSet<>(Arrays.asList(steps.get(steps.size() - 1)));
            merged.addAll(Arrays.asList(keys));
            steps.set(steps.size() - 1, merged.toArray(new Binding[0]));
        }
        else {
            if (steps.size() >= MacroSequence.MAX_STEPS) return;
            steps.add(keys.clone());
        }
        onStepsEdited();
    }

    private void removeStep(int index) {
        if (index < 0 || index >= steps.size()) return;
        steps.remove(index);
        onStepsEdited();
    }

    private void onStepsEdited() {
        if (stepTimes != null) {
            stepTimes = null;
            cbStepTimes.setChecked(false);
            cbStepTimes.setVisibility(View.GONE);
        }
        refreshSteps();
    }

    private void refreshSteps() {
        llSteps.removeAllViews();
        tvEmpty.setVisibility(steps.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                TextView arrow = new TextView(getContext());
                arrow.setText("→");
                arrow.setAlpha(0.6f);
                arrow.setPadding(dp(2), 0, dp(2), 0);
                llSteps.addView(arrow);
            }
            Binding[] step = steps.get(i);
            final int index = i;
            TextView chip = chip(MacroSequence.stepLabel(step) + "  ✕", step.length == 0 ? COLOR_WAIT : COLOR_STEP);
            chip.setOnClickListener((v) -> removeStep(index));
            llSteps.addView(chip);
        }

        HorizontalScrollView scrollView = getContentView().findViewById(R.id.HSVSteps);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_RIGHT));
    }

    private void loadPalette() {
        llDirections.removeAllViews();
        for (Object[] direction : DIRECTIONS) {
            final Binding[] keys = (Binding[])direction[gamepadDirections ? 2 : 1];
            addPaletteChip(llDirections, (String)direction[0], COLOR_DIRECTION, keys);
        }
        TextView wait = chip(getContext().getString(R.string.macro_wait), COLOR_WAIT);
        wait.setOnClickListener((v) -> addStep(new Binding[0], false));
        llDirections.addView(wait);

        llQuickKeys.removeAllViews();
        for (Binding binding : gamepadDirections ? GAMEPAD_QUICK_KEYS : KEYBOARD_QUICK_KEYS) {
            addPaletteChip(llQuickKeys, MacroSequence.stepLabel(new Binding[]{binding}), COLOR_KEY, new Binding[]{binding});
        }
    }

    private void addPaletteChip(LinearLayout parent, String label, int color, final Binding[] keys) {
        TextView chip = chip(label, color);
        chip.setOnClickListener((v) -> addStep(keys, cbMerge.isChecked()));
        chip.setOnLongClickListener((v) -> {
            addStep(keys, true);
            return true;
        });
        parent.addView(chip);
    }

    private void setupKeySpinners(View view) {
        final Spinner sKeyType = view.findViewById(R.id.SKeyType);
        final Spinner sKey = view.findViewById(R.id.SKey);
        final Context context = getContext();
        final Binding[][] values = {playable(Binding.keyboardBindingValues()), playable(Binding.mouseBindingValues()), playable(Binding.gamepadBindingValues())};

        sKeyType.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, new String[]{
            context.getString(R.string.keyboard), context.getString(R.string.mouse), context.getString(R.string.gamepad)
        }));
        sKeyType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                Binding[] list = values[position];
                String[] labels = new String[list.length];
                for (int i = 0; i < list.length; i++) labels[i] = list[i].toString();
                sKey.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        sKeyType.setSelection(gamepadDirections ? 2 : 0, false);

        View btAdd = view.findViewById(R.id.BTAddKey);
        btAdd.setOnClickListener((v) -> addSelectedKey(values, sKeyType, sKey, cbMerge.isChecked()));
        btAdd.setOnLongClickListener((v) -> {
            addSelectedKey(values, sKeyType, sKey, true);
            return true;
        });
    }

    private void addSelectedKey(Binding[][] values, Spinner sKeyType, Spinner sKey, boolean merge) {
        Binding[] list = values[Math.max(0, sKeyType.getSelectedItemPosition())];
        int position = sKey.getSelectedItemPosition();
        if (position < 0 || position >= list.length) return;
        addStep(new Binding[]{list[position]}, merge);
    }

    /** NONE, host commands and volume keys have no place in a sequence (see MacroSequence.isPlayable). */
    private static Binding[] playable(Binding[] bindings) {
        List<Binding> result = new ArrayList<>();
        for (Binding binding : bindings) if (MacroSequence.isPlayable(binding)) result.add(binding);
        return result.toArray(new Binding[0]);
    }

    private TextView chip(String text, int color) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(40));
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(14));
        chip.setBackground(background);
        chip.setClickable(true);
        chip.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private int dp(float value) {
        return (int)UnitUtils.dpToPx(value);
    }
}
