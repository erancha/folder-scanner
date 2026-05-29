package com.example.folderscanner.config;

import com.example.folderscanner.producer.FileExtensions;
import java.util.Set;

/**
 * Typed snapshot of one CLI invocation: every tuning knob plus the scan target. Immutable once
 * built. Parsing and validation live in {@link Cli}, which is the single owner of the user-facing
 * flag surface; this record is the validated result that the scan wiring consumes.
 */
public record Config(int queueSize, boolean statsEnabled, int producers, int consumers,
        QueueType queueType, ConsumerKind consumerKind, ManageAction action, String outPath,
        boolean hardDelete, long minSizeBytes, Set<String> excludeDirs,
        FileExtensions.IncludeSet includeExtensions, String target) {
}
