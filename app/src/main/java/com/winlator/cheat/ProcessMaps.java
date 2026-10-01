package com.winlator.cheat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** /proc/&lt;pid&gt;/maps, every line kept (the pathname is what tells a module's image from the heap). */
public final class ProcessMaps {
    /** Wine's new-WoW64 mode keeps a 32-bit game's view below this; see {@link MemoryScanner}. */
    public static final long LIMIT_4G = 0x1_0000_0000L;

    public static final class Mapping {
        public final long start;
        public final long end;
        public final String perms;
        public final long offset;
        /** Empty for anonymous memory. */
        public final String path;

        Mapping(long start, long end, String perms, long offset, String path) {
            this.start = start;
            this.end = end;
            this.perms = perms;
            this.offset = offset;
            this.path = path;
        }

        public boolean isReadable() {
            return perms.length() > 0 && perms.charAt(0) == 'r';
        }

        public boolean isWritable() {
            return perms.length() > 1 && perms.charAt(1) == 'w';
        }
    }

    private ProcessMaps() {}

    public static List<Mapping> read(int pid) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/maps"))) {
            return parse(reader);
        }
    }

    public static List<Mapping> parse(BufferedReader reader) throws IOException {
        List<Mapping> mappings = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            // start-end perms offset dev inode [path] - the path may contain spaces.
            String[] fields = line.trim().split("\\s+", 6);
            if (fields.length < 5) continue;
            int dash = fields[0].indexOf('-');
            if (dash < 0) continue;
            try {
                long start = Long.parseUnsignedLong(fields[0].substring(0, dash), 16);
                long end = Long.parseUnsignedLong(fields[0].substring(dash + 1), 16);
                long offset = Long.parseUnsignedLong(fields[2], 16);
                mappings.add(new Mapping(start, end, fields[1], offset, fields.length > 5 ? fields[5].trim() : ""));
            }
            catch (NumberFormatException ignored) {}
        }
        return mappings;
    }

    /** Readable and writable mappings below 4 GB, clamped to it - where a game keeps its data. */
    public static List<long[]> writableBelow4G(List<Mapping> mappings) {
        List<long[]> regions = new ArrayList<>();
        for (Mapping mapping : mappings) {
            if (!mapping.isReadable() || !mapping.isWritable() || mapping.start >= LIMIT_4G) continue;
            regions.add(new long[]{mapping.start, Math.min(mapping.end, LIMIT_4G)});
        }
        return regions;
    }
}
