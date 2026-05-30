package dev.erancha.folderscanner.consumer.aggregator;

import dev.erancha.folderscanner.consumer.AbstractFileConsumer;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.Format;
import dev.erancha.folderscanner.data.PathFileInfo;
import dev.erancha.folderscanner.data.PoisonPill;
import dev.erancha.folderscanner.data.ExtensionFileInfo;
import dev.erancha.folderscanner.producer.FileInfoFactory;
import dev.erancha.folderscanner.producer.FileExtensions;
import java.io.PrintStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregates file counts and bytes by extension, size bucket, and date bucket, and prints the
 * resulting tables to the supplied PrintStream.
 *
 * Multiple drainer threads: the per-message work is tiny but high-frequency; a single drainer would
 * bottleneck at line rate before the producer's queue.put() ever blocks.
 */
public final class Aggregator extends AbstractFileConsumer {

    // Keys are file extensions (e.g. "jpg").
    private final ConcurrentHashMap<String, LongAdder> countByExtension = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> bytesByExtension = new ConcurrentHashMap<>();

    // Plain array (not a CHM) because the SizeBucket key space is closed at compile time.
    private final LongAdder[] countBySize = new LongAdder[SizeBucket.values().length];
    private final LongAdder[] bytesBySize = new LongAdder[SizeBucket.values().length];

    // Cumulative: today ⊂ week ⊂ month ⊂ year. Subtract for mutually-exclusive counts.
    private final LongAdder countToday = new LongAdder();
    private final LongAdder countWeek = new LongAdder();
    private final LongAdder countMonth = new LongAdder();
    private final LongAdder countYear = new LongAdder();
    private final LongAdder bytesToday = new LongAdder();
    private final LongAdder bytesWeek = new LongAdder();
    private final LongAdder bytesMonth = new LongAdder();
    private final LongAdder bytesYear = new LongAdder();

    // Keys are calendar years.
    private final ConcurrentHashMap<Integer, LongAdder> countByPriorYear = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LongAdder> bytesByPriorYear = new ConcurrentHashMap<>();

    // Epoch-milli boundaries computed once at construction so the hot path is long-compares only.
    private final long yearStartMillis;
    private final long monthStartMillis;
    private final long weekStartMillis;
    private final long todayStartMillis;
    private final long todayEndMillis;
    private final ZoneId zone = ZoneId.systemDefault();

    private static final int TABLE_INDENT = 4;

    public Aggregator(BlockingQueue<FileInfo> queue, int consumerThreads) {
        super(queue, consumerThreads, "aggregator");
        for (int i = 0; i < countBySize.length; i++) {
            countBySize[i] = new LongAdder();
            bytesBySize[i] = new LongAdder();
        }
        // Week starts on Sunday per spec; DayOfWeek is MON=1..SUN=7.
        LocalDate today = LocalDate.now(zone);
        DayOfWeek dow = today.getDayOfWeek();
        int daysSinceSunday = dow == DayOfWeek.SUNDAY ? 0 : dow.getValue();
        LocalDate weekStart = today.minusDays(daysSinceSunday);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate yearStart = today.withDayOfYear(1);
        this.todayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli();
        this.todayEndMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        this.weekStartMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli();
        this.monthStartMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli();
        this.yearStartMillis = yearStart.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new ExtensionFileInfo(extensionOf(path), attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    /**
     * Delegates to {@link FileExtensions#extensionOf} so producer and consumer agree on what
     * "(none)" means.
     */
    static String extensionOf(java.nio.file.Path file) {
        return FileExtensions.extensionOf(file);
    }

    /**
     * Drainer loop. Exhaustive switch over the sealed FileInfo hierarchy turns a future variant
     * into a compile error here; the PathFileInfo arm exists only to surface a mis-wired producer
     * as IllegalStateException instead of a silent ClassCastException on a pool thread.
     */
    @Override
    protected void consume() {
        try {
            while (true) {
                FileInfo f = queue.take();
                switch (f) {
                case ExtensionFileInfo t -> {
                    totalFiles.increment();
                    totalBytes.add(t.size());
                    countByExtension.computeIfAbsent(t.extension(), k -> new LongAdder())
                            .increment();
                    bytesByExtension.computeIfAbsent(t.extension(), k -> new LongAdder())
                            .add(t.size());
                    SizeBucket b = SizeBucket.of(t.size());
                    countBySize[b.ordinal()].increment();
                    bytesBySize[b.ordinal()].add(t.size());
                    classifyByDate(t.lastModifiedMillis(), t.size());
                }
                case PoisonPill ignored -> {
                    return;
                }
                case PathFileInfo ignored -> throw new IllegalStateException(
                        "Aggregator received PathFileInfo; its factory() produces only "
                                + "ExtensionFileInfo");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cumulative, not mutually exclusive: a file from today increments today, week, month, and
     * year. Future-dated files (clock skew, bad metadata) are skipped here but still counted in the
     * extension/size aggregations.
     */
    void classifyByDate(long mtime, long size) {
        if (mtime >= todayEndMillis)
            return;
        if (mtime >= yearStartMillis) {
            countYear.increment();
            bytesYear.add(size);
            if (mtime >= monthStartMillis) {
                countMonth.increment();
                bytesMonth.add(size);
                if (mtime >= weekStartMillis) {
                    countWeek.increment();
                    bytesWeek.add(size);
                    if (mtime >= todayStartMillis) {
                        countToday.increment();
                        bytesToday.add(size);
                    }
                }
            }
        } else {
            int year = Instant.ofEpochMilli(mtime).atZone(zone).getYear();
            countByPriorYear.computeIfAbsent(year, k -> new LongAdder()).increment();
            bytesByPriorYear.computeIfAbsent(year, k -> new LongAdder()).add(size);
        }
    }

    @Override
    protected void report(PrintStream out) {
        printResult(out, snapshot());
    }

    AggregationResult snapshot() {
        Map<String, Long> extensionCounts = new TreeMap<>();
        Map<String, Long> extensionBytes = new TreeMap<>();
        countByExtension.forEach((k, v) -> extensionCounts.put(k, v.sum()));
        bytesByExtension.forEach((k, v) -> extensionBytes.put(k, v.sum()));
        Map<SizeBucket, Long> countBySizeMap = new EnumMap<>(SizeBucket.class);
        Map<SizeBucket, Long> bytesBySizeMap = new EnumMap<>(SizeBucket.class);
        for (SizeBucket b : SizeBucket.values()) {
            countBySizeMap.put(b, countBySize[b.ordinal()].sum());
            bytesBySizeMap.put(b, bytesBySize[b.ordinal()].sum());
        }
        // LinkedHashMap so display order matches: today, week, month, year, then prior years desc.
        Map<String, Long> countByDate = new LinkedHashMap<>();
        Map<String, Long> bytesByDate = new LinkedHashMap<>();
        countByDate.put("today", countToday.sum());
        bytesByDate.put("today", bytesToday.sum());
        countByDate.put("this week", countWeek.sum());
        bytesByDate.put("this week", bytesWeek.sum());
        countByDate.put("this month", countMonth.sum());
        bytesByDate.put("this month", bytesMonth.sum());
        countByDate.put("this year", countYear.sum());
        bytesByDate.put("this year", bytesYear.sum());
        List<Integer> priorYears = new ArrayList<>(countByPriorYear.keySet());
        priorYears.sort(Comparator.reverseOrder());
        for (Integer year : priorYears) {
            String key = String.valueOf(year);
            countByDate.put(key, countByPriorYear.get(year).sum());
            LongAdder bAdder = bytesByPriorYear.get(year);
            bytesByDate.put(key, bAdder == null ? 0L : bAdder.sum());
        }
        return new AggregationResult(extensionCounts, extensionBytes, countBySizeMap,
                bytesBySizeMap, countByDate, bytesByDate, totalFiles.sum(), totalBytes.sum());
    }

    private static void printResult(PrintStream out, AggregationResult r) {
        out.println("\nBy extension:");
        // Sorted bytes-desc for display so the biggest contributors surface first; the snapshot's
        // alphabetical order is kept for the by-size / by-date tables and for stable diffs.
        Map<String, Long> countsByExtSorted = byBytesDescending(r.countByExtension(),
                r.bytesByExtension());
        out.print(buildTable("extension", 16, countsByExtSorted, r.bytesByExtension(),
                java.util.function.Function.identity(), r.totalFiles(), r.totalBytes())
                        .indent(TABLE_INDENT));

        out.println("\nBy size bucket:");
        out.print(buildTable("bucket", 20, r.countBySizeBucket(), r.bytesBySizeBucket(),
                SizeBucket::label, r.totalFiles(), r.totalBytes()).indent(TABLE_INDENT));

        out.println("\nBy date bucket (cumulative - today is also in this week/month/year):");
        out.print(buildTable("date", 16, r.countByDateBucket(), r.bytesByDateBucket(),
                java.util.function.Function.identity(), r.totalFiles(), r.totalBytes())
                        .indent(TABLE_INDENT));
    }

    /**
     * Reorders {@code counts} by descending byte count, with extension name as the ascending
     * tiebreaker so the same input always produces the same table. Extensions missing from
     * {@code bytes} are ranked last with zero bytes (a builder bug, but preferable to NPE during
     * print).
     */
    static Map<String, Long> byBytesDescending(Map<String, Long> counts, Map<String, Long> bytes) {
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((a, b) -> {
            int cmp = Long.compare(bytes.getOrDefault(b.getKey(), 0L),
                    bytes.getOrDefault(a.getKey(), 0L));
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : ordered) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static <K> String buildTable(String keyHeader, int keyWidth,
            java.util.Map<K, Long> counts, java.util.Map<K, Long> bytes,
            java.util.function.Function<K, String> keyLabel, long totalCount, long totalBytes) {
        String fmt = "%-" + keyWidth + "s %10s %15s%n";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(fmt, keyHeader, "count", "bytes"));
        for (java.util.Map.Entry<K, Long> e : counts.entrySet()) {
            K key = e.getKey();
            long count = e.getValue();
            long byteCount = bytes.getOrDefault(key, 0L);
            sb.append(String.format(fmt, keyLabel.apply(key), count, Format.humanBytes(byteCount)));
        }
        // by-extension and by-size rows sum to this total; by-date rows don't (they're cumulative).
        sb.append(String.format(fmt, "total", totalCount, Format.humanBytes(totalBytes)));
        return sb.toString();
    }
}
