package dev.erancha.folderscanner.consumer.folders;

import dev.erancha.folderscanner.data.Format;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Renders the two day-over-day delta sections — folders that grew past the threshold and folders new
 * since the prior snapshot — from a baseline's recorded sizes and a current set of folder sizes.
 *
 * Shared by both producers of these sections so the format is defined once: the scan path
 * ({@link FolderSizeReporter}) diffing against the newest prior snapshot, and the on-demand compare
 * path ({@link SnapshotComparer}) diffing two stored snapshots.
 */
final class GrowthReport {

    private GrowthReport() {}

    /**
     * Prints the growth section followed by the new-folders section. {@code baselineTime} dates both
     * section headers; {@code was} is the baseline's recursive bytes per folder; {@code current} is
     * the set being compared against it.
     */
    static void print(PrintStream out, java.time.Instant baselineTime, double thresholdPct,
            Map<Path, Long> was, List<FolderSize> current) {
        printGrowth(out, baselineTime, thresholdPct, FolderGrowth.since(was, current, thresholdPct));
        printNew(out, baselineTime, FolderGrowth.appeared(was, current));
    }

    private static void printGrowth(PrintStream out, java.time.Instant baselineTime,
            double thresholdPct, List<FolderGrowth> grown) {
        LocalDate since = LocalDate.ofInstant(baselineTime, ZoneId.systemDefault());
        out.printf("%nFolder growth since %s (> %s%%):%n", since, formatThreshold(thresholdPct));
        if (grown.isEmpty()) {
            out.printf("  none%n");
            return;
        }
        out.printf("%12s %12s %14s %7s  %s%n", "was", "now", "+delta", "+pct", "folder");
        for (FolderGrowth g : grown) {
            out.printf("%12s %12s %14s %6.1f%%  %s%n", Format.humanBytes(g.was()),
                    Format.humanBytes(g.now()), "+" + Format.humanBytes(g.now() - g.was()), g.pct(),
                    g.path());
        }
    }

    // New folders have no growth percentage, so they get their own section instead of being dropped.
    private static void printNew(PrintStream out, java.time.Instant baselineTime,
            List<FolderSize> appeared) {
        LocalDate since = LocalDate.ofInstant(baselineTime, ZoneId.systemDefault());
        out.printf("%nNew folders since %s:%n", since);
        if (appeared.isEmpty()) {
            out.printf("  none%n");
            return;
        }
        out.printf("%12s %10s  %s%n", "bytes", "count", "folder");
        for (FolderSize f : appeared) {
            out.printf("%12s %10d  %s%n", Format.humanBytes(f.bytes()), f.count(), f.path());
        }
    }

    // Drop a trailing ".0" so the common whole-percent threshold reads "> 10%", not "> 10.0%".
    private static String formatThreshold(double thresholdPct) {
        return thresholdPct == Math.floor(thresholdPct)
                ? String.valueOf((long) thresholdPct)
                : String.valueOf(thresholdPct);
    }
}
