package com.winlator.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the game folders the bridge installed ({@code C:\DGPlayer\<gameId>}) and deletes the ones the
 * player picks. Opened from the fork's own menu and, through {@link #ACTION_MANAGE_STORAGE}, from
 * DGPlayer / Retrople — only this app can see the folders, so the screen has to live here.
 *
 * <p>Deleting a folder is safe because the caller can always reinstall it: the install marker lives
 * inside the folder, so the next launch re-extracts the zip ({@code payloadReinstalled}) and the save
 * restore runs in game-folder mode. What must hold for that to stay true:
 * <ul>
 *   <li>The whole folder goes, marker first. A marker without its files would read as installed and
 *       fail with "Executable not found".</li>
 *   <li>{@code dgplayer_saves/<gameId>.json} stays. Losing {@code lastStamp} turns the next restore
 *       into a full one that overwrites newer shared files; losing {@code tracked} breaks the
 *       {@code copy=} protection.</li>
 *   <li>A game whose session never exported must not be deleted as is: the next flush would export
 *       it without its folder and overwrite the archive with the shared files alone. Pending sessions
 *       are flushed when the screen opens; one still pending afterwards (its grant is gone) has its
 *       pending export discarded before deletion, so the last synced archive survives untouched.</li>
 *   <li>The game on screen in {@link XServerDisplayActivity} is never offered.</li>
 * </ul>
 *
 * <p>Result: {@code RESULT_OK} with {@link #EXTRA_FREED_BYTES} and {@link #EXTRA_DELETED_COUNT}, also
 * when nothing was deleted. Callers must use {@code startActivityForResult} ({@link BridgeSecurity}).
 */
public class StorageCleanupActivity extends AppCompatActivity {
    private static final String TAG = "DGPlayerBridge";

    // Must stay a literal mirroring AndroidManifest.xml (the manifest cannot reference BuildConfig).
    public static final String ACTION_MANAGE_STORAGE = "com.retrople.action.MANAGE_STORAGE";
    public static final String EXTRA_FREED_BYTES = "freed_bytes";
    public static final String EXTRA_DELETED_COUNT = "deleted_count";

    private enum Status { OK, RUNNING, UNSYNCED }

    private static class Entry {
        final String gameId;
        final String title;
        final File dir;
        final long size;
        final long lastPlayedAt;
        final Status status;
        boolean selected;

        Entry(String gameId, String title, File dir, long size, long lastPlayedAt, Status status) {
            this.gameId = gameId;
            this.title = title;
            this.dir = dir;
            this.size = size;
            this.lastPlayedAt = lastPlayedAt;
            this.status = status;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Entry> entries = new ArrayList<>();
    private final EntriesAdapter adapter = new EntriesAdapter();
    private PreloaderDialog preloaderDialog;
    private TextView tvSummary;
    private TextView tvEmptyText;
    private CheckBox cbSelectAll;
    private Button btDelete;
    private long freedBytes;
    private int deletedCount;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.storage_cleanup_activity);
        setTitle(R.string.dgp_storage_title);
        preloaderDialog = new PreloaderDialog(this);

        tvSummary = findViewById(R.id.TVSummary);
        tvEmptyText = findViewById(R.id.TVEmptyText);
        cbSelectAll = findViewById(R.id.CBSelectAll);
        btDelete = findViewById(R.id.BTDelete);
        RecyclerView recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setAdapter(adapter);

        cbSelectAll.setOnClickListener(v -> {
            boolean select = cbSelectAll.isChecked();
            for (Entry entry : entries) entry.selected = select && entry.status != Status.RUNNING;
            adapter.notifyDataSetChanged();
            updateSelection();
        });
        findViewById(R.id.BTClose).setOnClickListener(v -> finishWithResult());
        btDelete.setOnClickListener(v -> confirmDelete());

        // A consent dialog may come first, so the scan starts from the callback.
        BridgeSecurity.authorize(this, allowed -> {
            if (allowed) scan();
            else {
                Log.e(TAG, "storage screen: caller not authorized");
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    private void scan() {
        preloaderDialog.show(R.string.dgp_storage_scanning);
        executor.execute(() -> {
            List<Entry> found = loadEntries();
            runOnUiThread(() -> {
                preloaderDialog.close();
                entries.clear();
                entries.addAll(found);
                adapter.notifyDataSetChanged();
                cbSelectAll.setChecked(false);
                updateSelection();
            });
        });
    }

    /** Background thread: flushes pending sessions, then measures every installed game folder. */
    private List<Entry> loadEntries() {
        List<Entry> result = new ArrayList<>();
        Container container = GameLaunchActivity.findSharedContainer(this, new ContainerManager(this));
        if (container == null) return result;

        String running = XServerDisplayActivity.activeBridgeGameId;
        // The same catch-up a launch does: export every dead session while its folder is still there.
        // A live session is skipped by exportOnExit's own session-token check, and the archive of a
        // game replaced since is kept. Anything still pending afterwards could not be written back.
        if (running == null) SaveSync.flushPendingSessions(this, container, null, null);

        File[] dirs = GameLaunchActivity.gamesRoot(container).listFiles();
        if (dirs == null) return result;
        for (File dir : dirs) {
            if (!dir.isDirectory() || FileUtils.isSymlink(dir)) continue;
            String gameId = dir.getName();
            SaveState state = SaveState.load(this, gameId);

            Status status = gameId.equals(running) ? Status.RUNNING
                    : state.pendingExport ? Status.UNSYNCED : Status.OK;
            long lastPlayedAt = Math.max(state.lastPlayedAt, state.lastExportAt);
            if (lastPlayedAt <= 0) lastPlayedAt = new File(dir, PayloadInstaller.INSTALLED_MARKER).lastModified();
            String title = state.title != null && !state.title.isEmpty() ? state.title : gameId;
            result.add(new Entry(gameId, title, dir, sizeOf(dir), lastPlayedAt, status));
        }

        // Biggest first: that is what the player came here for.
        Collections.sort(result, (a, b) -> Long.compare(b.size, a.size));
        return result;
    }

    /** Never follows symlinks: Wine prefixes link out to shared runtime trees. */
    private static long sizeOf(File file) {
        if (FileUtils.isSymlink(file)) return 0;
        if (!file.isDirectory()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    private void updateSelection() {
        long total = 0;
        for (Entry entry : entries) total += entry.size;
        tvSummary.setText(getString(R.string.dgp_storage_summary, entries.size(), StringUtils.formatBytes(total)));
        tvEmptyText.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        cbSelectAll.setEnabled(!entries.isEmpty());

        int count = 0;
        long selectedBytes = 0;
        for (Entry entry : entries) {
            if (!entry.selected) continue;
            count++;
            selectedBytes += entry.size;
        }
        btDelete.setEnabled(count > 0);
        // ButtonPositive has no disabled look of its own.
        btDelete.setAlpha(count > 0 ? 1f : 0.5f);
        btDelete.setText(count > 0
                ? getString(R.string.dgp_storage_delete, StringUtils.formatBytes(selectedBytes))
                : getString(R.string.dgp_storage_delete_none));
    }

    private void confirmDelete() {
        List<Entry> targets = new ArrayList<>();
        int unsynced = 0;
        long bytes = 0;
        for (Entry entry : entries) {
            if (!entry.selected || entry.status == Status.RUNNING) continue;
            targets.add(entry);
            bytes += entry.size;
            if (entry.status == Status.UNSYNCED) unsynced++;
        }
        if (targets.isEmpty()) return;

        String message = getString(R.string.dgp_storage_confirm, targets.size(), StringUtils.formatBytes(bytes));
        if (unsynced > 0) message += "\n\n"+getString(R.string.dgp_storage_confirm_unsynced, unsynced);

        ContentDialog dialog = new ContentDialog(this);
        dialog.setMessage(message, R.drawable.content_dialog_type_confirm);
        dialog.setOnConfirmCallback(() -> delete(targets));
        dialog.show();
    }

    private void delete(List<Entry> targets) {
        preloaderDialog.show(R.string.dgp_storage_deleting);
        executor.execute(() -> {
            int failed = 0;
            for (Entry entry : targets) {
                // Re-checked here: a game may have started since the list was built.
                if (entry.gameId.equals(XServerDisplayActivity.activeBridgeGameId)) continue;
                if (deleteGameFolder(entry)) {
                    freedBytes += entry.size;
                    deletedCount++;
                }
                else failed++;
            }
            final int failures = failed;
            runOnUiThread(() -> {
                preloaderDialog.close();
                if (failures > 0) Toast.makeText(this, R.string.dgp_storage_delete_failed, Toast.LENGTH_LONG).show();
                else Toast.makeText(this, getString(R.string.dgp_storage_deleted,
                        StringUtils.formatBytes(freedBytes)), Toast.LENGTH_SHORT).show();
                scan();
            });
        });
    }

    private boolean deleteGameFolder(Entry entry) {
        // Before the folder goes: an export that ran later would find none of its in-folder saves
        // and overwrite the archive with the shared files alone. Dropping it keeps the last synced
        // archive as it is; the dead session's progress was already unreachable (no grant).
        if (SaveState.load(this, entry.gameId).pendingExport) SaveSync.discardPendingExport(this, entry.gameId);

        // Marker first, so an interrupted delete can only ever leave a folder that reinstalls.
        File marker = new File(entry.dir, PayloadInstaller.INSTALLED_MARKER);
        if (marker.exists() && !marker.delete()) {
            Log.e(TAG, "storage: could not remove the install marker of "+entry.gameId);
            return false;
        }

        boolean deleted = FileUtils.delete(entry.dir) || !Files.exists(entry.dir.toPath());
        Log.i(TAG, "storage: "+(deleted ? "deleted" : "could not fully delete")+" game folder "
                +entry.gameId+" ("+entry.size+" bytes)");
        return deleted;
    }

    private void finishWithResult() {
        Intent result = new Intent();
        result.putExtra(EXTRA_FREED_BYTES, freedBytes);
        result.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private class EntriesAdapter extends RecyclerView.Adapter<EntriesAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            final CheckBox cbSelected;
            final TextView tvTitle;
            final TextView tvDetail;
            final TextView tvSize;

            ViewHolder(View view) {
                super(view);
                cbSelected = view.findViewById(R.id.CBSelected);
                tvTitle = view.findViewById(R.id.TVTitle);
                tvDetail = view.findViewById(R.id.TVDetail);
                tvSize = view.findViewById(R.id.TVSize);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.storage_cleanup_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Entry entry = entries.get(position);
            boolean selectable = entry.status != Status.RUNNING;

            holder.tvTitle.setText(entry.title);
            holder.tvSize.setText(StringUtils.formatBytes(entry.size));
            holder.cbSelected.setChecked(entry.selected);
            holder.cbSelected.setEnabled(selectable);

            String played = entry.lastPlayedAt > 0
                    ? getString(R.string.dgp_storage_last_played, DateUtils.getRelativeTimeSpanString(
                            entry.lastPlayedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    : getString(R.string.dgp_storage_last_played_unknown);
            if (entry.status == Status.RUNNING) played = getString(R.string.dgp_storage_status_running)+" · "+played;
            else if (entry.status == Status.UNSYNCED) played = getString(R.string.dgp_storage_status_unsynced)+" · "+played;
            holder.tvDetail.setText(played);

            holder.itemView.setEnabled(selectable);
            holder.itemView.setAlpha(selectable ? 1f : 0.5f);
            holder.itemView.setOnClickListener(v -> {
                if (!selectable) return;
                entry.selected = !entry.selected;
                holder.cbSelected.setChecked(entry.selected);
                updateSelection();
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
