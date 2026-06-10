package dev.erancha.folderscanner.consumer.duplicates;

import dev.erancha.folderscanner.consumer.AbstractFileConsumer;
import dev.erancha.folderscanner.consumer.shell.OutPathResolver;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.PathFileInfo;
import dev.erancha.folderscanner.producer.FileInfoFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
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
public final class DuplicateLocator extends AbstractFileConsumer<PathFileInfo> {

    private final String outPathRaw;
    private final boolean hardDelete;
    private final Path sourceTree;

    // One bucket per file size: the key (Long) is the file size; the value (Queue<Path>) holds the
    // paths of all files with that size.
    private final ConcurrentHashMap<Long, Queue<Path>> pathsBySize = new ConcurrentHashMap<>();

    private long phase1StartNs;
    private long phase1ElapsedMs;
    private long phase2ElapsedMs;

    public DuplicateLocator(BlockingQueue<FileInfo> queue, int consumerThreads, String outPathRaw,
            boolean hardDelete, Path sourceTree) {
        super(queue, consumerThreads, "duplicate locator", PathFileInfo.class);
        this.outPathRaw = outPathRaw == null ? "" : outPathRaw;
        this.hardDelete = hardDelete;
        this.sourceTree = sourceTree;
    }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new PathFileInfo(path, attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    @Override
    public void start() {
        phase1StartNs = System.nanoTime();
        super.start();
    }

    @Override
    protected void accept(PathFileInfo p) {
        pathsBySize.computeIfAbsent(p.size(), k -> new ConcurrentLinkedQueue<>()).add(p.path());
    }

    @Override
    protected void report(PrintStream out) {
        phase1ElapsedMs = (System.nanoTime() - phase1StartNs) / 1_000_000L;

        DuplicateReport report = runPhase2();

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
                dev.erancha.folderscanner.data.Format.humanBytes(report.redundantBytes()));
        out.printf("Wrote %s — INSPECT BEFORE RUNNING.%n", scriptPath.toAbsolutePath());
    }

    // The only phase that reads file content.
    private DuplicateReport runPhase2() {
        long t0 = System.nanoTime();
        List<DuplicateReport.Group> confirmed = new ArrayList<>();

        // Hashing is mixed disk-read + CPU work — 2×NCPU lets some workers wait on disk while
        // others hash. Intentionally decoupled from --consumers: that flag sizes the phase-1
        // drainer pool, whose per-message work is trivial and queue-bound, not disk-bound;
        // one knob would force two unrelated workloads to share a sizing. A fresh ForkJoinPool
        // (rather than commonPool()) is required for that 2×NCPU sizing: commonPool is fixed
        // at NCPU and shared process-wide, so reusing it would both cap our parallelism and
        // leak phase-2 work onto unrelated callers.
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

    List<DuplicateReport.Group> confirmGroup(long size, List<Path> candidates) {
        Map<String, List<Path>> bySmall = new HashMap<>();
        for (Path p : candidates) {
            try {
                bySmall.computeIfAbsent(ContentHasher.smallHash(p), k -> new ArrayList<>()).add(p);
            } catch (IOException e) {
                logger.debug("skip (small-hash failed): {} — {}", p, e.getMessage());
            }
        }
        List<DuplicateReport.Group> out = new ArrayList<>();
        for (List<Path> sub : bySmall.values()) {
            if (sub.size() < 2)
                continue;
            // For files no larger than the small-hash window, smallHash already digested every
            // byte, so the bucket is byte-for-byte definitive; a full re-read would recompute an
            // identical SHA-256. Skip the second pass and use the small-hash bucket directly.
            List<List<Path>> contentGroups;
            if (size <= ContentHasher.SMALL_HASH_BYTES) {
                contentGroups = List.of(sub);
            } else {
                Map<String, List<Path>> byFull = new HashMap<>();
                for (Path p : sub) {
                    try {
                        byFull.computeIfAbsent(ContentHasher.fullHash(p), k -> new ArrayList<>())
                                .add(p);
                    } catch (IOException e) {
                        logger.debug("skip (full-hash failed): {} — {}", p, e.getMessage());
                    }
                }
                contentGroups = new ArrayList<>(byFull.values());
            }
            for (List<Path> group : contentGroups) {
                if (group.size() < 2)
                    continue;
                List<Path> distinct = keepOneNamePerInode(group);
                // A group that collapses to a single inode is just hardlinks with no independent
                // copy: nothing is reclaimable, so it is not a duplicate set.
                if (distinct.size() < 2)
                    continue;
                // Lexicographic sort so the keeper (index 0 in the script) is deterministic.
                distinct.sort(Comparator.comparing(Path::toString));
                out.add(new DuplicateReport.Group(size, distinct));
            }
        }
        return out;
    }

    /**
     * Drops hardlink aliases from a same-content group, keeping one name per physical file.
     *
     * Example: a.iso is a real file and b.iso is a hardlink to it, so both names address the same
     * inode and the same bytes. The input [a.iso, b.iso] returns just [a.iso] — one name per inode
     * (lexicographically first, for a deterministic keeper). b.iso is dropped because deleting a
     * hardlink frees no space and removes a path the user may rely on.
     *
     * An independent copy lives on its own inode and is kept as a genuine duplicate. A path whose
     * filesystem reports no fileKey is keyed by itself, so distinct names never collapse together.
     */
    private List<Path> keepOneNamePerInode(List<Path> sameContent) {
        // Key: inode identity (fileKey), or the path itself when no fileKey is available.
        // Value: the lexicographically first name seen for that inode.
        Map<Object, Path> keptNameByInode = new HashMap<>();
        for (Path p : sameContent) {
            keptNameByInode.merge(inodeKey(p), p, (kept,
                    other) -> kept.toString().compareTo(other.toString()) <= 0 ? kept : other);
        }
        return new ArrayList<>(keptNameByInode.values());
    }

    private Object inodeKey(Path p) {
        try {
            Object key = Files.readAttributes(p, BasicFileAttributes.class).fileKey();
            if (key != null)
                return key;
        } catch (IOException e) {
            logger.debug("inode lookup failed, treating as distinct: {} — {}", p, e.getMessage());
        }
        return p;
    }

    /**
     * Phase-1 wall-clock in ms; valid after awaitAndReport returns. Package-private for the testing
     * framework.
     */
    long phase1ElapsedMs() {
        return phase1ElapsedMs;
    }
}
