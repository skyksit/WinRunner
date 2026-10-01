package com.winlator.cheat;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.ProcessHelper;

import java.util.List;
import java.util.Locale;

/**
 * Drawer entry for {@link CheatSession}: find a value by searching for it, narrowing each time it
 * changes in the game, then set or hold it. The same flow as DGPlayer's in-game memory search.
 */
public class CheatSearchDialog extends ContentDialog {
    /** Candidates are listed once the search is down to this many (same limit as DGPlayer). */
    private static final int MAX_LISTED = 100;
    private static final int[] VALUE_SIZES = {4, 2, 1};

    private final CheatSession session;
    private final Spinner sProcess;
    private final Spinner sValueSize;
    private final EditText etValue;
    private final TextView tvStatus;
    private final LinearLayout llResults;
    private final LinearLayout llCheats;
    private final TextView tvCheatsTitle;
    private final LinearLayout llSaved;
    private final TextView tvSavedTitle;
    private final android.widget.Button btSaveAll;
    private final View btStartSearch;
    private final View btExact;
    private final View llCompare;
    private final View btNewSearch;
    private final View[] searchButtons;
    private List<ProcessHelper.PStat> processes;
    private boolean busy;

    public CheatSearchDialog(Context context, CheatSession session) {
        super(context, R.layout.cheat_search_dialog);
        this.session = session;
        setTitle(R.string.cheat_search);
        setIcon(R.drawable.icon_cheat);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        sProcess = findViewById(R.id.SProcess);
        sValueSize = findViewById(R.id.SValueSize);
        etValue = findViewById(R.id.ETValue);
        tvStatus = findViewById(R.id.TVStatus);
        llResults = findViewById(R.id.LLResults);
        llCheats = findViewById(R.id.LLCheats);
        tvCheatsTitle = findViewById(R.id.TVCheatsTitle);
        llSaved = findViewById(R.id.LLSaved);
        tvSavedTitle = findViewById(R.id.TVSavedTitle);
        btSaveAll = findViewById(R.id.BTSaveAll);

        sValueSize.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, new String[]{
            context.getString(R.string.cheat_size_4),
            context.getString(R.string.cheat_size_2),
            context.getString(R.string.cheat_size_1)
        }));

        btStartSearch = findViewById(R.id.BTStartSearch);
        btExact = findViewById(R.id.BTExact);
        llCompare = findViewById(R.id.LLCompare);
        btNewSearch = findViewById(R.id.BTNewSearch);
        View btIncreased = findViewById(R.id.BTIncreased);
        View btDecreased = findViewById(R.id.BTDecreased);
        View btUnchanged = findViewById(R.id.BTUnchanged);
        View btChanged = findViewById(R.id.BTChanged);
        searchButtons = new View[]{btStartSearch, btExact, btIncreased, btDecreased, btUnchanged, btChanged, btNewSearch};

        btStartSearch.setOnClickListener(v -> startSearch());
        btNewSearch.setOnClickListener(v -> resetSearch());
        btExact.setOnClickListener(v -> {
            Long value = parseValue();
            if (value != null) nextSearch(MemoryScanner.Compare.EXACT, value);
        });
        btIncreased.setOnClickListener(v -> nextSearch(MemoryScanner.Compare.INCREASED, 0));
        btDecreased.setOnClickListener(v -> nextSearch(MemoryScanner.Compare.DECREASED, 0));
        btUnchanged.setOnClickListener(v -> nextSearch(MemoryScanner.Compare.UNCHANGED, 0));
        btChanged.setOnClickListener(v -> nextSearch(MemoryScanner.Compare.CHANGED, 0));

        loadProcesses();
        restoreState();
        refreshCheats();
        refreshSaved();
        // Saved cheats change status on their own (found once the game is loaded).
        session.setSavedListener(this::refreshSaved);
        setOnDismissListener(dialog -> session.setSavedListener(null));
    }

    private void loadProcesses() {
        processes = session.findGameProcesses();
        String[] names = new String[processes.size()];
        for (int i = 0; i < names.length; i++) names[i] = CheatSession.displayName(processes.get(i));
        sProcess.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, names));

        // Keep pointing at the process the search in progress belongs to.
        MemoryScanner scanner = session.getScanner();
        if (scanner != null) {
            for (int i = 0; i < processes.size(); i++) {
                if (processes.get(i).pid == scanner.getPid()) sProcess.setSelection(i);
            }
        }
    }

    private void restoreState() {
        MemoryScanner scanner = session.getScanner();
        if (scanner == null) {
            setStatus(getContext().getString(processes.isEmpty() ? R.string.cheat_no_process : R.string.cheat_start_hint));
            applyMode();
            return;
        }
        for (int i = 0; i < VALUE_SIZES.length; i++) {
            if (VALUE_SIZES[i] == scanner.getValueSize()) sValueSize.setSelection(i);
        }
        showResults();
        applyMode();
    }

    private Long parseValue() {
        String text = etValue.getText().toString().trim();
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException e) {
            AppUtils.showToast(getContext(), R.string.cheat_enter_value);
            return null;
        }
    }

    private void startSearch() {
        Long value = parseValue();
        if (value == null) return;
        int position = sProcess.getSelectedItemPosition();
        if (position < 0 || position >= processes.size()) {
            loadProcesses();
            setStatus(getContext().getString(R.string.cheat_no_process));
            return;
        }
        int pid = processes.get(position).pid;
        int size = VALUE_SIZES[Math.max(sValueSize.getSelectedItemPosition(), 0)];

        setBusy(true);
        session.firstScan(pid, size, value, error -> {
            setBusy(false);
            if (error != null) setStatus(getContext().getString(R.string.cheat_search_failed, error));
            else showResults();
            applyMode();
        });
    }

    /** Drops the candidates and goes back to the start screen. Held values are kept. */
    private void resetSearch() {
        setBusy(true);
        session.resetSearch(error -> {
            setBusy(false);
            llResults.removeAllViews();
            setStatus(getContext().getString(R.string.cheat_start_hint));
            applyMode();
            etValue.requestFocus();
        });
    }

    private void nextSearch(MemoryScanner.Compare compare, long operand) {
        if (session.getScanner() == null) {
            AppUtils.showToast(getContext(), R.string.cheat_start_hint);
            return;
        }
        setBusy(true);
        session.nextScan(compare, operand, error -> {
            setBusy(false);
            if (error != null) setStatus(getContext().getString(R.string.cheat_search_failed, error));
            else showResults();
        });
    }

    private void showResults() {
        llResults.removeAllViews();
        btSaveAll.setVisibility(View.GONE);
        MemoryScanner scanner = session.getScanner();
        if (scanner == null) return;

        int count = scanner.getCount();
        String status = getContext().getString(R.string.cheat_candidates, count);
        if (scanner.isTruncated()) status += " " + getContext().getString(R.string.cheat_truncated);
        if (count == 0) status = getContext().getString(R.string.cheat_no_match);
        else if (count > MAX_LISTED) status += "\n" + getContext().getString(R.string.cheat_narrow_down);
        setStatus(status);
        if (count == 0 || count > MAX_LISTED) return;

        final int size = scanner.getValueSize();
        final int pid = scanner.getPid();
        final long[] addresses = new long[count];
        for (int i = 0; i < count; i++) addresses[i] = scanner.getAddress(i);

        session.readValues(addresses, size, pid, (values, ok) -> {
            if (session.getScanner() != scanner) return;
            llResults.removeAllViews();
            for (int i = 0; i < addresses.length; i++) {
                final long address = addresses[i];
                final long value = values[i];
                String text = String.format(Locale.ROOT, "0x%08X  =  %s", address,
                        ok[i] ? MemoryScanner.format(size, value) : "?");
                TextView row = createRow(text);
                row.setOnClickListener(v -> editValue(pid, new long[]{address}, size, value, false));
                llResults.addView(row);
            }
            if (session.canSave() && addresses.length >= 2 && addresses.length <= CheatSession.MAX_SAVED_TARGETS) {
                btSaveAll.setText(getContext().getString(R.string.cheat_save_all, addresses.length));
                btSaveAll.setVisibility(View.VISIBLE);
                btSaveAll.setOnClickListener(v -> editValue(pid, addresses, size, values[0], false));
            }
        });
    }

    private void refreshCheats() {
        llCheats.removeAllViews();
        List<CheatSession.Cheat> cheats = session.getCheats();
        tvCheatsTitle.setVisibility(cheats.isEmpty() ? View.GONE : View.VISIBLE);
        for (CheatSession.Cheat cheat : cheats) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);

            String text = String.format(Locale.ROOT, "0x%08X  =  %s", cheat.address, MemoryScanner.format(cheat.size, cheat.value));
            if (cheat.failed) text += "  " + getContext().getString(R.string.cheat_lost);
            else if (cheat.displaced) text += "  " + getContext().getString(R.string.cheat_displaced);
            TextView label = createRow(text);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            label.setOnClickListener(v -> editValue(cheat.pid, new long[]{cheat.address}, cheat.size, cheat.value, cheat.frozen));
            row.addView(label);

            CheckBox freeze = new CheckBox(getContext());
            freeze.setText(R.string.cheat_freeze);
            freeze.setChecked(cheat.frozen);
            freeze.setOnCheckedChangeListener((button, checked) -> session.setFrozen(cheat, checked));
            row.addView(freeze);

            TextView remove = createRow("✕");
            remove.setOnClickListener(v -> {
                session.remove(cheat);
                refreshCheats();
            });
            row.addView(remove);

            llCheats.addView(row);
        }
    }

    /**
     * Sets the value at {@code addresses} (all of them together: some games keep a value in
     * several copies), optionally saving it to the game.
     */
    private void editValue(int pid, long[] addresses, int size, long current, boolean frozen) {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.cheat_value_dialog);
        dialog.setTitle(addresses.length == 1 ? String.format(Locale.ROOT, "0x%08X", addresses[0])
                : getContext().getString(R.string.cheat_save_all, addresses.length));
        EditText etCheatValue = dialog.findViewById(R.id.ETCheatValue);
        CheckBox cbFreeze = dialog.findViewById(R.id.CBFreeze);
        CheckBox cbSave = dialog.findViewById(R.id.CBSaveToGame);
        EditText etName = dialog.findViewById(R.id.ETCheatName);
        etCheatValue.setText(MemoryScanner.format(size, current));
        cbFreeze.setChecked(frozen);
        if (session.canSave()) {
            cbSave.setVisibility(View.VISIBLE);
            // Change-all-together is there to be saved.
            cbSave.setChecked(addresses.length > 1);
            etName.setVisibility(cbSave.isChecked() ? View.VISIBLE : View.GONE);
            cbSave.setOnCheckedChangeListener((button, checked) -> etName.setVisibility(checked ? View.VISIBLE : View.GONE));
        }

        dialog.setOnConfirmCallback(() -> {
            Long value = parseNumber(etCheatValue);
            if (value == null) return;
            if (cbSave.getVisibility() == View.VISIBLE && cbSave.isChecked()) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) name = String.format(Locale.ROOT, "0x%08X", addresses[0]);
                saveCheat(name, pid, addresses, size, value, cbFreeze.isChecked());
                return;
            }
            final int[] left = {addresses.length};
            for (long address : addresses) {
                session.setValue(pid, address, size, value, cbFreeze.isChecked(), error -> {
                    if (error != null) AppUtils.showToast(getContext(), getContext().getString(R.string.cheat_write_failed, error));
                    if (--left[0] > 0) return;
                    refreshCheats();
                    showResults();
                });
            }
        });
        dialog.show();
    }

    private Long parseNumber(EditText editText) {
        try {
            return Long.parseLong(editText.getText().toString().trim());
        }
        catch (NumberFormatException e) {
            AppUtils.showToast(getContext(), R.string.cheat_enter_value);
            return null;
        }
    }

    /** Runs the pointer scan behind a progress dialog that can cancel it. */
    private void saveCheat(String name, int pid, long[] addresses, int size, long value, boolean freeze) {
        ContentDialog progressDialog = new ContentDialog(getContext());
        progressDialog.setCancelable(false);
        progressDialog.setTitle(R.string.cheat_saved_title);
        progressDialog.setMessage(getContext().getString(R.string.cheat_saving, 0, new PointerScanner.Options().maxDepth));
        progressDialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        final boolean[] cancelled = {false};
        progressDialog.setOnCancelCallback(() -> cancelled[0] = true);
        progressDialog.show();

        PointerScanner.Progress progress = new PointerScanner.Progress() {
            @Override
            public void update(int done, int total) {
                tvStatus.post(() -> progressDialog.setMessage(getContext().getString(R.string.cheat_saving, done, total)));
            }

            @Override
            public boolean isCancelled() {
                return cancelled[0];
            }
        };
        session.saveCheat(name, pid, addresses, size, value, freeze, progress, (saved, sessionOnly, error) -> {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            if (cancelled[0]) return;
            if (error != null) AppUtils.showToast(getContext(), getContext().getString(R.string.cheat_save_failed, error));
            else if (saved == 0) AppUtils.showToast(getContext(), R.string.cheat_saved_none);
            else if (sessionOnly > 0) AppUtils.showToast(getContext(), getContext().getString(R.string.cheat_saved_partial, saved, sessionOnly));
            else AppUtils.showToast(getContext(), R.string.cheat_saved_result);
            refreshCheats();
            showResults();
            refreshSaved();
        });
    }

    private void refreshSaved() {
        llSaved.removeAllViews();
        List<SavedCheats.Cheat> saved = session.getSaved();
        tvSavedTitle.setVisibility(saved.isEmpty() ? View.GONE : View.VISIBLE);
        for (SavedCheats.Cheat cheat : saved) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);

            String text = cheat.name + "  =  " + MemoryScanner.format(cheat.size, cheat.value)
                    + "  (" + getContext().getString(statusText(session.statusOf(cheat))) + ")";
            TextView label = createRow(text);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            label.setOnClickListener(v -> editSaved(cheat));
            row.addView(label);

            CheckBox enabled = new CheckBox(getContext());
            enabled.setText(R.string.cheat_enabled);
            enabled.setChecked(cheat.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> session.updateSaved(cheat, cheat.name, cheat.value, cheat.freeze, checked));
            row.addView(enabled);

            CheckBox freeze = new CheckBox(getContext());
            freeze.setText(R.string.cheat_freeze);
            freeze.setChecked(cheat.freeze);
            freeze.setOnCheckedChangeListener((button, checked) -> session.updateSaved(cheat, cheat.name, cheat.value, checked, cheat.enabled));
            row.addView(freeze);

            TextView remove = createRow("✕");
            remove.setOnClickListener(v -> ContentDialog.confirm(getContext(), R.string.cheat_delete_saved, () -> session.deleteSaved(cheat)));
            row.addView(remove);

            llSaved.addView(row);
        }
    }

    private void editSaved(SavedCheats.Cheat cheat) {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.cheat_value_dialog);
        dialog.setTitle(cheat.name);
        EditText etCheatValue = dialog.findViewById(R.id.ETCheatValue);
        CheckBox cbFreeze = dialog.findViewById(R.id.CBFreeze);
        EditText etName = dialog.findViewById(R.id.ETCheatName);
        etCheatValue.setText(MemoryScanner.format(cheat.size, cheat.value));
        cbFreeze.setChecked(cheat.freeze);
        etName.setVisibility(View.VISIBLE);
        etName.setText(cheat.name);

        dialog.setOnConfirmCallback(() -> {
            Long value = parseNumber(etCheatValue);
            if (value == null) return;
            String name = etName.getText().toString().trim();
            session.updateSaved(cheat, name.isEmpty() ? cheat.name : name, value, cbFreeze.isChecked(), true);
        });
        dialog.show();
    }

    private static int statusText(CheatSession.Status status) {
        switch (status) {
            case OFF: return R.string.cheat_status_off;
            case ACTIVE: return R.string.cheat_status_active;
            case NOT_FOUND: return R.string.cheat_status_not_found;
            case OTHER_VERSION: return R.string.cheat_status_other_version;
            default: return R.string.cheat_status_waiting;
        }
    }

    private TextView createRow(String text) {
        TextView row = new TextView(getContext());
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int padding = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getContext().getResources().getDisplayMetrics());
        row.setPadding(padding, padding, padding, padding);
        TypedValue background = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
        row.setBackgroundResource(background.resourceId);
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) setStatus(getContext().getString(R.string.cheat_searching));
        updateButtons();
    }

    /**
     * Before a search only the start button is shown; once it runs, "= Value" takes its place
     * beside the input (the button pressed over and over) and starting over moves below the
     * narrowing buttons. Same split as DGPlayer's in-game memory search.
     */
    private void applyMode() {
        boolean started = session.getScanner() != null;
        btStartSearch.setVisibility(started ? View.GONE : View.VISIBLE);
        btExact.setVisibility(started ? View.VISIBLE : View.GONE);
        llCompare.setVisibility(started ? View.VISIBLE : View.GONE);
        // The process and value size belong to the search in progress.
        sProcess.setEnabled(!started);
        sValueSize.setEnabled(!started);
        updateButtons();
    }

    private void updateButtons() {
        for (View button : searchButtons) {
            button.setEnabled(!busy);
            button.setAlpha(busy ? 0.5f : 1.0f);
        }
    }
}
