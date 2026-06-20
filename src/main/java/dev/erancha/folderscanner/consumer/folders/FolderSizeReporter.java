package dev.erancha.folderscanner.consumer.folders;

import dev.erancha.folderscanner.consumer.AbstractFileConsumer;
import dev.erancha.folderscanner.consumer.folders.growth.BaselineSnapshot;
import dev.erancha.folderscanner.consumer.folders.growth.GrowthReport;
import dev.erancha.folderscanner.consumer.folders.growth.SnapshotHistory;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.Format;
import dev.erancha.folderscanner.data.PathFileInfo;
import dev.erancha.folderscanner.producer.FileInfoFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reports the folders that hold the most space, recursively, ranked largest-first — the consumer
 * behind --consumer=folders.
 *
 * Flow, end to end:
 *   1. Drain (accept) — each drainer thread adds a file only to the folder that directly contains
 *      it. This is the hot path, so it stays O(1) per file: one map lookup, never an ancestor walk.
 *   2. Freeze (directTallies) — once draining is done, the concurrent counters are snapshotted into
 *      a plain folder-to-Tally map of immediate-child totals.
 *   3. Roll up (rollUp) — each folder's tally is added into every ancestor up to the scan root,
 *      turning immediate-child totals into whole-subtree totals; then small folders are dropped,
 *      redundant pass-through links collapse, and the rest rank by size.
 *   4. Print (printRows), then optionally diff against the prior snapshot (reportGrowth).
 *
 * Worked example — the only scanned files live in .../jmeter/results:
 *
 *   immediate:  /mnt/c/projects/.../jmeter/results   count 4, 2.44 GB
 *
 *   recursive:  /mnt/c/projects                       4, 2.44 GB
 *               /mnt/c/projects/JAVA                  4, 2.44 GB   identical all the way down,
 *               /mnt/c/projects/.../jmeter            4, 2.44 GB   because the files sit only in
 *               /mnt/c/projects/.../jmeter/results    4, 2.44 GB   the single leaf
 *
 *   reported:   /mnt/c/projects                       4, 2.44 GB   scan root, kept as the anchor
 *               /mnt/c/projects/.../jmeter/results    4, 2.44 GB   deepest link = the real folder
 *
 * The intermediate JAVA and jmeter links carry the identical total and add nothing of their own, so
 * they collapse away and the report points straight at results — the folder you would actually act
 * on (delete, inspect).
 */
public final class FolderSizeReporter extends AbstractFileConsumer<PathFileInfo> {

    /** A folder's file count and byte total — for either its immediate children or its whole subtree. */
    record Tally(long count, long bytes) {
        static final Tally EMPTY = new Tally(0, 0);

        Tally plus(Tally other) {
            return new Tally(count + other.count, bytes + other.bytes);
        }
    }

    // Recursive byte total below which a folder is omitted from the report (the scan root is always
    // kept regardless, as the orientation anchor / grand total).
    private final long minSizeRecursiveBytes;

    // Highest folder reported; the roll-up stops here so nothing above the scan target is summed.
    private final Path root;

    // Empty disables day-over-day growth reporting; otherwise the directory of dated snapshots this
    // run diffs against (newest prior) and writes today's snapshot into.
    private final String baselinePath;

    // Percent a folder must grow past (strictly) to appear in the growth section.
    private final double growthThresholdPct;

    // Counters for the folder that DIRECTLY contains each file, accumulated concurrently by the
    // drainers. Kept as two maps so each adder is a single hot-path increment; merged into one Tally
    // per folder by directTallies() once draining is done.
    private final ConcurrentHashMap<Path, LongAdder> directCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, LongAdder> directBytes = new ConcurrentHashMap<>();

    public FolderSizeReporter(BlockingQueue<FileInfo> queue, int consumerThreads, Path root,
            long minSizeRecursiveBytes, String baselinePath, double growthThresholdPct) {
        super(queue, consumerThreads, "folder sizes", PathFileInfo.class);
        this.root = root;
        this.minSizeRecursiveBytes = minSizeRecursiveBytes;
        this.baselinePath = baselinePath;
        this.growthThresholdPct = growthThresholdPct;
    }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new PathFileInfo(path, attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    @Override
    protected void accept(PathFileInfo p) {
        Path parent = p.path().getParent();
        if (parent == null)
            return; // a scanned file always sits under root, so this is unreachable in practice
        directCount.computeIfAbsent(parent, k -> new LongAdder()).increment();
        directBytes.computeIfAbsent(parent, k -> new LongAdder()).add(p.size());
    }

    @Override
    protected void report(PrintStream out) {
        List<FolderSize> rows = rollUp(directTallies(), root, minSizeRecursiveBytes);
        printRows(out, rows);
        if (!baselinePath.isEmpty())
            reportGrowth(out, rows);
    }

    /**
     * Diffs this run against the newest dated snapshot already in the baseline directory and prints
     * the growth and new-folder sections, then writes today's snapshot into that directory so older
     * days are retained and the next run compares against today. The first run (empty directory) only
     * seeds it. File I/O is wrapped unchecked so report keeps the base class's non-throwing
     * contract; Main surfaces it as one error line.
     */
    private void reportGrowth(PrintStream out, List<FolderSize> current) {
        SnapshotHistory history = new SnapshotHistory(Paths.get(baselinePath));
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        try {
            Optional<Path> prior = history.priorSnapshot(today);
            if (prior.isPresent()) {
                BaselineSnapshot baseline = BaselineSnapshot.read(prior.get());
                GrowthReport.print(out, baseline.timestamp(), growthThresholdPct,
                        baseline.bytesByFolder(), current);
            } else {
                out.printf("%nBaseline written (%,d folders); no prior baseline to compare.%n",
                        current.size());
            }
            BaselineSnapshot.write(history.targetFile(today), Instant.now(), current);
        } catch (IOException e) {
            throw new UncheckedIOException("baseline " + baselinePath, e);
        }
    }

    // Freezes the two concurrent counter maps into one immutable Tally per folder that directly
    // holds files. Both maps share the same key set (accept() always touches both), so iterating one
    // and reading the other is complete.
    private Map<Path, Tally> directTallies() {
        Map<Path, Tally> tallies = new HashMap<>();
        directBytes.forEach((folder, bytes) ->
                tallies.put(folder, new Tally(directCount.get(folder).sum(), bytes.sum())));
        return tallies;
    }

    /**
     * Turns the immediate-child tallies into recursive subtree totals — each folder's tally is added
     * into every ancestor up to root inclusive, so a folder holding only subfolders still gets the
     * size of everything beneath it — then returns the reportable rows: folders below minBytes
     * dropped, redundant pass-through links collapsed, ranked by bytes descending with path ascending
     * as the tiebreaker. root is always present (even at zero) as the anchor.
     *
     * A pass-through link is a folder whose recursive total exactly equals one of its children's — it
     * holds no files of its own and no second subfolder, so it adds nothing the child does not already
     * show (e.g. a resources folder containing nothing but app). Such a folder is dropped in favour of
     * its deeper child, so a chain a/b/c/results reports only results: the lowest folder that actually
     * holds the bytes, i.e. the one to act on.
     */
    static List<FolderSize> rollUp(Map<Path, Tally> immediate, Path root, long minBytes) {
        Map<Path, Tally> subtreeTotals = new HashMap<>();
        for (Map.Entry<Path, Tally> e : immediate.entrySet()) {
            Tally tally = e.getValue();
            for (Path folder = e.getKey(); folder != null; folder = folder.getParent()) {
                subtreeTotals.merge(folder, tally, Tally::plus);
                if (folder.equals(root))
                    break;
            }
        }
        subtreeTotals.putIfAbsent(root, Tally.EMPTY);

        // A folder whose subtree total equals one of its children's is a redundant pass-through the
        // deeper child already represents; mark it for removal. (root never qualifies: its parent is
        // above the scan and so absent from the map, and it is kept unconditionally below anyway.)
        Set<Path> redundantAncestors = new HashSet<>();
        subtreeTotals.forEach((folder, total) -> {
            Path parent = folder.getParent();
            if (total.equals(subtreeTotals.get(parent)))
                redundantAncestors.add(parent);
        });

        List<FolderSize> rows = new ArrayList<>();
        subtreeTotals.forEach((folder, total) -> {
            boolean isRoot = folder.equals(root);
            if (isRoot || (!redundantAncestors.contains(folder) && total.bytes() >= minBytes))
                rows.add(new FolderSize(folder, total.count(), total.bytes()));
        });
        rows.sort(Comparator.comparingLong(FolderSize::bytes).reversed()
                .thenComparing(fs -> fs.path().toString()));
        return rows;
    }

    private void printRows(PrintStream out, List<FolderSize> rows) {
        out.printf("%nFolders by recursive size (%s):%n", minSizeRecursiveBytes > 0
                ? ">= " + Format.humanBytes(minSizeRecursiveBytes)
                : "all");
        out.printf("%12s %10s  %s%n", "bytes", "count", "folder");
        for (FolderSize r : rows) {
            out.printf("%12s %10d  %s%n", Format.humanBytes(r.bytes()), r.count(), r.path());
        }
        out.printf("%nListed %,d folders (%s total).%n", rows.size(),
                Format.humanBytes(totalBytes.sum()));
    }
}
