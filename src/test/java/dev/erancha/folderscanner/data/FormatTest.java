package dev.erancha.folderscanner.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for Format. Covers the inverse pair humanBytes/parseSize, the unit roll-up around the
 * 1024 boundary, the parser's rejection of malformed input, and the elapsed-time minute rollover.
 */
final class FormatTest {

    @Test
    void humanBytes_prints_raw_bytes_below_one_kilobyte() {
        assertEquals("0 B", Format.humanBytes(0));
        assertEquals("1023 B", Format.humanBytes(1023));
    }

    @Test
    void humanBytes_rolls_up_at_kilobyte_boundary() {
        assertEquals("1.00 KB", Format.humanBytes(1024));
        assertEquals("1.50 KB", Format.humanBytes(1024 + 512));
    }

    @Test
    void humanBytes_rolls_up_through_mb_gb_tb() {
        assertEquals("1.00 MB", Format.humanBytes(1024L * 1024));
        assertEquals("1.00 GB", Format.humanBytes(1024L * 1024 * 1024));
        assertEquals("1.00 TB", Format.humanBytes(1024L * 1024 * 1024 * 1024));
    }

    @Test
    void humanBytesColumn_right_aligns_a_one_decimal_numeric_field() {
        // Numeric field is right-aligned to 5 chars ("999.9") so size columns line up on the dot.
        assertEquals("  1.0 KB", Format.humanBytesColumn(1024));
        assertEquals("  1.5 KB", Format.humanBytesColumn(1024 + 512));
        assertEquals("512.0 KB", Format.humanBytesColumn(512L * 1024));
        assertEquals("  1.0 MB", Format.humanBytesColumn(1024L * 1024));
    }

    @Test
    void humanBytesColumn_right_aligns_raw_bytes_below_one_kilobyte() {
        assertEquals("    0 B", Format.humanBytesColumn(0));
        assertEquals("  337 B", Format.humanBytesColumn(337));
        assertEquals(" 1023 B", Format.humanBytesColumn(1023));
    }

    @Test
    void formatElapsed_prints_seconds_below_one_minute() {
        assertEquals("0.0 s", Format.formatElapsed(0));
        assertEquals("30.0 s", Format.formatElapsed(30_000));
        assertEquals("59.9 s", Format.formatElapsed(59_900));
    }

    @Test
    void formatElapsed_appends_minutes_once_past_one_minute() {
        assertEquals("60.0 s (1.0 m)", Format.formatElapsed(60_000));
        assertEquals("90.0 s (1.5 m)", Format.formatElapsed(90_000));
    }

    @Test
    void parseSize_accepts_null_and_empty_as_zero() {
        assertEquals(0L, Format.parseSize(null));
        assertEquals(0L, Format.parseSize(""));
        assertEquals(0L, Format.parseSize("  "));
    }

    @Test
    void parseSize_parses_plain_bytes() {
        assertEquals(0L, Format.parseSize("0"));
        assertEquals(4096L, Format.parseSize("4096"));
    }

    @Test
    void parseSize_parses_each_unit_suffix_case_insensitively() {
        assertEquals(1L, Format.parseSize("1B"));
        assertEquals(1024L, Format.parseSize("1KB"));
        assertEquals(1024L, Format.parseSize("1kb"));
        assertEquals(1024L * 1024, Format.parseSize("1MB"));
        assertEquals(1024L * 1024 * 1024, Format.parseSize("1GB"));
        assertEquals(1024L * 1024 * 1024 * 1024, Format.parseSize("1TB"));
    }

    @Test
    void parseSize_tolerates_whitespace_between_number_and_unit() {
        assertEquals(512L * 1024, Format.parseSize("  512 KB  "));
    }

    @Test
    void parseSize_rejects_negative_values() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Format.parseSize("-1KB"));
        assertEquals("negative size: -1KB", ex.getMessage());
    }

    @Test
    void parseSize_rejects_non_numeric_input() {
        assertThrows(IllegalArgumentException.class, () -> Format.parseSize("abc"));
        assertThrows(IllegalArgumentException.class, () -> Format.parseSize("1.5KB"));
        assertThrows(IllegalArgumentException.class, () -> Format.parseSize("KB"));
    }
}
