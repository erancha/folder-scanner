package com.example.folderscanner.consumer;

import java.util.Map;

/**
 * Immutable snapshot of the aggregator's final counts. Maps are not defensively copied —
 * the builder constructs them once and never mutates them after returning the record.
 */
public record AggregationResult(
    Map<String, Long> countByExtension,
    Map<String, Long> bytesByExtension,
    Map<SizeBucket, Long> countBySizeBucket,
    Map<SizeBucket, Long> bytesBySizeBucket,
    Map<String, Long> countByDateBucket,
    Map<String, Long> bytesByDateBucket,
    long totalFiles,
    long totalBytes
) {}
