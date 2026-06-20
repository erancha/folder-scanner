package dev.erancha.folderscanner.consumer.folders.growth;

import dev.erancha.folderscanner.consumer.folders.FolderSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The persisted folder-size baseline behind --baseline: a tab-delimited snapshot of one run's
 * recursive folder sizes plus the run timestamp, used as the prior point of comparison by the next
 * day's growth diff.
 *
 * File layout is one header line "# <ISO-8601 instant>" followed by one folder per line as
 * bytes, count, path separated by tabs. The path is last and the delimiter is a tab so folder names
 * containing spaces survive a round trip.
 */
public final class BaselineSnapshot {

    private static final char DELIM = '\t';

    private final Instant timestamp;
    // Recorded folder sizes in file order. The count is kept alongside bytes (not bytes alone) so the
    // compare path can render the "new folders" count column.
    private final List<FolderSize> rows;

    private BaselineSnapshot(Instant timestamp, List<FolderSize> rows) {
        this.timestamp = timestamp;
        this.rows = rows;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public List<FolderSize> rows() {
        return rows;
    }

    // Recursive subtree bytes keyed by folder — the side the day-over-day diff compares on.
    public Map<Path, Long> bytesByFolder() {
        Map<Path, Long> byFolder = new HashMap<>();
        for (FolderSize row : rows) {
            byFolder.put(row.path(), row.bytes());
        }
        return byFolder;
    }

    /**
     * Writes the snapshot atomically: the rows are staged in a sibling temp file that is then moved
     * over file, so a crash mid-write can never truncate or corrupt the prior baseline.
     */
    public static void write(Path file, Instant timestamp, List<FolderSize> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("# " + timestamp);
        for (FolderSize row : rows) {
            lines.add(row.bytes() + "" + DELIM + row.count() + DELIM + row.path());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(tmp.toAbsolutePath().getParent());
        Files.write(tmp, lines);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Parses a snapshot written by write; the caller checks the file exists first. */
    public static BaselineSnapshot read(Path file) throws IOException {
        Instant timestamp = null;
        List<FolderSize> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("# ")) {
                timestamp = Instant.parse(line.substring(2).trim());
                continue;
            }
            if (line.isEmpty())
                continue;
            int firstTab = line.indexOf(DELIM);
            int secondTab = line.indexOf(DELIM, firstTab + 1);
            long bytes = Long.parseLong(line.substring(0, firstTab));
            long count = Long.parseLong(line.substring(firstTab + 1, secondTab));
            String path = line.substring(secondTab + 1);
            rows.add(new FolderSize(Paths.get(path), count, bytes));
        }
        return new BaselineSnapshot(timestamp, rows);
    }
}
