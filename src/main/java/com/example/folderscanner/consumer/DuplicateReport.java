package com.example.folderscanner.consumer;

import java.nio.file.Path;
import java.util.List;

/**
 * Snapshot of phase-2 results. Internal to the consumer package; the
 * public surface is the generated shell script.
 *
 * groups is sorted by descending wasted bytes so the user reading the
 * generated script sees the biggest wins first. The list and the inner
 * Group.paths lists are NOT defensively copied — callers receive the
 * same instances the producer built. Since this record is constructed
 * once and never mutated after, that is safe.
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
