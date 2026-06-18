package dev.erancha.folderscanner.consumer.folders;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * The on-demand compare path behind {@code --compare=FROM,TO}: diffs two stored dated snapshots from
 * the baseline directory and renders the growth / new-folder report between them, without scanning the
 * filesystem. Lets a user backtrack any pair of recorded days instead of only the most recent run.
 */
public final class SnapshotComparer {

    private SnapshotComparer() {}

    /**
     * Reads the {@code from} and {@code to} snapshots from {@code dir} and prints the diff of
     * {@code to} against {@code from} (the same growth and new-folder sections a scan produces). A
     * requested date with no snapshot file fails with a message naming that date.
     */
    public static void compare(Path dir, LocalDate from, LocalDate to, double thresholdPct,
            PrintStream out) throws IOException {
        SnapshotHistory history = new SnapshotHistory(dir);
        BaselineSnapshot baseline = readRequired(history.fileFor(from), from);
        BaselineSnapshot current = readRequired(history.fileFor(to), to);
        GrowthReport.print(out, baseline.timestamp(), thresholdPct, baseline.bytesByFolder(),
                current.rows());
    }

    private static BaselineSnapshot readRequired(Path file, LocalDate date) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("no snapshot for " + date + " at " + file);
        }
        return BaselineSnapshot.read(file);
    }
}
