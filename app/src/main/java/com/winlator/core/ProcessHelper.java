package com.winlator.core;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.winlator.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public abstract class ProcessHelper {
    private static final String TAG = "WinlatorProcess";
    public enum PState {RUNNING, SLEEPING, WAITING, ZOMBIE, STOPPED, DEAD, OTHER}
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();

    public static class PStat {
        public int pid = 0;
        public String name = "";
        public String shortName = "";
        public PState state = PState.OTHER;
        public int parentPID = 0;
        public boolean guestProcess = false;

        @NonNull
        @Override
        public String toString() {
            return pid+" "+name+" "+state+" "+parentPID+" "+guestProcess;
        }
    }

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGCONT);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGKILL);
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, EnvVars envVars) {
        return exec(command, envVars, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir) {
        return exec(command, envVars, workingDir, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
        int pid = -1;
        try {
            ProcessBuilder processBuilder = (new ProcessBuilder(splitCommand(command))).directory(workingDir);
            if (debugCallbacks.isEmpty()) processBuilder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);

            Map<String, String> environment = processBuilder.environment();
            for (String name : envVars) environment.put(name, envVars.get(name));

            java.lang.Process process = processBuilder.start();
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);

            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }

            if (terminationCallback != null) createWaitForThread(process, terminationCallback);
        }
        catch (Exception e) {
            // Swallowing this made a failed guest launch indistinguishable from a slow one: the
            // preloader just sits on "Starting up..." forever. Anything driving the app headlessly
            // (the DGPlayer bridge) has no other way to see it.
            Log.e(TAG, "exec failed: "+command, e);
        }
        return pid;
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            // Mirrored to logcat so guest output is reachable over adb, not just
                            // through the in-app log dialog.
                            Log.d(TAG, line);
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                        else if (MainActivity.DEBUG_MODE) System.out.println(line);
                    }
                }
            }
            catch (IOException e) {}
        });
    }

    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int status = process.waitFor();
                terminationCallback.call(status);
            }
            catch (InterruptedException e) {}
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static long getMemoryUsage(int pid) {
        try (Scanner scanner = new Scanner(new FileInputStream("/proc/"+pid+"/statm"))) {
            byte index = 0;
            long vmSize = 0;
            long resident = 0;

            while (scanner.hasNext() && index < 2) {
                byte column = index++;
                if (column == 0) {
                    vmSize = scanner.nextLong();
                }
                else resident = scanner.nextLong();
            }

            return (resident * Os.sysconf(OsConstants._SC_PAGESIZE));
        }
        catch (Exception e) {
            return 0;
        }
    }

    public static String getProcessName(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/cmdline"))) {
            StringBuilder result = new StringBuilder();
            int chr;
            while ((chr = reader.read()) != -1 && chr != 0) {
                result.append((char)chr);
            }
            return result.length() > 0 && result.charAt(0) == '/' ? FileUtils.getName(result.toString()) : result.toString();
        }
        catch (IOException e) {
            return "";
        }
    }

    public static List<String> getProcessCmdLine(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/cmdline"))) {
            StringBuilder sb = new StringBuilder();
            ArrayList<String> result = new ArrayList<>();

            int charsRead;
            char[] chars = new char[64];
            while ((charsRead = reader.read(chars)) != -1) {
                for (int i = 0; i < charsRead; i++) {
                    if (chars[i] == '\0') {
                        if (sb.length() == 0) break;
                        result.add(sb.toString());
                        sb = new StringBuilder();
                    }
                    else sb.append(chars[i]);
                }
            }
            return result;
        }
        catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static List<PStat> getChildProcesses() {
        File procFile = new File("/proc");
        String[] pids = procFile.list((file, name) -> (new File(file, name)).isDirectory() && name.matches("[0-9]+"));
        if (pids == null) return Collections.emptyList();
        ArrayList<PStat> result = new ArrayList<>();
        int parentPID = Os.getpid();

        for (String pid : pids) {
            try (Scanner scanner = new Scanner(new FileInputStream("/proc/"+pid+"/stat"))) {
                PStat pstat = new PStat();
                int index = 0;

                while (scanner.hasNext() && index < 4) {
                    switch (index++) {
                        case 0:
                            pstat.pid = scanner.nextInt();
                            break;
                        case 1:
                            Pattern oldDelimiter = scanner.delimiter();
                            scanner.useDelimiter("\\)");
                            pstat.shortName = scanner.hasNext() ? scanner.next().substring(2) : "";
                            scanner.useDelimiter(oldDelimiter);
                            if (scanner.hasNext()) scanner.next();
                            break;
                        case 2: {
                            switch (scanner.next()) {
                                case "R":
                                    pstat.state = PState.RUNNING;
                                    break;
                                case "S":
                                    pstat.state = PState.SLEEPING;
                                    break;
                                case "D":
                                    pstat.state = PState.WAITING;
                                    break;
                                case "Z":
                                    pstat.state = PState.ZOMBIE;
                                    break;
                                case "T":
                                    pstat.state = PState.STOPPED;
                                    break;
                                case "X":
                                    pstat.state = PState.DEAD;
                                    break;
                            }
                            break;
                        }
                        case 3:
                            pstat.parentPID = scanner.nextInt();
                            break;
                    }
                }

                if (pstat.parentPID == parentPID || pstat.pid > parentPID) {
                    pstat.name = getProcessName(pstat.pid);
                    if (pstat.name.isEmpty()) pstat.name = pstat.shortName;
                    pstat.guestProcess = pstat.name.contains("wine") || pstat.name.contains(".exe");
                    result.add(pstat);
                }
            }
            catch (Exception e) {
                return Collections.emptyList();
            }
        }

        return result;
    }

    /**
     * Every process of this uid whose executable lives inside {@code rootDir}: box64 (which is what
     * wineserver, winhandler and every .exe really are), pulseaudio and any other rootfs helper.
     * Unlike {@link #getChildProcesses()} this depends neither on the parent pid nor on pid
     * ordering, so it also finds guests that init adopted after the process that spawned them went
     * away, and guests older than the current app process (a relaunched session runs in a new
     * process with a higher pid than the leftovers of the old one).
     */
    public static List<Integer> getGuestProcessPids(File rootDir) {
        String[] roots = rootPathForms(rootDir);
        String[] entries = (new File("/proc")).list((file, name) -> name.matches("[0-9]+"));
        if (entries == null) return Collections.emptyList();
        int self = Os.getpid();
        ArrayList<Integer> result = new ArrayList<>();
        for (String entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry);
            }
            catch (NumberFormatException e) {
                continue;
            }
            if (pid != self && isGuestProcess(pid, roots)) result.add(pid);
        }
        return result;
    }

    /**
     * Sends SIGTERM to every guest process (see {@link #getGuestProcessPids}) so wineserver and the
     * game can run their exit handlers, then SIGKILL to whatever is still alive after
     * {@code graceMillis}. A grace of 0 goes straight to SIGKILL. Returns "pid name" for each
     * process that was still running when called, empty when there was nothing to do.
     */
    public static List<String> killGuestProcesses(File rootDir, long graceMillis) {
        List<Integer> pids = getGuestProcessPids(rootDir);
        if (pids.isEmpty()) return Collections.emptyList();
        ArrayList<String> names = new ArrayList<>(pids.size());
        for (int pid : pids) names.add(pid+" "+getProcessName(pid));

        int signal = graceMillis > 0 ? OsConstants.SIGTERM : OsConstants.SIGKILL;
        for (int pid : pids) Process.sendSignal(pid, signal);

        if (graceMillis > 0) {
            long deadline = SystemClock.uptimeMillis() + graceMillis;
            List<Integer> alive = pids;
            while (!alive.isEmpty() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50);
                alive = stillAlive(alive);
            }
            for (int pid : alive) Process.sendSignal(pid, OsConstants.SIGKILL);
        }
        return names;
    }

    private static String[] rootPathForms(File rootDir) {
        // /data/user/0/<pkg> and /data/data/<pkg> are the same directory; /proc/<pid>/exe reports
        // whichever spelling the launcher used, so accept both plus the canonical form.
        String raw = rootDir.getAbsolutePath();
        String canonical;
        try {
            canonical = rootDir.getCanonicalPath();
        }
        catch (IOException e) {
            canonical = raw;
        }
        String alt = raw;
        if (raw.startsWith("/data/user/0/")) alt = "/data/data/"+raw.substring("/data/user/0/".length());
        else if (raw.startsWith("/data/data/")) alt = "/data/user/0/"+raw.substring("/data/data/".length());
        return new String[]{raw+"/", canonical+"/", alt+"/"};
    }

    private static boolean isGuestProcess(int pid, String[] roots) {
        String exe = null;
        try {
            exe = Os.readlink("/proc/"+pid+"/exe");
        }
        catch (ErrnoException e) {
            // ENOENT: a zombie or a kernel thread. EACCES: not dumpable. Fall through to the name.
        }
        if (exe != null) {
            int deleted = exe.indexOf(" (deleted)");
            if (deleted > 0) exe = exe.substring(0, deleted);
            return startsWithAny(exe, roots);
        }
        // Same heuristic as getChildProcesses(): with hidepid=2 every pid we can see is our uid,
        // so a process called wineserver or something.exe can only be one of our guests.
        String name = getProcessName(pid);
        return startsWithAny(name, roots) || name.contains("wine") || name.toLowerCase().endsWith(".exe");
    }

    private static boolean startsWithAny(String value, String[] prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static List<Integer> stillAlive(List<Integer> pids) {
        ArrayList<Integer> alive = new ArrayList<>();
        for (int pid : pids) {
            // A zombie keeps its /proc entry until init reaps it; its stat state is Z.
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/stat"))) {
                String line = reader.readLine();
                if (line == null) continue;
                int close = line.lastIndexOf(')');
                if (close < 0 || close+2 >= line.length()) continue;
                if (line.charAt(close+2) != 'Z') alive.add(pid);
            }
            catch (IOException e) {
                // gone
            }
        }
        return alive;
    }
}
