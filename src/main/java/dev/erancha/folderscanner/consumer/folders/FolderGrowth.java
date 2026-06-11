package dev.erancha.folderscanner.consumer.folders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One folder whose recursive subtree grew since the baseline: its prior bytes, current bytes, and
 * the growth percentage between them.
 *
 * {@link #since} is the pure day-over-day diff behind {@code --baseline}: it keeps only folders that
 * grew by strictly more than the threshold. A folder the baseline did not record (or recorded at zero
 * bytes) has no defined growth percentage, so {@code since} excludes it; {@link #appeared} surfaces
 * exactly those folders instead, so a large subtree that materializes overnight is reported rather
 * than dropped.
 */
public record FolderGrowth(Path path, long was, long now, double pct) {

    /**
     * Diffs each current folder against its baseline bytes, keeping those whose recursive size grew
     * by more than {@code thresholdPct} percent. Ranked by growth percent descending, path ascending
     * as the deterministic tiebreaker.
     */
    public static List<FolderGrowth> since(Map<Path, Long> baseline, List<FolderSize> current,
            double thresholdPct) {
        List<FolderGrowth> grown = new ArrayList<>();
        for (FolderSize row : current) {
            Long was = baseline.get(row.path());
            if (was == null || was == 0L)
                continue;
            double pct = (row.bytes() - was) * 100.0 / was;
            if (pct > thresholdPct)
                grown.add(new FolderGrowth(row.path(), was, row.bytes(), pct));
        }
        grown.sort(Comparator.comparingDouble(FolderGrowth::pct).reversed()
                .thenComparing(g -> g.path().toString()));
        return grown;
    }

    /**
     * The current folders that are new since the baseline: absent from it, or recorded there at zero
     * bytes. These have no defined growth percentage and so are excluded from {@link #since}; reported
     * on their own, ranked by current size descending, path ascending as the deterministic tiebreaker.
     */
    public static List<FolderSize> appeared(Map<Path, Long> baseline, List<FolderSize> current) {
        List<FolderSize> fresh = new ArrayList<>();
        for (FolderSize row : current) {
            Long was = baseline.get(row.path());
            if (was == null || was == 0L)
                fresh.add(row);
        }
        fresh.sort(Comparator.comparingLong(FolderSize::bytes).reversed()
                .thenComparing(fs -> fs.path().toString()));
        return fresh;
    }
}
