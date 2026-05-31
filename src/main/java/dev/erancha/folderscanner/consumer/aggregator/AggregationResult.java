package dev.erancha.folderscanner.consumer.aggregator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of the aggregator's final counts. The compact constructor wraps every Map
 * in an unmodifiable LinkedHashMap copy so the snapshot's contract is enforced by the type, not
 * by reviewer trust: neither the builder nor an external accessor consumer can mutate a
 * published snapshot.
 *
 * Why LinkedHashMap rather than Map.copyOf: the renderer trusts the snapshot's iteration order
 * (date buckets read today→week→month→year→prior years; size buckets read small→large via the
 * EnumMap the builder constructs). Map.copyOf's iteration order is officially unspecified and
 * empirically reshuffles both tables, so it would silently break the printed output.
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
        countByExtension = orderedUnmodifiableCopy(countByExtension);
        bytesByExtension = orderedUnmodifiableCopy(bytesByExtension);
        countBySizeBucket = orderedUnmodifiableCopy(countBySizeBucket);
        bytesBySizeBucket = orderedUnmodifiableCopy(bytesBySizeBucket);
        countByDateBucket = orderedUnmodifiableCopy(countByDateBucket);
        bytesByDateBucket = orderedUnmodifiableCopy(bytesByDateBucket);
    }

    private static <K, V> Map<K, V> orderedUnmodifiableCopy(Map<K, V> in) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(in));
    }
}
