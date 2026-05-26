package com.example.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.PathFileInfo;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Aggregator's pure helpers: extensionOf (extension parsing rules)
 * and classifyByDate (cumulative today / week / month / year bucketing with a
 * Sunday-aligned week start). The classifyByDate tests re-derive the same epoch
 * boundaries the production constructor uses so they are independent of the
 * specific calendar date the test runs on.
 */
final class AggregatorTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static Aggregator newAggregator() {
        return new Aggregator(new ArrayBlockingQueue<FileInfo>(1), 1);
    }

    // ---- extensionOf ----------------------------------------------------------

    @Test
    void extensionOf_returns_lowercase_suffix_after_last_dot() {
        assertEquals("txt", Aggregator.extensionOf(Paths.get("file.txt")));
        assertEquals("java", Aggregator.extensionOf(Paths.get("/path/to/Code.JAVA")));
        assertEquals("gz", Aggregator.extensionOf(Paths.get("archive.tar.gz")));
    }

    @Test
    void extensionOf_treats_dotfile_as_no_extension() {
        // dot == 0 path: leading-dot names like .bashrc are configuration files, not extensions.
        assertEquals("(none)", Aggregator.extensionOf(Paths.get(".bashrc")));
        assertEquals("(none)", Aggregator.extensionOf(Paths.get("/home/u/.gitconfig")));
    }

    @Test
    void extensionOf_treats_extensionless_name_as_no_extension() {
        assertEquals("(none)", Aggregator.extensionOf(Paths.get("README")));
    }

    @Test
    void extensionOf_treats_trailing_dot_as_no_extension() {
        // dot == name.length() - 1 path: name ends with a dot, no characters after.
        assertEquals("(none)", Aggregator.extensionOf(Paths.get("file.")));
    }

    // ---- classifyByDate -------------------------------------------------------

    @Test
    void classifyByDate_today_increments_all_four_cumulative_buckets() {
        Aggregator a = newAggregator();
        long now = System.currentTimeMillis();
        a.classifyByDate(now, 100L);

        Map<String, Long> counts = a.snapshot().countByDateBucket();
        Map<String, Long> bytes  = a.snapshot().bytesByDateBucket();
        assertEquals(1L, counts.get("today"));
        assertEquals(1L, counts.get("this week"));
        assertEquals(1L, counts.get("this month"));
        assertEquals(1L, counts.get("this year"));
        assertEquals(100L, bytes.get("today"));
        assertEquals(100L, bytes.get("this year"));
    }

    @Test
    void classifyByDate_prior_year_lands_in_prior_year_bucket_only() {
        Aggregator a = newAggregator();
        // 1500000000000 ms = 2017-07-14 UTC; safely in a prior year regardless of when this runs.
        long priorYearMtime = 1500000000000L;
        int priorYear = java.time.Instant.ofEpochMilli(priorYearMtime).atZone(ZONE).getYear();
        a.classifyByDate(priorYearMtime, 50L);

        Map<String, Long> counts = a.snapshot().countByDateBucket();
        Map<String, Long> bytes  = a.snapshot().bytesByDateBucket();
        assertEquals(0L, counts.get("today"));
        assertEquals(0L, counts.get("this week"));
        assertEquals(0L, counts.get("this month"));
        assertEquals(0L, counts.get("this year"));
        assertEquals(1L, counts.get(String.valueOf(priorYear)));
        assertEquals(50L, bytes.get(String.valueOf(priorYear)));
    }

    @Test
    void classifyByDate_future_mtime_is_skipped() {
        // Future-dated files (clock skew or bad metadata) must NOT advance any date counter,
        // and must NOT create a future-year prior-year entry.
        Aggregator a = newAggregator();
        long oneYearFromNow = System.currentTimeMillis() + 365L * 24 * 3600 * 1000;
        int futureYear = java.time.Instant.ofEpochMilli(oneYearFromNow).atZone(ZONE).getYear();
        a.classifyByDate(oneYearFromNow, 999L);

        Map<String, Long> counts = a.snapshot().countByDateBucket();
        assertEquals(0L, counts.get("today"));
        assertEquals(0L, counts.get("this week"));
        assertEquals(0L, counts.get("this month"));
        assertEquals(0L, counts.get("this year"));
        assertNull(counts.get(String.valueOf(futureYear)),
                "future year must not appear in the prior-year section");
    }

    @Test
    void classifyByDate_today_start_boundary_is_inclusive() {
        // mtime == start-of-today-millis MUST land in "today" (the spec is >= start of day).
        long startOfTodayMillis = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        Aggregator a = newAggregator();
        a.classifyByDate(startOfTodayMillis, 1L);
        assertEquals(1L, a.snapshot().countByDateBucket().get("today"));
    }

    @Test
    void classifyByDate_one_ms_before_today_start_is_not_in_today() {
        // off-by-one guard: the millisecond just before midnight belongs to yesterday,
        // not today. It still belongs to the current week (assuming today is not Sunday) and
        // to the current month/year.
        long beforeToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1;
        Aggregator a = newAggregator();
        a.classifyByDate(beforeToday, 1L);
        Map<String, Long> counts = a.snapshot().countByDateBucket();
        assertEquals(0L, counts.get("today"));
        // Skip the week assertion when today is Sunday; otherwise yesterday is in this week.
        if (LocalDate.now(ZONE).getDayOfWeek() != DayOfWeek.SUNDAY) {
            assertEquals(1L, counts.get("this week"));
        }
        // Skip the month assertion when today is the 1st; otherwise yesterday is in this month.
        if (LocalDate.now(ZONE).getDayOfMonth() != 1) {
            assertEquals(1L, counts.get("this month"));
        }
    }

    // ---- consume() dispatch over the sealed FileInfo hierarchy ---------------

    @Test
    void consume_rejects_wrong_FileInfo_subtype_with_IllegalStateException() throws InterruptedException {
        // Aggregator's factory only ever produces TypeFileInfo. If a PathFileInfo somehow reaches
        // consume() (mis-wired factory in a future change), the exhaustive switch must surface that
        // as a clear configuration bug — not a bare ClassCastException that bubbles out of a pool
        // thread anonymously and silently corrupts the aggregation.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        Aggregator a = new Aggregator(queue, 1);
        queue.put(new PathFileInfo(Paths.get("/tmp/x"), 100L, 0L));
        queue.put(FileInfo.POISON);
        assertThrows(IllegalStateException.class, a::consume);
    }

    @Test
    void classifyByDate_today_end_boundary_is_exclusive() {
        // mtime == start-of-tomorrow-millis is treated as future and MUST be skipped from
        // every bucket - the "future" guard at the top of classifyByDate is >= todayEndMillis.
        long startOfTomorrowMillis = LocalDate.now(ZONE).plusDays(1)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
        Aggregator a = newAggregator();
        a.classifyByDate(startOfTomorrowMillis, 1L);
        Map<String, Long> counts = a.snapshot().countByDateBucket();
        assertEquals(0L, counts.get("today"));
        assertEquals(0L, counts.get("this week"));
        assertEquals(0L, counts.get("this month"));
        assertEquals(0L, counts.get("this year"));
    }
}
