package dev.erancha.folderscanner.consumer.folders.growth;

import dev.erancha.folderscanner.consumer.folders.FolderSize;
import dev.erancha.folderscanner.data.Format;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Renders the two day-over-day delta sections — folders that grew past the threshold and folders new
 * since the prior snapshot — from a baseline's recorded sizes and a current set of folder sizes.
 *
 * Shared by both producers of these sections so the format is defined once: the scan path
 * (FolderSizeReporter) diffing against the newest prior snapshot, and the on-demand compare path
 * (SnapshotComparer) diffing two stored snapshots.
 */
public final class GrowthReport {

    private GrowthReport() {}

    /**
     * Prints the growth section followed by the new-folders section. baselineTime dates both section
     * headers; baselineBytes is the prior recursive bytes per folder; current is the set being
     * compared against it.
     */
    public static void print(PrintStream out, Instant baselineTime, double thresholdPct,
            Map<Path, Long> baselineBytes, List<FolderSize> current) {
        printGrowth(out, baselineTime, thresholdPct,
                FolderGrowth.since(baselineBytes, current, thresholdPct));
        printNew(out, baselineTime, FolderGrowth.appeared(baselineBytes, current));
    }

    private static void printGrowth(PrintStream out, Instant baselineTime,
            double thresholdPct, List<FolderGrowth> grown) {
        LocalDate baselineDate = LocalDate.ofInstant(baselineTime, ZoneId.systemDefault());
        out.printf("%nFolder growth since %s (> %s%%):%n", baselineDate, formatThreshold(thresholdPct));
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
    private static void printNew(PrintStream out, Instant baselineTime,
            List<FolderSize> appeared) {
        LocalDate baselineDate = LocalDate.ofInstant(baselineTime, ZoneId.systemDefault());
        out.printf("%nNew folders since %s:%n", baselineDate);
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
