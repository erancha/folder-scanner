package dev.erancha.folderscanner.consumer.folders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure baseline-to-current growth diff: {@link FolderGrowth#since} keeps only the
 * folders whose recursive bytes grew by strictly more than the threshold percentage, skipping
 * folders absent from the baseline or with a zero baseline (growth from zero has no percentage).
 */
final class FolderGrowthTest {

    private static final long MB = 1024L * 1024L;

    private static List<FolderSize> current(FolderSize... rows) {
        return List.of(rows);
    }

    @Test
    void since_keeps_only_folders_growing_more_than_the_threshold() {
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/grew"), 100 * MB);  // +25% -> kept at threshold 10
        baseline.put(Paths.get("/mnt/c/flat"), 100 * MB);  // +5%  -> dropped

        List<FolderGrowth> grown = FolderGrowth.since(baseline, current(
                new FolderSize(Paths.get("/mnt/c/grew"), 1, 125 * MB),
                new FolderSize(Paths.get("/mnt/c/flat"), 1, 105 * MB)), 10.0);

        assertEquals(List.of(new FolderGrowth(Paths.get("/mnt/c/grew"), 100 * MB, 125 * MB, 25.0)),
                grown);
    }

    @Test
    void since_excludes_growth_exactly_at_the_threshold() {
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/edge"), 100 * MB);  // +10% exactly -> excluded (strict >)

        List<FolderGrowth> grown = FolderGrowth.since(baseline, current(
                new FolderSize(Paths.get("/mnt/c/edge"), 1, 110 * MB)), 10.0);

        assertEquals(List.of(), grown);
    }

    @Test
    void since_skips_folders_absent_from_the_baseline() {
        // A folder with no baseline entry is new growth from zero, which has no defined percentage.
        List<FolderGrowth> grown = FolderGrowth.since(new HashMap<>(), current(
                new FolderSize(Paths.get("/mnt/c/brandnew"), 1, 500 * MB)), 10.0);

        assertEquals(List.of(), grown);
    }

    @Test
    void since_skips_folders_whose_baseline_was_zero() {
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/wasempty"), 0L);  // division by zero has no percentage

        List<FolderGrowth> grown = FolderGrowth.since(baseline, current(
                new FolderSize(Paths.get("/mnt/c/wasempty"), 1, 500 * MB)), 10.0);

        assertEquals(List.of(), grown);
    }

    @Test
    void appeared_returns_folders_absent_from_the_baseline_ranked_by_size() {
        // old/ existed at baseline; the other two are new. since() drops new folders as growth from
        // zero, so appeared() is what surfaces them.
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/old"), 100 * MB);

        List<FolderSize> appeared = FolderGrowth.appeared(baseline, current(
                new FolderSize(Paths.get("/mnt/c/old"), 1, 150 * MB),
                new FolderSize(Paths.get("/mnt/c/small-new"), 1, 20 * MB),
                new FolderSize(Paths.get("/mnt/c/big-new"), 7, 500 * MB)));

        assertEquals(List.of(
                new FolderSize(Paths.get("/mnt/c/big-new"), 7, 500 * MB),
                new FolderSize(Paths.get("/mnt/c/small-new"), 1, 20 * MB)),
                appeared);
    }

    @Test
    void appeared_treats_a_zero_baseline_as_new() {
        // A zero-byte baseline has no growth percentage, so since() skips it; appeared() reports it.
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/wasempty"), 0L);

        List<FolderSize> appeared = FolderGrowth.appeared(baseline, current(
                new FolderSize(Paths.get("/mnt/c/wasempty"), 3, 500 * MB)));

        assertEquals(List.of(new FolderSize(Paths.get("/mnt/c/wasempty"), 3, 500 * MB)), appeared);
    }

    @Test
    void appeared_excludes_folders_already_present_in_the_baseline() {
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/known"), 100 * MB);

        List<FolderSize> appeared = FolderGrowth.appeared(baseline, current(
                new FolderSize(Paths.get("/mnt/c/known"), 1, 300 * MB)));

        assertEquals(List.of(), appeared);
    }

    @Test
    void since_ranks_by_growth_percent_descending_then_path_ascending() {
        Map<Path, Long> baseline = new HashMap<>();
        baseline.put(Paths.get("/mnt/c/a"), 100L);  // +100%
        baseline.put(Paths.get("/mnt/c/b"), 100L);  // +50%
        baseline.put(Paths.get("/mnt/c/c"), 100L);  // +50% (tie with b, path after b)

        List<FolderGrowth> grown = FolderGrowth.since(baseline, current(
                new FolderSize(Paths.get("/mnt/c/b"), 1, 150L),
                new FolderSize(Paths.get("/mnt/c/a"), 1, 200L),
                new FolderSize(Paths.get("/mnt/c/c"), 1, 150L)), 10.0);

        assertEquals(List.of(
                new FolderGrowth(Paths.get("/mnt/c/a"), 100L, 200L, 100.0),
                new FolderGrowth(Paths.get("/mnt/c/b"), 100L, 150L, 50.0),
                new FolderGrowth(Paths.get("/mnt/c/c"), 100L, 150L, 50.0)),
                grown);
    }
}
