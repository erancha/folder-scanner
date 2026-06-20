package dev.erancha.folderscanner.consumer.folders.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the dated snapshot directory: SnapshotHistory owns the YYYY-MM-DD.tsv naming,
 * selects the newest snapshot strictly older than a given day, and
 * resolves the target file for a write.
 */
final class SnapshotHistoryTest {

    private static void touch(Path dir, String name) throws IOException {
        Files.write(dir.resolve(name), new byte[0]);
    }

    @Test
    void priorSnapshot_returns_the_newest_file_strictly_before_today(@TempDir Path dir)
            throws IOException {
        touch(dir, "2026-06-08.tsv");
        touch(dir, "2026-06-10.tsv");
        touch(dir, "2026-06-09.tsv");

        Optional<Path> prior = new SnapshotHistory(dir).priorSnapshot(LocalDate.parse("2026-06-12"));

        assertEquals(Optional.of(dir.resolve("2026-06-10.tsv")), prior);
    }

    @Test
    void priorSnapshot_ignores_todays_own_file_so_a_rerun_diffs_against_yesterday(@TempDir Path dir)
            throws IOException {
        touch(dir, "2026-06-11.tsv");
        touch(dir, "2026-06-12.tsv"); // today's file from an earlier run today

        Optional<Path> prior = new SnapshotHistory(dir).priorSnapshot(LocalDate.parse("2026-06-12"));

        assertEquals(Optional.of(dir.resolve("2026-06-11.tsv")), prior);
    }

    @Test
    void priorSnapshot_skips_tmp_siblings_and_names_that_are_not_dated_snapshots(@TempDir Path dir)
            throws IOException {
        touch(dir, "2026-06-10.tsv");
        touch(dir, "2026-06-12.tsv.tmp"); // atomic-write leftover, not a finished snapshot
        touch(dir, "notes.tsv");          // unrelated file in the directory

        Optional<Path> prior = new SnapshotHistory(dir).priorSnapshot(LocalDate.parse("2026-06-12"));

        assertEquals(Optional.of(dir.resolve("2026-06-10.tsv")), prior);
    }

    @Test
    void priorSnapshot_is_empty_when_no_dated_snapshot_predates_today(@TempDir Path dir)
            throws IOException {
        touch(dir, "2026-06-12.tsv");
        touch(dir, "2026-06-20.tsv");

        Optional<Path> prior = new SnapshotHistory(dir).priorSnapshot(LocalDate.parse("2026-06-12"));

        assertTrue(prior.isEmpty());
    }

    @Test
    void priorSnapshot_is_empty_for_an_absent_directory(@TempDir Path dir) throws IOException {
        Optional<Path> prior = new SnapshotHistory(dir.resolve("missing"))
                .priorSnapshot(LocalDate.parse("2026-06-12"));

        assertTrue(prior.isEmpty());
    }

    @Test
    void targetFile_and_fileFor_name_by_date_under_the_directory(@TempDir Path dir) {
        SnapshotHistory history = new SnapshotHistory(dir);

        assertEquals(dir.resolve("2026-06-12.tsv"), history.targetFile(LocalDate.parse("2026-06-12")));
        assertEquals(dir.resolve("2026-06-01.tsv"), history.fileFor(LocalDate.parse("2026-06-01")));
    }
}
