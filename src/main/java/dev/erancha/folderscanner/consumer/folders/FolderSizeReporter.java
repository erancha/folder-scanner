package dev.erancha.folderscanner.consumer.folders;

import dev.erancha.folderscanner.consumer.AbstractFileConsumer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reports the folders that hold the most space, recursively, ranked largest-first — the consumer
 * behind {@code --consumer=folders}.
 *
 * Hot path stays O(1) per file: each drainer adds a file only to its immediately-containing folder
 * (one map lookup), never walking ancestors. The ancestor roll-up runs once after the drain
 * ({@link #rollUp}), summing each folder's subtree into recursive totals for every ancestor up to
 * the scan root, then dropping folders below {@code --min-size-recursive} and ranking by size.
 */
public final class FolderSizeReporter extends AbstractFileConsumer<PathFileInfo> {

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

    // Per-folder tallies for the folder that DIRECTLY contains each file (no descendants). Keyed by
    // that immediate parent; the ancestor roll-up to recursive totals happens at report time.
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
        List<FolderSize> rows = rollUp(snapshotDirect(), root, minSizeRecursiveBytes);
        printRows(out, rows);
        if (!baselinePath.isEmpty())
            reportGrowth(out, rows);
    }

    /**
     * Diffs this run against the newest dated snapshot already in the baseline directory and prints
     * the growth and new-folder sections, then writes today's snapshot into that directory so older
     * days are retained and the next run compares against today. The first run (empty directory) only
     * seeds it. File I/O is wrapped unchecked so {@link #report} keeps the base class's non-throwing
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

    // Key: folder directly containing files. Value: {count, bytes} of those direct files only.
    private Map<Path, long[]> snapshotDirect() {
        Map<Path, long[]> direct = new HashMap<>();
        directCount.forEach((folder, adder) -> direct.computeIfAbsent(folder,
                k -> new long[2])[0] = adder.sum());
        directBytes.forEach((folder, adder) -> direct.computeIfAbsent(folder,
                k -> new long[2])[1] = adder.sum());
        return direct;
    }

    /**
     * Rolls the per-immediate-parent tallies up into recursive subtree totals for every ancestor
     * folder from each populated folder up to {@code root} inclusive, so a folder holding only
     * subfolders still gets the size of everything beneath it. Folders whose recursive byte total
     * is below {@code minBytes} are omitted; {@code root} is always present (even at zero). Rows are
     * ranked by bytes descending, path ascending as the deterministic tiebreaker.
     *
     * Pass-through chains are collapsed to their topmost link: a folder is dropped when its parent
     * has the identical recursive count and bytes, which happens only when that parent holds no
     * files of its own and no other subfolder (e.g. {@code .../resources} containing nothing but
     * {@code app}). The parent stands in for the whole chain, so the listing shows one row per
     * chain instead of a row for every redundant link.
     */
    static List<FolderSize> rollUp(Map<Path, long[]> direct, Path root, long minBytes) {
        Map<Path, long[]> recursive = new HashMap<>();
        for (Map.Entry<Path, long[]> e : direct.entrySet()) {
            long[] cb = e.getValue();
            for (Path cur = e.getKey(); cur != null; cur = cur.getParent()) {
                long[] acc = recursive.computeIfAbsent(cur, k -> new long[2]);
                acc[0] += cb[0];
                acc[1] += cb[1];
                if (cur.equals(root))
                    break;
            }
        }
        recursive.computeIfAbsent(root, k -> new long[2]);

        List<FolderSize> rows = new ArrayList<>();
        for (Map.Entry<Path, long[]> e : recursive.entrySet()) {
            Path folder = e.getKey();
            long[] cb = e.getValue();
            if (!folder.equals(root) && isPassThroughOf(recursive.get(folder.getParent()), cb))
                continue;
            if (folder.equals(root) || cb[1] >= minBytes) {
                rows.add(new FolderSize(folder, cb[0], cb[1]));
            }
        }
        rows.sort(Comparator.comparingLong(FolderSize::bytes).reversed()
                .thenComparing(fs -> fs.path().toString()));
        return rows;
    }

    // True when {@code child}'s recursive totals exactly match its parent's, marking the child as a
    // redundant pass-through link the parent already represents. Parent totals can never be smaller
    // than a child's, so equality means the parent adds nothing of its own.
    private static boolean isPassThroughOf(long[] parent, long[] child) {
        return parent != null && parent[0] == child[0] && parent[1] == child[1];
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
