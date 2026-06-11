package dev.erancha.folderscanner.consumer.folders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the baseline snapshot file: {@link BaselineSnapshot} writes the current folder
 * sizes and the run timestamp as tab-delimited text and reads them back for the next run's diff.
 */
final class BaselineSnapshotTest {

    @Test
    void write_then_read_round_trips_bytes_and_timestamp_for_paths_with_spaces(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("baseline.tsv");
        Instant ts = Instant.parse("2026-06-10T08:00:00Z");
        // A space-bearing path must survive the round trip, which is why the format is tab-delimited
        // with the path last rather than whitespace-split.
        BaselineSnapshot.write(file, ts, List.of(
                new FolderSize(Paths.get("/mnt/c/Program Files"), 3, 100L),
                new FolderSize(Paths.get("/mnt/c/data"), 5, 200L)));

        BaselineSnapshot snap = BaselineSnapshot.read(file);

        assertEquals(ts, snap.timestamp());
        Map<Path, Long> expected = new HashMap<>();
        expected.put(Paths.get("/mnt/c/Program Files"), 100L);
        expected.put(Paths.get("/mnt/c/data"), 200L);
        assertEquals(expected, snap.bytesByFolder());
    }
}
