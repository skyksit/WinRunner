package com.winlator.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.core.AppUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;

/**
 * Picks files from a game tool's library ({@code toolLibrary=}, see GameTools) before the tool
 * starts: browse the folders or search every name, tick up to {@link GameTools#MAX_PICKS}.
 *
 * <p>Android-side on purpose. The tool's own file dialog runs in Wine, where a two-thousand file
 * tree is a long way to drag a touch cursor - the picked files are instead placed where that dialog
 * opens, so it shows only them.
 */
public final class ToolLibraryDialog {
    public interface Callback {
        /** On the main thread. Empty means "open without items". */
        void onPicked(List<File> picked);
    }

    private final Activity activity;
    private final File root;
    private final Callback callback;
    private List<GameTools.LibraryItem> items = new ArrayList<>();
    /** Ticked items, in the order they were ticked. */
    private final Set<GameTools.LibraryItem> picked = new LinkedHashSet<>();
    private String folder = "";
    private String query = "";
    private final List<Object> rows = new ArrayList<>();
    private RowAdapter adapter;
    private TextView pathView;
    private AlertDialog dialog;
    /** The dialog's themed context, so rows are styled like the dialog they sit on. */
    private Context ui;

    /**
     * A dark alert dialog. AppThemeDark sits on Theme.AppCompat.Light, so a plain AlertDialog gets a
     * light background while the app's TextView style paints text #fafafa - white on white, title
     * included. The dark dialog theme puts that same text on a dark background.
     */
    public static AlertDialog.Builder builder(Activity activity) {
        return new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    public ToolLibraryDialog(Activity activity, File root, Callback callback) {
        this.activity = activity;
        this.root = root;
        this.callback = callback;
    }

    public void show(String title) {
        AlertDialog.Builder builder = builder(activity);
        ui = builder.getContext();
        LinearLayout content = new LinearLayout(ui);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        content.setPadding(pad, pad / 2, pad, 0);

        EditText search = new EditText(ui);
        search.setHint(R.string.game_tool_library_search);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                refresh();
            }
        });
        content.addView(search);

        pathView = new TextView(ui);
        pathView.setPadding(0, dp(6), 0, dp(6));
        pathView.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(pathView);

        ListView list = new ListView(ui);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClicked(rows.get(position)));
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        dialog = builder
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.game_tool_open, null)
            .setNeutralButton(R.string.game_tool_library_skip, (d, w) -> callback.onPicked(new ArrayList<>()))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<File> files = new ArrayList<>();
            for (GameTools.LibraryItem item : picked) files.add(item.file);
            dialog.dismiss();
            callback.onPicked(files);
        }));
        dialog.show();
        refresh();

        Executors.newSingleThreadExecutor().execute(() -> {
            List<GameTools.LibraryItem> scanned = GameTools.scanLibrary(root);
            activity.runOnUiThread(() -> {
                items = scanned;
                refresh();
            });
        });
    }

    private void onRowClicked(Object row) {
        if (row instanceof String) {
            String name = (String) row;
            if (name.equals("..")) {
                int slash = folder.lastIndexOf('/');
                folder = slash < 0 ? "" : folder.substring(0, slash);
            }
            else folder = folder.isEmpty() ? name : folder+"/"+name;
            refresh();
            return;
        }
        GameTools.LibraryItem item = (GameTools.LibraryItem) row;
        if (!picked.remove(item)) {
            if (picked.size() >= GameTools.MAX_PICKS) {
                AppUtils.showToast(activity, activity.getString(R.string.game_tool_library_limit, GameTools.MAX_PICKS));
                return;
            }
            picked.add(item);
        }
        adapter.notifyDataSetChanged();
        updateButton();
    }

    /** Rebuilds the rows: search hits across the library, or the current folder's subfolders and files. */
    private void refresh() {
        rows.clear();
        if (!query.isEmpty()) {
            String needle = query.toLowerCase(Locale.ROOT);
            for (GameTools.LibraryItem item : items) {
                if (item.name().toLowerCase(Locale.ROOT).contains(needle)) rows.add(item);
            }
            pathView.setText(activity.getString(R.string.game_tool_library_results, rows.size()));
        }
        else {
            if (!folder.isEmpty()) rows.add("..");
            String prefix = folder.isEmpty() ? "" : folder+"/";
            Set<String> subfolders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            List<GameTools.LibraryItem> files = new ArrayList<>();
            for (GameTools.LibraryItem item : items) {
                if (!item.relativePath.startsWith(prefix)) continue;
                String rest = item.relativePath.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0) subfolders.add(rest.substring(0, slash));
                else files.add(item);
            }
            rows.addAll(subfolders);
            rows.addAll(files);
            pathView.setText("/"+folder);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateButton();
    }

    private void updateButton() {
        if (dialog == null || dialog.getButton(AlertDialog.BUTTON_POSITIVE) == null) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(picked.isEmpty()
                ? activity.getString(R.string.game_tool_open)
                : activity.getString(R.string.game_tool_library_open_with, picked.size()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!picked.isEmpty());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : newRow();
            CheckBox check = (CheckBox) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            TextView title = (TextView) texts.getChildAt(0);
            TextView subtitle = (TextView) texts.getChildAt(1);

            Object item = rows.get(position);
            if (item instanceof String) {
                String name = (String) item;
                check.setVisibility(View.GONE);
                title.setText(name.equals("..") ? "⬆ .." : "📁 "+name);
                subtitle.setVisibility(View.GONE);
            }
            else {
                GameTools.LibraryItem libraryItem = (GameTools.LibraryItem) item;
                check.setVisibility(View.VISIBLE);
                check.setChecked(picked.contains(libraryItem));
                title.setText(libraryItem.name());
                // The folder tells a search hit's category apart; while browsing it is the header.
                subtitle.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                subtitle.setText(libraryItem.folder());
            }
            return row;
        }

        private LinearLayout newRow() {
            LinearLayout row = new LinearLayout(ui);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            CheckBox check = new CheckBox(ui);
            // The row handles the tap; a focusable checkbox would swallow it.
            check.setClickable(false);
            check.setFocusable(false);
            row.addView(check);
            LinearLayout texts = new LinearLayout(ui);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(ui);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            TextView subtitle = new TextView(ui);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            subtitle.setAlpha(0.7f);
            texts.addView(title);
            texts.addView(subtitle);
            row.addView(texts);
            return row;
        }
    }
}
