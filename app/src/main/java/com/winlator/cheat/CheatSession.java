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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One game session's cheat search: the search in progress and the values being held. Owned by
 * XServerDisplayActivity so it outlives {@link CheatSearchDialog} - the player searches, closes the
 * dialog, changes the value in the game and searches again.
 *
 * Only reachable when DGPlayer says the player may use it ({@link #EXTRA_CHEAT_SEARCH}); there is
 * deliberately no other way in (no file, broadcast or socket), because anything outside the app that
 * can drive this can rewrite the game's memory.
 *
 * Cheats last for the session only: heap addresses change every run, so a saved address would
 * silently point at something else next time.
 */
public final class CheatSession {
    private static final String TAG = "CheatSearch";
    /** Boolean extra from the bridge caller: show the cheat menu (DGPlayer: premium only). */
    public static final String EXTRA_CHEAT_SEARCH = "cheat_search";
    private static final long FREEZE_INTERVAL_MS = 50;

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

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "CheatSearch"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Cheat> cheats = new CopyOnWriteArrayList<>();
    private final String preferredExe;
    private volatile MemoryScanner scanner;
    private Thread freezeThread;
    private volatile boolean closed;

    /** @param execPath the executable the session launched; preferred when several games run. */
    public CheatSession(String execPath) {
        preferredExe = execPath != null ? FileUtils.getName(execPath).toLowerCase(Locale.ROOT) : null;
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

    public List<Cheat> getCheats() {
        return cheats;
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

    public void resetSearch() {
        submit(() -> scanner = null, null);
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
        submit(() -> {
            MemoryScanner.write(pid, address, size, value);
            Cheat existing = find(pid, address);
            if (existing != null) {
                existing.value = value;
                existing.frozen = frozen;
                existing.failed = false;
            }
            else cheats.add(new Cheat(pid, address, size, value, frozen));
            if (frozen) ensureFreezeThread();
        }, result);
    }

    public void setFrozen(Cheat cheat, boolean frozen) {
        cheat.frozen = frozen;
        if (frozen) {
            cheat.failed = false;
            ensureFreezeThread();
        }
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
                        MemoryScanner.write(mem, cheat.address, cheat.size, cheat.value);
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

    /** Session over: stop holding values. */
    public synchronized void close() {
        closed = true;
        if (freezeThread != null) freezeThread.interrupt();
        freezeThread = null;
        cheats.clear();
        worker.shutdownNow();
    }
}
