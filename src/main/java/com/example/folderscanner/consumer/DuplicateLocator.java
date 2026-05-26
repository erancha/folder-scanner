package com.example.folderscanner.consumer;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.PathFileInfo;
import com.example.folderscanner.data.PoisonPill;
import com.example.folderscanner.data.TypeFileInfo;
import com.example.folderscanner.producer.FileInfoFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Consumer that locates files with identical content (regardless of name).
 *
 * Phase 1 (concurrent with the scan): bucket every incoming PathFileInfo
 * by size. Files whose size is unique in the tree cannot be duplicates of
 * anything and are dropped in phase 2 without ever being read.
 *
 * Phase 2 (after every drainer has taken POISON): for each same-size group
 * with two or more entries, hash the first 4 KB to cheaply split, then
 * full-hash the surviving subgroups. Confirmed duplicate groups are
 * collected into an internal report sorted by descending wasted bytes.
 * Phase 2 runs in a dedicated ForkJoinPool sized to consumerThreads.
 *
 * Phase 3 (script generation): ScriptWriter emits the shell script to
 * the path resolved from -Dout, defaulting to remove-duplicates.sh in cwd.
 */
public final class DuplicateLocator implements FileConsumer {

    /** Application queue: handed in by the composition root; drained by this consumer. */
    private final BlockingQueue<FileInfo> queue;

    /** Number of drainer threads; one POISON per drainer is expected. */
    private final int consumerThreads;

    /** Fixed pool of long-running drainers; each loops until POISON. */
    private final ExecutorService pool;

    /** Where the generated script should be written; from -Dout, resolved by OutPathResolver. */
    private final String outPathRaw;

    /** When true, generated script uses rm; when false, uses mv to a bin. */
    private final boolean hardDelete;

    /** Absolute path of the scanned root; written into the generated script header. */
    private final Path sourceTree;

    /** Minimum file size in bytes to consider; smaller files are dropped before bucketing. 0 = no filter. */
    private final long minSizeBytes;

    /** Files dropped by the minSize filter; reported once at end of run. */
    private final LongAdder filteredCount = new LongAdder();
    private final LongAdder filteredBytes = new LongAdder();

    /** Phase 1 grouping: same-size paths share a bucket. */
    private final ConcurrentHashMap<Long, Queue<Path>> pathsBySize = new ConcurrentHashMap<>();

    /** Running totals for the composition-root headline. */
    private final LongAdder totalFiles = new LongAdder();
    private final LongAdder totalBytes = new LongAdder();

    /** Phase 1 wall time, captured in awaitAndReport for the script header. */
    private long phase1ElapsedMs = -1;

    /** Phase 2 wall time; populated by runPhase2. */
    private long phase2ElapsedMs = -1;

    /**
     * Wires the consumer to a queue and creates a fixed drainer pool. consumerThreads
     * must be at least 1; outPathRaw is the raw -Dout value (null is treated as
     * empty); hardDelete switches the generated script from mv-to-bin to rm;
     * sourceTree is the absolute root path written into the script header;
     * minSizeBytes is the minimum file size to consider (0 = no filter).
     */
    public DuplicateLocator(BlockingQueue<FileInfo> queue, int consumerThreads,
            String outPathRaw, boolean hardDelete, Path sourceTree, long minSizeBytes) {
        if (consumerThreads < 1) throw new IllegalArgumentException("consumerThreads must be >= 1");
        if (minSizeBytes < 0) throw new IllegalArgumentException("minSizeBytes must be >= 0");
        this.queue = queue;
        this.consumerThreads = consumerThreads;
        this.outPathRaw = outPathRaw == null ? "" : outPathRaw;
        this.hardDelete = hardDelete;
        this.sourceTree = sourceTree;
        this.minSizeBytes = minSizeBytes;
        this.pool = Executors.newFixedThreadPool(consumerThreads);
    }

    @Override public int consumerCount() { return consumerThreads; }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new PathFileInfo(
                path, attrs.size(), attrs.lastModifiedTime().toMillis());
    }

    @Override
    public void start() {
        for (int i = 0; i < consumerThreads; i++) pool.submit(this::consume);
    }

    /**
     * Drainer loop: bucket each PathFileInfo by size and exit on POISON. Exhaustive switch over
     * the sealed FileInfo hierarchy lets the compiler enforce that every variant is handled —
     * a new permitted subtype added to FileInfo will fail to compile here instead of being
     * silently dropped. TypeFileInfo is rejected explicitly because this consumer's factory()
     * only produces PathFileInfo; reaching that arm means the consumer was wired to a producer
     * of the wrong shape. Package-private so the unit test can drive it directly.
     */
    void consume() {
        try {
            while (true) {
                FileInfo f = queue.take();
                switch (f) {
                    case PathFileInfo p -> {
                        // Min-size filter: drop early so small files never enter bucketing or hashing.
                        // totalFiles/totalBytes count only files actually considered for duplication.
                        if (p.size() < minSizeBytes) {
                            filteredCount.increment();
                            filteredBytes.add(p.size());
                        } else {
                            totalFiles.increment();
                            totalBytes.add(p.size());
                            pathsBySize
                                    .computeIfAbsent(p.size(), k -> new ConcurrentLinkedQueue<>())
                                    .add(p.path());
                        }
                    }
                    case PoisonPill ignored -> { return; }
                    case TypeFileInfo ignored -> throw new IllegalStateException(
                            "DuplicateLocator received TypeFileInfo; its factory() produces only PathFileInfo");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void awaitAndReport(PrintStream out) throws InterruptedException {
        long t1 = System.nanoTime();
        pool.shutdown();
        if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
            throw new IllegalStateException("duplicate locator did not terminate within 1 hour");
        }
        phase1ElapsedMs = (System.nanoTime() - t1) / 1_000_000L;

        if (minSizeBytes > 0) {
            out.printf("Filtered (size < %s): %,d files (%s) — not considered.%n",
                    com.example.folderscanner.data.Format.humanBytes(minSizeBytes),
                    filteredCount.sum(),
                    com.example.folderscanner.data.Format.humanBytes(filteredBytes.sum()));
        }

        DuplicateReport report = runPhase2();

        // Nothing to quarantine - skip writing an empty script. The headline below
        // still prints zeros so the user sees the run completed and confirmed no
        // duplicates rather than wondering whether the consumer ran at all.
        if (report.groupCount() == 0) {
            out.println("Duplicates: none found. No script written.");
            return;
        }

        Path scriptPath = OutPathResolver.resolve(outPathRaw, "remove-duplicates.sh");
        try {
            ScriptWriter.write(scriptPath, sourceTree, report, hardDelete);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + scriptPath, e);
        }
        out.printf("Duplicates: %d groups, %d redundant files, %s recoverable.%n",
                report.groupCount(), report.redundantFileCount(),
                com.example.folderscanner.data.Format.humanBytes(report.redundantBytes()));
        out.printf("Wrote %s — INSPECT BEFORE RUNNING.%n", scriptPath.toAbsolutePath());
    }

    /**
     * Runs phase 2 inside a fresh ForkJoinPool sized to consumerThreads.
     * For each size group with at least two entries, computes the small
     * hash of the first SMALL_HASH_BYTES; same-small-hash subgroups still
     * holding 2+ entries then get full-hashed. Files of size 0 are dropped
     * (empty files trivially "duplicate" but quarantining them is rarely
     * useful). IO failures drop the offending path from its group.
     */
    private DuplicateReport runPhase2() {
        long t0 = System.nanoTime();
        List<DuplicateReport.Group> confirmed = new ArrayList<>();

        ForkJoinPool phase2Pool = new ForkJoinPool(consumerThreads);
        try {
            List<DuplicateReport.Group> groups = phase2Pool.submit(() ->
                pathsBySize.entrySet().parallelStream()
                    .filter(e -> e.getKey() > 0)                    // drop empty files
                    .filter(e -> e.getValue().size() >= 2)          // only size collisions
                    .flatMap(e -> confirmGroup(e.getKey(), new ArrayList<>(e.getValue())).stream())
                    .collect(Collectors.toList())
            ).join();
            confirmed.addAll(groups);
        } finally {
            phase2Pool.shutdown();
        }

        confirmed.sort(Comparator.<DuplicateReport.Group>comparingLong(
                g -> (g.paths().size() - 1L) * g.fileSizeBytes()).reversed());

        long groupCount = confirmed.size();
        long redundantFiles = confirmed.stream().mapToLong(g -> g.paths().size() - 1L).sum();
        long redundantBytes = confirmed.stream()
                .mapToLong(g -> (g.paths().size() - 1L) * g.fileSizeBytes()).sum();
        phase2ElapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        return new DuplicateReport(confirmed, groupCount, redundantFiles, redundantBytes,
                phase1ElapsedMs, phase2ElapsedMs);
    }

    /** Hash one same-size group; returns zero or more confirmed duplicate sub-groups. */
    private List<DuplicateReport.Group> confirmGroup(long size, List<Path> candidates) {
        // 1. small hash split
        Map<String, List<Path>> bySmall = new HashMap<>();
        for (Path p : candidates) {
            try {
                bySmall.computeIfAbsent(ContentHasher.smallHash(p), k -> new ArrayList<>()).add(p);
            } catch (IOException e) {
                System.err.println("skip (small-hash failed): " + p + " — " + e.getMessage());
            }
        }
        // 2. full hash for surviving subgroups
        List<DuplicateReport.Group> out = new ArrayList<>();
        for (List<Path> sub : bySmall.values()) {
            if (sub.size() < 2) continue;
            Map<String, List<Path>> byFull = new HashMap<>();
            for (Path p : sub) {
                try {
                    byFull.computeIfAbsent(ContentHasher.fullHash(p), k -> new ArrayList<>()).add(p);
                } catch (IOException e) {
                    System.err.println("skip (full-hash failed): " + p + " — " + e.getMessage());
                }
            }
            for (List<Path> group : byFull.values()) {
                if (group.size() < 2) continue;
                // Sort each surviving group lexicographically for deterministic "first kept" later.
                group.sort(Comparator.comparing(Path::toString));
                out.add(new DuplicateReport.Group(size, group));
            }
        }
        return out;
    }

    @Override public long totalFilesSeen() { return totalFiles.sum(); }
    @Override public long totalBytesSeen() { return totalBytes.sum(); }
}
