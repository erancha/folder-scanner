package dev.erancha.folderscanner.consumer.aggregator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks in the snapshot-decoupling guarantee that the record's javadoc implies: defensive
 * copying must isolate the snapshot from any post-construction mutation of the source maps,
 * and the presentation order chosen by the Aggregator (date insertion order, size enum order)
 * must survive the copy so the printed tables read as designed.
 */
final class AggregationResultTest {

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

    @Test
    void preserves_date_bucket_insertion_order_for_presentation() {
        // The Aggregator builds the date map with a hand-chosen order (today / week / month /
        // year / prior years desc) that the printed table assumes. Defensive copying must not
        // shuffle that order — the renderer trusts the snapshot.
        Map<String, Long> countDate = new LinkedHashMap<>();
        countDate.put("today", 1L);
        countDate.put("this week", 2L);
        countDate.put("this month", 3L);
        countDate.put("this year", 4L);
        countDate.put("2025", 5L);
        countDate.put("2024", 6L);
        countDate.put("2023", 7L);
        Map<String, Long> bytesDate = new LinkedHashMap<>(countDate);

        AggregationResult r = new AggregationResult(new HashMap<>(), new HashMap<>(),
                new EnumMap<>(SizeBucket.class), new EnumMap<>(SizeBucket.class),
                countDate, bytesDate, 28L, 28L);

        List<String> expected = new ArrayList<>(countDate.keySet());
        assertEquals(expected, new ArrayList<>(r.countByDateBucket().keySet()));
        assertEquals(expected, new ArrayList<>(r.bytesByDateBucket().keySet()));
    }

    @Test
    void preserves_size_bucket_enum_order_for_presentation() {
        // The Aggregator populates an EnumMap in SizeBucket.values() order so the printed
        // size table reads small-to-large. Defensive copying must preserve that order.
        Map<SizeBucket, Long> countSize = new EnumMap<>(SizeBucket.class);
        Map<SizeBucket, Long> bytesSize = new EnumMap<>(SizeBucket.class);
        for (SizeBucket b : SizeBucket.values()) {
            countSize.put(b, 0L);
            bytesSize.put(b, 0L);
        }

        AggregationResult r = new AggregationResult(new HashMap<>(), new HashMap<>(),
                countSize, bytesSize, new LinkedHashMap<>(), new LinkedHashMap<>(), 0L, 0L);

        List<SizeBucket> expected = List.of(SizeBucket.values());
        assertEquals(expected, new ArrayList<>(r.countBySizeBucket().keySet()));
        assertEquals(expected, new ArrayList<>(r.bytesBySizeBucket().keySet()));
    }
}
