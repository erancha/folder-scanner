package dev.erancha.folderscanner.consumer.folders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SnapshotComparer}, the on-demand compare path: it reads two stored dated
 * snapshots from the history directory and renders the growth / new-folder diff between them without
 * running a scan.
 */
final class SnapshotComparerTest {

    private static void writeSnapshot(Path dir, String date, List<FolderSize> rows)
            throws IOException {
        BaselineSnapshot.write(dir.resolve(date + ".tsv"), Instant.parse(date + "T12:00:00Z"), rows);
    }

    @Test
    void compares_two_stored_snapshots_and_diffs_the_later_against_the_earlier(@TempDir Path dir)
            throws IOException {
        writeSnapshot(dir, "2026-06-01", List.of(
                new FolderSize(Paths.get("/mnt/c/grower"), 1, 100L)));
        writeSnapshot(dir, "2026-06-10", List.of(
                new FolderSize(Paths.get("/mnt/c/grower"), 2, 150L),  // +50% since FROM
                new FolderSize(Paths.get("/mnt/c/fresh"), 1, 80L)));  // absent in FROM

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        SnapshotComparer.compare(dir, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-10"),
                10.0, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);

        assertTrue(out.contains("Folder growth since"), out);
        assertTrue(out.contains("/mnt/c/grower"), "the grown folder must appear");
        assertTrue(out.contains("/mnt/c/fresh"), "the folder new since FROM must appear");
    }

    @Test
    void missing_snapshot_for_a_requested_date_fails_with_a_clear_message(@TempDir Path dir)
            throws IOException {
        writeSnapshot(dir, "2026-06-01", List.of(
                new FolderSize(Paths.get("/mnt/c/grower"), 1, 100L)));

        IOException thrown = assertThrows(IOException.class, () ->
                SnapshotComparer.compare(dir, LocalDate.parse("2026-06-01"),
                        LocalDate.parse("2026-06-10"), 10.0,
                        new PrintStream(java.io.OutputStream.nullOutputStream())));
        assertTrue(thrown.getMessage().contains("2026-06-10"),
                "the message must name the missing date; was: " + thrown.getMessage());
    }
}
