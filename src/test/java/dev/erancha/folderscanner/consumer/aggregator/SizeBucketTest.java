package dev.erancha.folderscanner.consumer.aggregator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for SizeBucket.of. The boundaries are inclusive on the upper side
 * (1024 lands in LE_1KB, not LE_1MB); these tests pin that behavior so a future
 * refactor cannot silently flip the comparison.
 */
final class SizeBucketTest {

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;
    private static final long MB_20 = 20L * MB;
    private static final long GB = 1024L * MB;

    @Test
    void zero_bytes_falls_into_first_bucket() {
        assertEquals(SizeBucket.LE_1KB, SizeBucket.of(0));
    }

    @Test
    void boundary_values_use_inclusive_upper_bound() {
        assertEquals(SizeBucket.LE_1KB, SizeBucket.of(KB));
        assertEquals(SizeBucket.LE_1MB, SizeBucket.of(MB));
        assertEquals(SizeBucket.LE_20MB, SizeBucket.of(MB_20));
        assertEquals(SizeBucket.LE_1GB, SizeBucket.of(GB));
    }

    @Test
    void one_byte_over_each_boundary_promotes_to_next_bucket() {
        assertEquals(SizeBucket.LE_1MB, SizeBucket.of(KB + 1));
        assertEquals(SizeBucket.LE_20MB, SizeBucket.of(MB + 1));
        assertEquals(SizeBucket.LE_1GB, SizeBucket.of(MB_20 + 1));
        assertEquals(SizeBucket.GT_1GB, SizeBucket.of(GB + 1));
    }
}
