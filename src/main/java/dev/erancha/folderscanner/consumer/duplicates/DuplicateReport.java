package dev.erancha.folderscanner.consumer.duplicates;

import java.nio.file.Path;
import java.util.List;

/**
 * Snapshot of phase-2 results. Groups are sorted by descending wasted bytes so the
 * generated script shows the biggest wins first.
 */
public record DuplicateReport(
        List<Group> groups,
        long groupCount,
        long redundantFileCount,
        long redundantBytes,
        long phase1ElapsedMs,
        long phase2ElapsedMs) {

    /** One confirmed duplicate set: two or more paths of identical size and content. */
    public record Group(long fileSizeBytes, List<Path> paths) {}
}
