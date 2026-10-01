package com.winlator.cheat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Where each .exe/.dll image sits in a guest process. A saved cheat is "module + offset", so it is
 * the module base that makes it survive a restart while heap addresses move.
 *
 * Seen with Wine (Diablo II 1.14d, 2026-10): every section of a PE image is a file mapping with the
 * image's path - including the writable data section - and the first one has file offset 0 at the
 * image base. The .bss part is anonymous memory, but it lies between two mappings of the same image,
 * so a module is taken to span from its base to the end of the last mapping with its path, anything
 * anonymous in between included. A mapping of another file ends it.
 */
public final class ModuleMap {
    public static final class Module {
        /** The file name as mapped, e.g. "Game.exe". */
        public final String name;
        public final String path;
        public final long base;
        public final long end;

        Module(String name, String path, long base, long end) {
            this.name = name;
            this.path = path;
            this.base = base;
            this.end = end;
        }

        public long size() {
            return end - base;
        }

        public boolean contains(long address) {
            return address >= base && address < end;
        }
    }

    private final List<Module> modules;

    private ModuleMap(List<Module> modules) {
        this.modules = Collections.unmodifiableList(modules);
    }

    public static ModuleMap build(List<ProcessMaps.Mapping> mappings) {
        List<Module> modules = new ArrayList<>();
        for (int i = 0; i < mappings.size(); i++) {
            ProcessMaps.Mapping first = mappings.get(i);
            if (first.offset != 0 || first.start >= ProcessMaps.LIMIT_4G || !isImage(first.path)) continue;

            long end = first.end;
            for (int j = i + 1; j < mappings.size(); j++) {
                ProcessMaps.Mapping next = mappings.get(j);
                if (next.path.isEmpty()) continue;
                if (!next.path.equals(first.path) || next.offset == 0) break;
                end = next.end;
            }
            modules.add(new Module(fileName(first.path), first.path, first.start, end));
        }
        return new ModuleMap(modules);
    }

    public List<Module> modules() {
        return modules;
    }

    /** By file name, ignoring case (Windows does); the first image with that name. */
    public Module find(String name) {
        for (Module module : modules) {
            if (module.name.equalsIgnoreCase(name)) return module;
        }
        return null;
    }

    public Module containing(long address) {
        for (Module module : modules) {
            if (module.contains(address)) return module;
        }
        return null;
    }

    private static boolean isImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".dll");
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
