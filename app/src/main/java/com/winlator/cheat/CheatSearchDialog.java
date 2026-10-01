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
                row.setOnClickListener(v -> editValue(pid, address, size, value, false));
                llResults.addView(row);
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
            TextView label = createRow(text);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            label.setOnClickListener(v -> editValue(cheat.pid, cheat.address, cheat.size, cheat.value, cheat.frozen));
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

    private void editValue(int pid, long address, int size, long current, boolean frozen) {
        ContentDialog dialog = new ContentDialog(getContext(), R.layout.cheat_value_dialog);
        dialog.setTitle(String.format(Locale.ROOT, "0x%08X", address));
        EditText etCheatValue = dialog.findViewById(R.id.ETCheatValue);
        CheckBox cbFreeze = dialog.findViewById(R.id.CBFreeze);
        etCheatValue.setText(MemoryScanner.format(size, current));
        cbFreeze.setChecked(frozen);

        dialog.setOnConfirmCallback(() -> {
            long value;
            try {
                value = Long.parseLong(etCheatValue.getText().toString().trim());
            }
            catch (NumberFormatException e) {
                AppUtils.showToast(getContext(), R.string.cheat_enter_value);
                return;
            }
            session.setValue(pid, address, size, value, cbFreeze.isChecked(), error -> {
                if (error != null) AppUtils.showToast(getContext(), getContext().getString(R.string.cheat_write_failed, error));
                refreshCheats();
                showResults();
            });
        });
        dialog.show();
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
