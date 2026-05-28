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
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Locates files with identical content and emits a shell script that quarantines or deletes the
 * redundant copies.
 *
 * Three phases: (1 - consume) Concurrent with the scan: group files by size. Multiple drainer
 * threads — per-message work is tiny but high-frequency; one drainer would bottleneck before the
 * producer's queue.put() ever blocks. (2 - runPhase2) After POISON: only buckets with >= 2 files
 * are examined. Small-hash (first 4 KB) narrows each bucket; survivors are then full-hashed, so
 * most large files are never read in full. (3) ScriptWriter emits the shell script for the user to
 * inspect.
 */
public final class DuplicateLocator implements FileConsumer {

    private final BlockingQueue<FileInfo> queue;
    private final ThreadPoolExecutor drainersPool;
    private final String outPathRaw;
    private final boolean hardDelete;
    private final Path sourceTree;

    // One bucket per file size: the key (Long) is the file size; the value (Queue<Path>) holds the
    // paths of all files with that size.
    private final ConcurrentHashMap<Long, Queue<Path>> pathsBySize = new ConcurrentHashMap<>();
    private final LongAdder totalFiles = new LongAdder();
    private final LongAdder totalBytes = new LongAdder();

    private long phase1StartNs;
    private long phase1ElapsedMs;
    private long phase2ElapsedMs;

    public DuplicateLocator(BlockingQueue<FileInfo> queue, int consumerThreads, String outPathRaw,
            boolean hardDelete, Path sourceTree) {
        if (consumerThreads < 1)
            throw new IllegalArgumentException("consumerThreads must be >= 1");
        this.queue = queue;
        this.outPathRaw = outPathRaw == null ? "" : outPathRaw;
        this.hardDelete = hardDelete;
        this.sourceTree = sourceTree;
        this.drainersPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(consumerThreads);
    }

    @Override
    public int consumerCount() {
        return drainersPool.getCorePoolSize();
    }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new PathFileInfo(path, attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    @Override
    public void start() {
        phase1StartNs = System.nanoTime();
        for (int i = 0, n = drainersPool.getCorePoolSize(); i < n; i++) {
            drainersPool.submit(this::consume);
        }
    }

    /**
     * Drainer loop: takes messages from the queue shared from the producer and adds each path to
     * its size-bucket. Exits on PoisonPill; any other variant means a producer wiring bug.
     *
     * Switch is over the sealed FileInfo type — compiler enforces exhaustiveness, so any future
     * variant added to FileInfo becomes a compile error here until handled.
     */
    void consume() {
        try {
            while (true) {
                FileInfo f = queue.take();
                switch (f) {
                case PathFileInfo p -> {
                    totalFiles.increment();
                    totalBytes.add(p.size());
                    // Many drainers may add to the same bucket at once; ConcurrentLinkedQueue lets
                    // those add()s run in parallel instead of one thread waiting for another.
                    pathsBySize.computeIfAbsent(p.size(), k -> new ConcurrentLinkedQueue<>())
                            .add(p.path());
                }
                case PoisonPill ignored -> {
                    return;
                }
                case TypeFileInfo ignored -> throw new IllegalStateException(
                        "DuplicateLocator received TypeFileInfo; its factory() produces only "
                                + "PathFileInfo");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void awaitAndReport(PrintStream out) throws InterruptedException {
        drainersPool.shutdown();
        if (!drainersPool.awaitTermination(1, TimeUnit.HOURS)) {
            throw new IllegalStateException("duplicate locator did not terminate within 1 hour");
        }
        // All drainers have exited; record phase-1 elapsed.
        phase1ElapsedMs = (System.nanoTime() - phase1StartNs) / 1_000_000L;

        // Phase 2: confirm size-collisions.
        DuplicateReport report = runPhase2();

        if (report.groupCount() == 0) {
            out.println("Duplicates: none found. No script written.");
            return;
        }

        // Phase 3: write the script for the user to inspect.
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

    // Phase 2: confirm size-collisions by hashing (the only phase that reads file content).
    private DuplicateReport runPhase2() {
        long t0 = System.nanoTime();
        List<DuplicateReport.Group> confirmed = new ArrayList<>();

        // Phase 2 is IO-bound (hashing files); size the pool from CPU count. (TODO: optimize?)
        int phase2Parallelism = Runtime.getRuntime().availableProcessors() * 2;
        try (ForkJoinPool phase2Pool = new ForkJoinPool(phase2Parallelism)) {
            List<DuplicateReport.Group> groups = phase2Pool.submit(() -> pathsBySize.entrySet()
                    .parallelStream().filter(e -> e.getKey() > 0) // drop empty files
                    .filter(e -> e.getValue().size() >= 2) // only size collisions
                    .flatMap(e -> confirmGroup(e.getKey(), new ArrayList<>(e.getValue())).stream())
                    .collect(Collectors.toList())).join();
            confirmed.addAll(groups);
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

    private List<DuplicateReport.Group> confirmGroup(long size, List<Path> candidates) {
        Map<String, List<Path>> bySmall = new HashMap<>();
        for (Path p : candidates) {
            try {
                bySmall.computeIfAbsent(ContentHasher.smallHash(p), k -> new ArrayList<>()).add(p);
            } catch (IOException e) {
                System.err.println("skip (small-hash failed): " + p + " — " + e.getMessage());
            }
        }
        List<DuplicateReport.Group> out = new ArrayList<>();
        for (List<Path> sub : bySmall.values()) {
            if (sub.size() < 2)
                continue;
            Map<String, List<Path>> byFull = new HashMap<>();
            for (Path p : sub) {
                try {
                    byFull.computeIfAbsent(ContentHasher.fullHash(p), k -> new ArrayList<>())
                            .add(p);
                } catch (IOException e) {
                    System.err.println("skip (full-hash failed): " + p + " — " + e.getMessage());
                }
            }
            for (List<Path> group : byFull.values()) {
                if (group.size() < 2)
                    continue;
                // Lexicographic sort so the keeper (index 0 in the script) is deterministic.
                group.sort(Comparator.comparing(Path::toString));
                out.add(new DuplicateReport.Group(size, group));
            }
        }
        return out;
    }

    @Override
    public long totalFilesSeen() {
        return totalFiles.sum();
    }

    @Override
    public long totalBytesSeen() {
        return totalBytes.sum();
    }

    /** Phase-1 wall-clock in ms; valid after awaitAndReport returns. Package-private for tests. */
    long phase1ElapsedMs() {
        return phase1ElapsedMs;
    }
}
