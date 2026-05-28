package com.example.folderscanner.consumer;

import java.util.Map;

/**
 * Immutable snapshot of the aggregator's final counts. The compact constructor wraps every Map
 * with Map.copyOf so the snapshot's contract is enforced by the type, not by reviewer trust:
 * neither the builder nor an external accessor consumer can mutate a published snapshot.
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
) {
    public AggregationResult {
        countByExtension = Map.copyOf(countByExtension);
        bytesByExtension = Map.copyOf(bytesByExtension);
        countBySizeBucket = Map.copyOf(countBySizeBucket);
        bytesBySizeBucket = Map.copyOf(bytesBySizeBucket);
        countByDateBucket = Map.copyOf(countByDateBucket);
        bytesByDateBucket = Map.copyOf(bytesByDateBucket);
    }
}
