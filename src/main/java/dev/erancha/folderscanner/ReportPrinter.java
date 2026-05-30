package dev.erancha.folderscanner;

import dev.erancha.folderscanner.data.Format;
import dev.erancha.folderscanner.producer.FileExtensions;
import java.io.PrintStream;

/**
 * Renders the end-of-run text report so the orchestration in {@link Main#run} stays free of
 * presentation logic and the formatting becomes assertable in isolation. Holds two concerns:
 * the per-filter skip tallies (size / extension / inaccessible) and the headline done line.
 */
public final class ReportPrinter {

    private ReportPrinter() {
    }

    /**
     * Immutable snapshot of producer-side filter tallies. Counts and byte sums are zero for an
     * inactive filter; {@code includeExtensions} is {@link FileExtensions.IncludeSet#ALL} when no
     * extension whitelist was given.
     */
    public record FilterTally(long minSizeBytes, long sizeSkippedCount, long sizeSkippedBytes,
            FileExtensions.IncludeSet includeExtensions, long extSkippedCount, long extSkippedBytes,
            long inaccessibleDirs, long inaccessibleFiles) {
    }

    /**
     * Writes one line per active filter. Each section is suppressed when its filter was inactive,
     * so a run with no filters emits nothing.
     */
    public static void printFilterSummary(PrintStream out, FilterTally tally) {
        if (tally.minSizeBytes() > 0) {
            out.printf("%nSkipped (size < %s): %,d files (%s).%n",
                    Format.humanBytes(tally.minSizeBytes()), tally.sizeSkippedCount(),
                    Format.humanBytes(tally.sizeSkippedBytes()));
        }
        if (!tally.includeExtensions().isAll()) {
            out.printf("%nSkipped (extension not in %s): %,d files (%s).%n",
                    tally.includeExtensions().displayList(), tally.extSkippedCount(),
                    Format.humanBytes(tally.extSkippedBytes()));
        }
        if (tally.inaccessibleDirs() > 0 || tally.inaccessibleFiles() > 0) {
            out.printf(
                    "%nInaccessible (permission denied or IO error): %,d directories, %,d files "
                            + "— their contents are absent from this report.%n",
                    tally.inaccessibleDirs(), tally.inaccessibleFiles());
        }
    }

    /** Headline summary: wall-clock elapsed, files seen by the consumer, and their total bytes. */
    public static void printDone(PrintStream out, long elapsedMs, long totalFiles, long totalBytes) {
        out.printf("%nDone in %s. Files=%,d  TotalBytes=%s%n", Format.formatElapsed(elapsedMs),
                totalFiles, Format.humanBytes(totalBytes));
    }
}
