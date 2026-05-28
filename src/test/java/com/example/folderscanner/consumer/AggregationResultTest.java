package com.example.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks in the immutability contract implied by the record's javadoc: every Map exposed by an
 * AggregationResult accessor must reject mutation, so an external caller cannot retroactively
 * corrupt a snapshot the aggregator has already published.
 */
final class AggregationResultTest {

    @Test
    void accessors_return_unmodifiable_maps_even_when_built_from_mutable_input() {
        Map<String, Long> countExt = new HashMap<>();
        countExt.put("txt", 1L);
        Map<String, Long> bytesExt = new HashMap<>();
        Map<SizeBucket, Long> countSize = new HashMap<>();
        Map<SizeBucket, Long> bytesSize = new HashMap<>();
        Map<String, Long> countDate = new HashMap<>();
        Map<String, Long> bytesDate = new HashMap<>();

        AggregationResult r = new AggregationResult(countExt, bytesExt, countSize, bytesSize,
                countDate, bytesDate, 1L, 0L);

        assertThrows(UnsupportedOperationException.class,
                () -> r.countByExtension().put("oops", 2L));
        assertThrows(UnsupportedOperationException.class,
                () -> r.bytesByExtension().put("oops", 2L));
        assertThrows(UnsupportedOperationException.class,
                () -> r.countBySizeBucket().put(SizeBucket.LE_1KB, 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> r.bytesBySizeBucket().put(SizeBucket.LE_1KB, 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> r.countByDateBucket().put("today", 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> r.bytesByDateBucket().put("today", 1L));
    }

    @Test
    void mutating_source_map_after_construction_does_not_leak_into_snapshot() {
        Map<String, Long> countExt = new HashMap<>();
        countExt.put("txt", 1L);

        AggregationResult r = new AggregationResult(countExt, new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), 1L, 0L);

        countExt.put("md", 999L);

        if (r.countByExtension().containsKey("md")) {
            throw new AssertionError("snapshot leaked post-construction mutation of source map");
        }
    }
}
