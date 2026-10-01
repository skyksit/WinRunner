package com.winlator.cheat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds {@link PointerChain}s from the game's images to a value, so the value can be found again in
 * the next run (Cheat Engine's pointer scan, cut down).
 *
 * Works backwards one level at a time: level 1 is every aligned pointer in writable memory that
 * points at most {@link Options#maxOffset} below a target, level 2 the pointers to those, and so on.
 * A pointer that itself lies inside a module image ends a chain. Each level is one pass over the
 * writable memory with the open nodes in a sorted array, so nothing like an index of every pointer
 * (26 million of them in Diablo II) is ever held - the reason it can run on the phone.
 *
 * What the defaults come from (Diablo II gold, 2026-10): the real chains were one or two levels deep
 * with offsets 0x144/0x16C/0x36C/0x86C, so 0x400 missed one and 0x1000 is the limit. Chains whose
 * pointer was not 4-aligned (+0x2A1 and the like) were all noise that failed the next run, so only
 * aligned pointers are followed.
 */
public final class PointerScanner {
    public static final class Options {
        public int maxDepth = 4;
        public int maxOffset = 0x1000;
        /** Open nodes kept per level; the ones with the smallest offsets win. */
        public int maxNodesPerLevel = 20000;
        public int maxChainsPerTarget = 8;
        /**
         * Chains collected per target before the best {@link #maxChainsPerTarget} are kept. The
         * first ones found are not the best: memory is scanned in address order.
         */
        public int maxCandidatesPerTarget = 512;
    }

    public interface Progress {
        /** {@code done} of {@code total} passes over memory. */
        void update(int done, int total);

        boolean isCancelled();
    }

    private static final int CHUNK = 1 << 20;

    /** One open end of a chain: memory at {@code address} is reached from target {@code source}. */
    private static final class Node {
        final long address;
        final int source;
        /** Offset added after dereferencing this node's pointer to reach its parent. */
        final long offset;
        final Node parent;

        Node(long address, int source, long offset, Node parent) {
            this.address = address;
            this.source = source;
            this.offset = offset;
            this.parent = parent;
        }
    }

    private PointerScanner() {}

    /**
     * @param regions writable memory to look for pointers in, as {start, end} pairs (below 4 GB)
     * @return one list per target, shortest chains first; an address inside an image gives a chain
     *         with no offsets
     */
    public static List<List<PointerChain>> scan(MemoryReader memory, List<long[]> regions, ModuleMap modules,
                                                long[] targets, Options options, Progress progress) throws IOException {
        List<List<PointerChain>> chains = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) chains.add(new ArrayList<>());

        List<Node> open = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            ModuleMap.Module image = modules.containing(targets[i]);
            if (image != null) chains.get(i).add(new PointerChain(image.name, targets[i] - image.base, new long[0]));
            else open.add(new Node(targets[i], i, 0, null));
        }

        byte[] buffer = new byte[CHUNK];
        IntBuffer words = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int depth = 1; depth <= options.maxDepth && !open.isEmpty(); depth++) {
            if (progress != null) {
                if (progress.isCancelled()) break;
                progress.update(depth - 1, options.maxDepth);
            }

            Node[] sorted = open.toArray(new Node[0]);
            Arrays.sort(sorted, Comparator.comparingLong(node -> node.address));
            long[] keys = new long[sorted.length];
            for (int i = 0; i < sorted.length; i++) keys[i] = sorted[i].address;
            long low = keys[0] - options.maxOffset;
            long high = keys[keys.length - 1];

            List<Node> next = new ArrayList<>();
            for (long[] region : regions) {
                if (progress != null && progress.isCancelled()) break;
                for (long pos = region[0]; pos < region[1]; pos += CHUNK) {
                    int length = (int)Math.min(CHUNK, region[1] - pos);
                    int read;
                    try {
                        read = memory.read(pos, buffer, 0, length);
                    }
                    catch (IOException e) {
                        continue;
                    }
                    int count = read / 4;
                    for (int w = 0; w < count; w++) {
                        long value = words.get(w) & 0xFFFFFFFFL;
                        if (value < low || value > high || (value & 3) != 0) continue;
                        // Every open node within maxOffset above the value: a struct pointer reaches
                        // all the fields that live in it (two of Diablo II's gold copies share one).
                        int k = lowerBound(keys, value);
                        for (; k < keys.length && keys[k] - value <= options.maxOffset; k++) {
                            Node node = sorted[k];
                            if (chains.get(node.source).size() >= options.maxCandidatesPerTarget) continue;
                            long location = pos + w * 4L;
                            Node link = new Node(location, node.source, node.address - value, node);
                            ModuleMap.Module image = modules.containing(location);
                            if (image != null) addChain(chains.get(node.source), image, link);
                            else next.add(link);
                        }
                    }
                }
            }

            if (next.size() > options.maxNodesPerLevel) {
                Collections.sort(next, Comparator.comparingLong(node -> node.offset));
                next = new ArrayList<>(next.subList(0, options.maxNodesPerLevel));
            }
            open = next;
        }
        if (progress != null) progress.update(options.maxDepth, options.maxDepth);

        // Shortest, then nearest first. Diablo II has an array of pointers in Game.exe to structs
        // 0x100 apart: every one of them is within maxOffset of the gold, and the far ones (through
        // a neighbouring struct) broke on the next run while the nearest kept working (2026-10).
        for (int i = 0; i < chains.size(); i++) {
            List<PointerChain> list = chains.get(i);
            Collections.sort(list, Comparator.comparingInt(PointerChain::depth).thenComparingLong(PointerScanner::offsetSum));
            if (list.size() > options.maxChainsPerTarget) chains.set(i, new ArrayList<>(list.subList(0, options.maxChainsPerTarget)));
        }
        return chains;
    }

    private static void addChain(List<PointerChain> list, ModuleMap.Module image, Node root) {
        List<Long> offsets = new ArrayList<>();
        for (Node node = root; node.parent != null; node = node.parent) offsets.add(node.offset);
        long[] array = new long[offsets.size()];
        for (int i = 0; i < array.length; i++) array[i] = offsets.get(i);
        PointerChain chain = new PointerChain(image.name, root.address - image.base, array);
        if (!list.contains(chain)) list.add(chain);
    }

    private static long offsetSum(PointerChain chain) {
        long sum = 0;
        for (long offset : chain.offsets) sum += offset;
        return sum;
    }

    private static int lowerBound(long[] keys, long value) {
        int low = 0, high = keys.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (keys[mid] < value) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
