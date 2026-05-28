package com.example.folderscanner.consumer.aggregator;

import com.example.folderscanner.consumer.FileConsumer;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
import com.example.folderscanner.data.PathFileInfo;
import com.example.folderscanner.data.PoisonPill;
import com.example.folderscanner.data.ExtensionFileInfo;
import com.example.folderscanner.producer.FileInfoFactory;
import com.example.folderscanner.producer.FileExtensions;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregates file counts and bytes by extension, size bucket, and date bucket, and prints the
 * resulting tables to the supplied PrintStream.
 *
 * Multiple drainer threads: the per-message work is tiny but high-frequency; a single drainer would
 * bottleneck at line rate before the producer's queue.put() ever blocks.
 */
public final class Aggregator implements FileConsumer {

    private final BlockingQueue<FileInfo> queue;
    private final ThreadPoolExecutor drainerPool;

    // Keys are file extensions (e.g. "jpg").
    private final ConcurrentHashMap<String, LongAdder> countByExtension = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> bytesByExtension = new ConcurrentHashMap<>();

    // Size-bucket keys are fixed at compile time, so a plain array indexed by ordinal() is
    // simpler and faster than another CHM lookup per file.
    private final LongAdder[] countBySize = new LongAdder[SizeBucket.values().length];
    private final LongAdder[] bytesBySize = new LongAdder[SizeBucket.values().length];

    // Cumulative date buckets — e.g. today ⊂ this week ⊂ this month ⊂ .. (i.e a file modified today
    // is also in this week, month, ..). Subtract for mutually-exclusive counts.
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

    private final LongAdder totalFiles = new LongAdder();
    private final LongAdder totalBytes = new LongAdder();

    // Epoch-milli boundaries computed once at construction so the hot path is long-compares only.
    private final long yearStartMillis;
    private final long monthStartMillis;
    private final long weekStartMillis;
    private final long todayStartMillis;
    private final long todayEndMillis;
    private final ZoneId zone = ZoneId.systemDefault();

    private static final int TABLE_INDENT = 4;

    public Aggregator(BlockingQueue<FileInfo> queue, int consumerThreads) {
        if (consumerThreads < 1)
            throw new IllegalArgumentException("consumerThreads must be >= 1");
        this.queue = queue;
        this.drainerPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(consumerThreads);
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
    public int drainerCount() {
        return drainerPool.getCorePoolSize();
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

    @Override
    public void start() {
        for (int i = 0, n = drainerPool.getCorePoolSize(); i < n; i++) {
            drainerPool.submit(this::consume);
        }
    }

    /**
     * Drainer loop. Exhaustive switch over the sealed FileInfo hierarchy turns a future variant
     * into a compile error here; the PathFileInfo arm exists only to surface a mis-wired producer
     * as IllegalStateException instead of a silent ClassCastException on a pool thread.
     */
    void consume() {
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
    public void awaitAndReport(PrintStream out) throws InterruptedException {
        // shutdown() (not shutdownNow) so queued items still drain — drainers exit on POISON.
        drainerPool.shutdown();
        if (!drainerPool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS)) {
            throw new IllegalStateException("aggregator did not terminate within 1 hour");
        }
        printResult(out, snapshot());
    }

    @Override
    public long totalFilesSeen() {
        return totalFiles.sum();
    }

    @Override
    public long totalBytesSeen() {
        return totalBytes.sum();
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
        // Display order is bytes-desc (largest footprint first) so the biggest contributors
        // surface at the top of the table. The snapshot's own map order (alphabetical) is
        // useful for diffs but burying a 79 GB extension behind a 5 MB one is the opposite
        // of what a user scanning the output is looking for.
        Map<String, Long> countsByExtSorted =
                byBytesDescending(r.countByExtension(), r.bytesByExtension());
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
     * Returns {@code counts} reshaped as a LinkedHashMap whose iteration order is bytes-desc
     * by extension, with the extension name as the secondary ascending tiebreaker so the same
     * input always produces the same table. Extensions absent from {@code bytes} contribute
     * zero bytes (defensive: an extension present in counts but missing from bytes is a
     * builder bug, but ranking it last is preferable to a NullPointerException at print-time).
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
        // Same total expected on every table — the count column of the by-extension and by-size
        // rows must sum to it; the by-date rows can't (they're cumulative), but the printed total
        // still anchors the table to one authoritative number.
        sb.append(String.format(fmt, "total", totalCount, Format.humanBytes(totalBytes)));
        return sb.toString();
    }
}
