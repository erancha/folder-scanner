package dev.erancha.folderscanner.consumer.folders;

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
 * The persisted folder-size baseline behind {@code --baseline}: a tab-delimited snapshot of one
 * run's recursive folder sizes plus the run timestamp, used as the prior point of comparison by the
 * next day's growth diff.
 *
 * File layout is one header line {@code # <ISO-8601 instant>} followed by one folder per line as
 * {@code bytes<TAB>count<TAB>path}. The path is last and the delimiter is a tab so folder names
 * containing spaces survive a round trip.
 */
public final class BaselineSnapshot {

    private static final char DELIM = '\t';

    private final Instant timestamp;
    // Key: folder path. Value: recursive subtree bytes recorded for it at snapshot time.
    private final Map<Path, Long> bytesByFolder;

    private BaselineSnapshot(Instant timestamp, Map<Path, Long> bytesByFolder) {
        this.timestamp = timestamp;
        this.bytesByFolder = bytesByFolder;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Map<Path, Long> bytesByFolder() {
        return bytesByFolder;
    }

    /**
     * Writes the snapshot atomically: the rows are staged in a sibling temp file that is then moved
     * over {@code file}, so a crash mid-write can never truncate or corrupt the prior baseline.
     */
    static void write(Path file, Instant timestamp, List<FolderSize> rows) throws IOException {
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

    /** Parses a snapshot written by {@link #write}; the caller checks {@code file} exists first. */
    static BaselineSnapshot read(Path file) throws IOException {
        Instant timestamp = null;
        Map<Path, Long> bytesByFolder = new HashMap<>();
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
            String path = line.substring(secondTab + 1);
            bytesByFolder.put(Paths.get(path), bytes);
        }
        return new BaselineSnapshot(timestamp, bytesByFolder);
    }
}
