package com.winlator.cheat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One game session's cheat search: the search in progress and the values being held. Owned by
 * XServerDisplayActivity so it outlives {@link CheatSearchDialog} - the player searches, closes the
 * dialog, changes the value in the game and searches again.
 *
 * Only reachable when DGPlayer says the player may use it ({@link #EXTRA_CHEAT_SEARCH}); there is
 * deliberately no other way in (no file, broadcast or socket), because anything outside the app that
 * can drive this can rewrite the game's memory.
 *
 * Values held here last for the session. Saving one ({@link #saveCheat}) keeps it as pointer chains
 * from the game's image instead of an address - heap addresses change every run, so a saved address
 * would silently point at something else next time - in DGPlayer's cheats file ({@link CheatStore}).
 * Saved cheats are found again every {@link #APPLY_INTERVAL_S}s while enabled: the game builds the
 * structures the chains run through only once a game is loaded, not in its menus.
 */
public final class CheatSession {
    private static final String TAG = "CheatSearch";
    /** Boolean extra from the bridge caller: show the cheat menu (DGPlayer: premium only). */
    public static final String EXTRA_CHEAT_SEARCH = "cheat_search";
    private static final long FREEZE_INTERVAL_MS = 50;
    private static final long APPLY_INTERVAL_S = 2;
    /** More would make the pointer scan slow, and no value needs that many copies. */
    public static final int MAX_SAVED_TARGETS = 16;

    /** A saved cheat in the running game. */
    public enum Status {
        /** Disabled by the player. */
        OFF,
        /** The game has not built what the chains run through yet (menus, loading). */
        WAITING,
        ACTIVE,
        /** The chains lead somewhere, but the value is not there (Diablo II: gold spent to 0). */
        NOT_FOUND,
        /** The game's image is another size than the one the cheat was saved with. */
        OTHER_VERSION
    }

    public interface SaveResult {
        /** On the main thread: how many of the addresses got chains; 0 means nothing was saved. */
        void done(int saved, int sessionOnly, String error);
    }

    /** Wine's own processes; anything else ending in .exe is taken for the game. */
    private static final Set<String> SYSTEM_PROCESSES = new HashSet<>(Arrays.asList(
        "start.exe", "services.exe", "winedevice.exe", "explorer.exe", "winhandler.exe", "plugplay.exe",
        "rpcss.exe", "svchost.exe", "conhost.exe", "tabtip.exe", "wineboot.exe", "winemenubuilder.exe",
        "rundll32.exe", "cmd.exe"
    ));

    public static final class Cheat {
        public final int pid;
        public final long address;
        public final int size;
        public volatile long value;
        public volatile boolean frozen;
        /** The address stopped being writable (the game quit or freed it). */
        public volatile boolean failed;
        /**
         * The 4 bytes before the value when it was set ({@link TargetResolver#captureTag}), so the
         * freeze loop notices another entry taking the address; {@link TargetResolver#NO_TAG} if unusable.
         */
        volatile long tag = TargetResolver.NO_TAG;
        /** Something else now sits at the address; holding is paused until it comes back. */
        public volatile boolean displaced;
        /** The saved cheat this applies, null for one found this session. */
        volatile SavedCheats.Cheat owner;
        volatile int targetIndex;

        Cheat(int pid, long address, int size, long value, boolean frozen) {
            this.pid = pid;
            this.address = address;
            this.size = size;
            this.value = value;
            this.frozen = frozen;
        }
    }

    public interface Result {
        /** On the main thread; {@code error} is null on success. */
        void done(String error);
    }

    private interface Job {
        void run() throws IOException;
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "CheatSearch"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Cheat> cheats = new CopyOnWriteArrayList<>();
    private final String preferredExe;
    private volatile MemoryScanner scanner;
    private Thread freezeThread;
    private volatile boolean closed;
    private final CheatStore store;
    /** Read under the session lock; only the worker changes it. */
    private SavedCheats saved = new SavedCheats();
    private final Map<SavedCheats.Cheat, Status> statuses = Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile boolean savedDirty;
    private volatile Runnable savedListener;

    /**
     * @param execPath the executable the session launched; preferred when several games run.
     * @param store where saved cheats live, or null when the launch sent none (they are then off).
     */
    public CheatSession(String execPath, CheatStore store) {
        preferredExe = execPath != null ? FileUtils.getName(execPath).toLowerCase(Locale.ROOT) : null;
        this.store = store;
        if (store != null) {
            worker.execute(() -> {
                SavedCheats loaded = store.load();
                synchronized (this) {
                    saved = loaded;
                }
                notifySaved();
            });
            worker.scheduleWithFixedDelay(this::applySaved, APPLY_INTERVAL_S, APPLY_INTERVAL_S, TimeUnit.SECONDS);
        }
    }

    /** Guest processes that look like the game, the launched executable first. */
    public List<ProcessHelper.PStat> findGameProcesses() {
        List<ProcessHelper.PStat> result = new ArrayList<>();
        for (ProcessHelper.PStat stat : ProcessHelper.getChildProcesses()) {
            String name = FileUtils.getName(stat.name).toLowerCase(Locale.ROOT);
            if (!name.endsWith(".exe") || SYSTEM_PROCESSES.contains(name)) continue;
            if (name.equals(preferredExe)) result.add(0, stat);
            else result.add(stat);
        }
        return result;
    }

    public static String displayName(ProcessHelper.PStat stat) {
        return FileUtils.getName(stat.name) + " (" + stat.pid + ")";
    }

    /** The search in progress, or null before the first scan. */
    public MemoryScanner getScanner() {
        return scanner;
    }

    /** Values held this session; the ones applying a saved cheat are listed with it instead. */
    public List<Cheat> getCheats() {
        List<Cheat> held = new ArrayList<>();
        for (Cheat cheat : cheats) if (cheat.owner == null) held.add(cheat);
        return held;
    }

    public void firstScan(int pid, int size, long value, Result result) {
        submit(() -> {
            MemoryScanner fresh = new MemoryScanner(pid);
            long started = System.currentTimeMillis();
            fresh.firstScan(size, value);
            scanner = fresh;
            Log.i(TAG, "first scan pid="+pid+" size="+size+" hits="+fresh.getCount()+" truncated="+fresh.isTruncated()
                    +" in "+(System.currentTimeMillis() - started)+"ms");
        }, result);
    }

    public void nextScan(MemoryScanner.Compare compare, long operand, Result result) {
        submit(() -> {
            if (scanner == null) return;
            long started = System.currentTimeMillis();
            scanner.nextScan(compare, operand);
            Log.i(TAG, "next scan "+compare+" left="+scanner.getCount()+" in "+(System.currentTimeMillis() - started)+"ms");
        }, result);
    }

    public void resetSearch(Result result) {
        submit(() -> scanner = null, result);
    }

    /** Reads the listed candidates' current values off the UI thread. */
    public void readValues(long[] addresses, int size, int pid, ValuesResult result) {
        worker.execute(() -> {
            long[] values = new long[addresses.length];
            boolean[] ok = new boolean[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                try {
                    values[i] = MemoryScanner.read(pid, addresses[i], size);
                    ok[i] = true;
                }
                catch (IOException ignored) {}
            }
            mainHandler.post(() -> result.done(values, ok));
        });
    }

    public interface ValuesResult {
        void done(long[] values, boolean[] ok);
    }

    /** Writes the value once, and keeps writing it while {@code frozen}. */
    public void setValue(int pid, long address, int size, long value, boolean frozen, Result result) {
        submit(() -> setValueNow(pid, address, size, value, frozen), result);
    }

    private void setValueNow(int pid, long address, int size, long value, boolean frozen) throws IOException {
        MemoryScanner.write(pid, address, size, value);
        Cheat cheat = find(pid, address);
        if (cheat != null) {
            cheat.value = value;
            cheat.failed = false;
        }
        else cheats.add(cheat = new Cheat(pid, address, size, value, false));
        arm(cheat);
        cheat.frozen = frozen;
        if (frozen) ensureFreezeThread();
    }

    /** Turning holding back on also re-takes the tag: the player says the address is right again. */
    public void setFrozen(Cheat cheat, boolean frozen) {
        if (!frozen) {
            cheat.frozen = false;
            return;
        }
        submit(() -> {
            arm(cheat);
            cheat.failed = false;
            cheat.frozen = true;
            ensureFreezeThread();
        }, null);
    }

    private static void arm(Cheat cheat) {
        try (ProcMemory memory = new ProcMemory(cheat.pid)) {
            cheat.tag = TargetResolver.captureTag(memory, cheat.address);
        }
        catch (IOException e) {
            cheat.tag = TargetResolver.NO_TAG;
        }
        cheat.displaced = false;
    }

    public void remove(Cheat cheat) {
        cheats.remove(cheat);
    }

    private Cheat find(int pid, long address) {
        for (Cheat cheat : cheats) if (cheat.pid == pid && cheat.address == address) return cheat;
        return null;
    }

    private void submit(Job job, Result result) {
        worker.execute(() -> {
            String error = null;
            try {
                job.run();
            }
            catch (Throwable e) {
                Log.w(TAG, "cheat job failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            if (result != null) {
                final String message = error;
                mainHandler.post(() -> result.done(message));
            }
        });
    }

    /**
     * Whether the held value still owns its address. A changed tag with the value as we left it is
     * just a neighbour changing - the tag is re-learnt. A changed tag and a changed value is another
     * entry having moved in (Diablo II's stash gold taking the gold's slot once the gold hit 0):
     * writing would overwrite it, so holding pauses until the tag comes back.
     */
    private static boolean stillThere(RandomAccessFile mem, Cheat cheat) throws IOException {
        long tag = cheat.tag;
        if (tag == TargetResolver.NO_TAG) return true;
        byte[] bytes = new byte[4 + cheat.size];
        mem.seek(cheat.address - 4);
        mem.readFully(bytes);
        long current = 0, value = 0;
        for (int i = 0; i < 4; i++) current |= (bytes[i] & 0xFFL) << (8 * i);
        for (int i = 0; i < cheat.size; i++) value |= (bytes[4 + i] & 0xFFL) << (8 * i);

        if (current == tag) {
            if (cheat.displaced) Log.i(TAG, String.format("0x%08x back in place, holding again", cheat.address));
            cheat.displaced = false;
            return true;
        }
        if (!cheat.displaced && value == (cheat.value & MemoryScanner.mask(cheat.size))) {
            cheat.tag = current == 0 || current == 0xFFFFFFFFL ? TargetResolver.NO_TAG : current;
            return true;
        }
        if (!cheat.displaced) Log.i(TAG, String.format("0x%08x taken by another entry (tag %08x -> %08x), not writing", cheat.address, tag, current));
        cheat.displaced = true;
        return false;
    }

    private synchronized void ensureFreezeThread() {
        if (closed || freezeThread != null) return;
        freezeThread = new Thread(this::freezeLoop, "CheatFreeze");
        freezeThread.setDaemon(true);
        freezeThread.start();
    }

    private void freezeLoop() {
        RandomAccessFile mem = null;
        int memPid = -1;
        try {
            while (!closed) {
                for (Cheat cheat : cheats) {
                    if (!cheat.frozen || cheat.failed) continue;
                    try {
                        if (mem == null || memPid != cheat.pid) {
                            if (mem != null) mem.close();
                            mem = null;
                            mem = new RandomAccessFile("/proc/"+cheat.pid+"/mem", "rw");
                            memPid = cheat.pid;
                        }
                        if (stillThere(mem, cheat)) MemoryScanner.write(mem, cheat.address, cheat.size, cheat.value);
                    }
                    catch (IOException e) {
                        // Usually the game exited; stop rather than retry 20 times a second.
                        Log.w(TAG, String.format("freeze 0x%08x failed: %s", cheat.address, e));
                        cheat.failed = true;
                        cheat.frozen = false;
                        if (mem != null) {
                            try { mem.close(); } catch (IOException ignored) {}
                            mem = null;
                        }
                    }
                }
                Thread.sleep(FREEZE_INTERVAL_MS);
            }
        }
        catch (InterruptedException ignored) {}
        finally {
            if (mem != null) {
                try { mem.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ---- Saved cheats --------------------------------------------------------------------------

    /** Whether this launch can save cheats (DGPlayer sent a cheats file). */
    public boolean canSave() {
        return store != null;
    }

    public synchronized List<SavedCheats.Cheat> getSaved() {
        return new ArrayList<>(saved.cheats);
    }

    public Status statusOf(SavedCheats.Cheat cheat) {
        Status status = statuses.get(cheat);
        return status != null ? status : (cheat.enabled ? Status.WAITING : Status.OFF);
    }

    /** Called on the main thread whenever the saved list or a status changes; null to stop. */
    public void setSavedListener(Runnable listener) {
        savedListener = listener;
    }

    private void notifySaved() {
        mainHandler.post(() -> {
            Runnable listener = savedListener;
            if (listener != null) listener.run();
        });
    }

    /**
     * Writes {@code value} to every address and saves them as one cheat: each address gets the
     * pointer chains that reach it from an image ({@link PointerScanner}). Addresses no chain
     * reaches (a copy the game recomputes, like Diablo II's displayed gold) stay held for this
     * session only. Takes seconds: the scan passes over all writable memory once per level.
     */
    public void saveCheat(String name, int pid, long[] addresses, int size, long value, boolean frozen,
                          PointerScanner.Progress progress, SaveResult result) {
        worker.execute(() -> {
            int kept = 0;
            String error = null;
            try {
                for (long address : addresses) setValueNow(pid, address, size, value, frozen);

                List<ProcessMaps.Mapping> maps = ProcessMaps.read(pid);
                ModuleMap modules = ModuleMap.build(maps);
                List<List<PointerChain>> chains;
                long[] tags = new long[addresses.length];
                try (ProcMemory memory = new ProcMemory(pid)) {
                    for (int i = 0; i < addresses.length; i++) tags[i] = TargetResolver.captureTag(memory, addresses[i]);
                    long started = System.currentTimeMillis();
                    chains = PointerScanner.scan(memory, ProcessMaps.writableBelow4G(maps), modules, addresses,
                            new PointerScanner.Options(), progress);
                    Log.i(TAG, "pointer scan of "+addresses.length+" addresses in "+(System.currentTimeMillis() - started)+"ms");
                }
                if (progress != null && progress.isCancelled()) throw new IOException("cancelled");

                List<SavedCheats.Target> targets = new ArrayList<>();
                List<Long> targetAddresses = new ArrayList<>();
                for (int i = 0; i < addresses.length; i++) {
                    Log.i(TAG, String.format(Locale.ROOT, "0x%08x: %d chains %s", addresses[i], chains.get(i).size(), chains.get(i)));
                    if (chains.get(i).isEmpty()) continue;
                    targets.add(new SavedCheats.Target(chains.get(i), tags[i]));
                    targetAddresses.add(addresses[i]);
                }
                kept = targets.size();
                if (kept > 0) {
                    SavedCheats.Cheat cheat = new SavedCheats.Cheat(name, size, value, frozen, true, targets);
                    ModuleMap.Module main = preferredExe != null ? modules.find(preferredExe) : null;
                    synchronized (this) {
                        if (saved.module == null && main != null) {
                            saved.module = main.name;
                            saved.moduleSize = main.size();
                        }
                        saved.cheats.add(cheat);
                    }
                    for (int i = 0; i < targetAddresses.size(); i++) {
                        Cheat held = find(pid, targetAddresses.get(i));
                        if (held == null) continue;
                        held.owner = cheat;
                        held.targetIndex = i;
                    }
                    statuses.put(cheat, Status.ACTIVE);
                    persist();
                }
            }
            catch (Throwable e) {
                Log.w(TAG, "saving cheat failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final int savedCount = kept;
            final String message = error;
            mainHandler.post(() -> result.done(savedCount, addresses.length - savedCount, message));
            notifySaved();
        });
    }

    /** Edits a saved cheat; it is found and written again right away. */
    public void updateSaved(SavedCheats.Cheat cheat, String name, long value, boolean freeze, boolean enabled) {
        worker.execute(() -> {
            synchronized (this) {
                cheat.name = name;
                cheat.value = value;
                cheat.freeze = freeze;
                cheat.enabled = enabled;
            }
            dropHeld(cheat);
            statuses.remove(cheat);
            persist();
            applySaved();
            notifySaved();
        });
    }

    public void deleteSaved(SavedCheats.Cheat cheat) {
        worker.execute(() -> {
            synchronized (this) {
                saved.cheats.remove(cheat);
            }
            dropHeld(cheat);
            statuses.remove(cheat);
            persist();
            notifySaved();
        });
    }

    private void dropHeld(SavedCheats.Cheat cheat) {
        for (Cheat held : cheats) if (held.owner == cheat) cheats.remove(held);
    }

    private void persist() {
        if (store == null) return;
        savedDirty = true;
        SavedCheats snapshot;
        synchronized (this) {
            snapshot = saved;
        }
        if (store.save(snapshot)) savedDirty = false;
    }

    /** Worker thread: finds every enabled saved cheat that is not being applied and applies it. */
    private void applySaved() {
        if (closed) return;
        List<SavedCheats.Cheat> list = getSaved();
        if (list.isEmpty()) return;
        boolean changed = false;
        try {
            List<ProcessHelper.PStat> processes = findGameProcesses();
            int pid = processes.isEmpty() ? -1 : processes.get(0).pid;
            ModuleMap modules = null;
            boolean otherVersion = false;
            if (pid > 0) {
                modules = ModuleMap.build(ProcessMaps.read(pid));
                synchronized (this) {
                    ModuleMap.Module main = saved.module != null ? modules.find(saved.module) : null;
                    otherVersion = main != null && saved.moduleSize > 0 && main.size() != saved.moduleSize;
                }
            }

            for (SavedCheats.Cheat cheat : list) {
                Status before = statusOf(cheat);
                Status after = apply(cheat, pid, modules, otherVersion);
                if (after != before || !statuses.containsKey(cheat)) {
                    statuses.put(cheat, after);
                    changed = true;
                    if (after != before) Log.i(TAG, "saved cheat '"+cheat.name+"': "+before+" -> "+after);
                }
                learnTags(cheat);
            }
        }
        catch (Throwable e) {
            Log.w(TAG, "applying saved cheats failed", e);
        }
        if (savedDirty) persist();
        if (changed) notifySaved();
    }

    private Status apply(SavedCheats.Cheat cheat, int pid, ModuleMap modules, boolean otherVersion) throws IOException {
        if (!cheat.enabled) {
            dropHeld(cheat);
            return Status.OFF;
        }
        if (pid <= 0 || modules == null) return Status.WAITING;
        if (otherVersion) return Status.OTHER_VERSION;

        List<Cheat> held = new ArrayList<>();
        for (Cheat candidate : cheats) if (candidate.owner == cheat) held.add(candidate);
        boolean healthy = held.size() == cheat.targets.size();
        for (Cheat candidate : held) healthy &= candidate.pid == pid && !candidate.failed && !candidate.displaced;
        if (healthy) return Status.ACTIVE;

        long[] found = new long[cheat.targets.size()];
        int followed = 0;
        try (ProcMemory memory = new ProcMemory(pid)) {
            for (int i = 0; i < found.length; i++) {
                SavedCheats.Target target = cheat.targets.get(i);
                TargetResolver.Resolution resolution = TargetResolver.resolve(memory, modules, target.chains, target.tag);
                found[i] = resolution.address;
                followed += resolution.followed;
                if (pruneChains(target, resolution)) savedDirty = true;
            }
        }
        for (long address : found) {
            if (address < 0) return followed == 0 ? Status.WAITING : Status.NOT_FOUND;
        }

        dropHeld(cheat);
        for (int i = 0; i < found.length; i++) {
            MemoryScanner.write(pid, found[i], cheat.size, cheat.value);
            Cheat applied = new Cheat(pid, found[i], cheat.size, cheat.value, cheat.freeze);
            applied.owner = cheat;
            applied.targetIndex = i;
            applied.tag = cheat.targets.get(i).tag;
            cheats.add(applied);
        }
        if (cheat.freeze) ensureFreezeThread();
        Log.i(TAG, String.format(Locale.ROOT, "applied saved cheat '%s' = %d at %d addresses", cheat.name, cheat.value, found.length));
        return Status.ACTIVE;
    }

    /**
     * Drops chains that led elsewhere while at least two others agreed: the scan keeps a few
     * look-alikes (the Diablo II one did), and they only cost time on every later run.
     */
    private static boolean pruneChains(SavedCheats.Target target, TargetResolver.Resolution resolution) {
        if (!resolution.found()) return false;
        int agreeing = 0;
        for (boolean agreed : resolution.agreed) if (agreed) agreeing++;
        if (agreeing < 2 || agreeing == target.chains.size()) return false;
        List<PointerChain> keep = new ArrayList<>();
        for (int i = 0; i < target.chains.size(); i++) if (resolution.agreed[i]) keep.add(target.chains.get(i));
        Log.i(TAG, "dropping "+(target.chains.size() - keep.size())+" chains that did not agree");
        target.chains.clear();
        target.chains.addAll(keep);
        return true;
    }

    /** The freeze loop re-learns a tag a neighbour rewrote; keeps the saved one in step. */
    private void learnTags(SavedCheats.Cheat cheat) {
        for (Cheat held : cheats) {
            if (held.owner != cheat || held.targetIndex >= cheat.targets.size()) continue;
            SavedCheats.Target target = cheat.targets.get(held.targetIndex);
            if (target.tag != held.tag) {
                target.tag = held.tag;
                savedDirty = true;
            }
        }
    }

    /** Session over: stop holding values. Pending changes to saved cheats are written first. */
    public synchronized void close() {
        if (closed) return;
        if (savedDirty && store != null) store.save(saved);
        closed = true;
        if (freezeThread != null) freezeThread.interrupt();
        freezeThread = null;
        cheats.clear();
        worker.shutdownNow();
    }
}
