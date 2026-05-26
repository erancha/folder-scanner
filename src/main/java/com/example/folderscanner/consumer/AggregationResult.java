package com.example.folderscanner.consumer;

import java.util.Map;

/**
 * Immutable snapshot of the aggregator's final counts: by extension, by
 * size bucket, and by date bucket. Separate maps for count and bytes so
 * callers can scan just the metric they care about. The maps are not
 * defensively copied here; the producer of this record builds them once
 * and never mutates them.
 */
public record AggregationResult(
    Map<String, Long> countByExtension,
    Map<String, Long> bytesByExtension,
    Map<SizeBucket, Long> countBySizeBucket,
    Map<SizeBucket, Long> bytesBySizeBucket,
    Map<String, Long> countByDateBucket,
    Map<String, Long> bytesByDateBucket
) {}
