package dev.erancha.folderscanner.consumer.folders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A directory of dated folder-size snapshots behind {@code --baseline}: one file per run named
 * {@code YYYY-MM-DD.tsv}. Owns that naming convention so the scan path can find the prior day's
 * snapshot to diff against and resolve today's write target, and the compare path can address any
 * stored day by date.
 *
 * Older days are never overwritten — only the file for the current day is rewritten on a same-day
 * rerun — so the directory accumulates a backtrackable history of recursive folder sizes.
 */
public final class SnapshotHistory {

    private static final String SUFFIX = ".tsv";

    private final Path dir;

    public SnapshotHistory(Path dir) {
        this.dir = dir;
    }

    /**
     * The newest snapshot whose date is strictly before {@code today}, or empty when none exists
     * (including an absent directory). Today's own file is skipped so a second run the same day still
     * diffs against the prior day rather than against itself. Names that are not {@code YYYY-MM-DD.tsv}
     * — the atomic-write {@code .tmp} sibling, unrelated files — are ignored.
     */
    public Optional<Path> priorSnapshot(LocalDate today) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> dateOf(p).filter(d -> d.isBefore(today)).isPresent())
                    .max((a, b) -> dateOf(a).orElseThrow().compareTo(dateOf(b).orElseThrow()));
        }
    }

    /** The file this run writes to: {@code DIR/<today>.tsv}, replacing any earlier run from today. */
    public Path targetFile(LocalDate today) {
        return fileFor(today);
    }

    /** The snapshot file for a specific day, whether or not it exists; used to address a compare end. */
    public Path fileFor(LocalDate date) {
        return dir.resolve(date + SUFFIX);
    }

    // The date a snapshot file name encodes, or empty if the name is not exactly <ISO date>.tsv.
    private static Optional<LocalDate> dateOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(name.substring(0, name.length() - SUFFIX.length())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
