package com.example.folderscanner.consumer;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
import com.example.folderscanner.data.PathFileInfo;
import com.example.folderscanner.data.PoisonPill;
import com.example.folderscanner.data.TypeFileInfo;
import com.example.folderscanner.producer.FileInfoFactory;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * What: drains FileInfo messages from a shared queue using N consumer threads and aggregates
 *       counts and total bytes by file extension and by size bucket. Stops when each consumer
 *       takes a POISON sentinel.
 * Why:  splitting the upstream producer from this consumer lets the two stages run
 *       concurrently, decoupled by the bounded queue - the producer can saturate the disk
 *       while consumers run on different cores. Multiple consumer threads matter because the
 *       per-message work is small but high-frequency; one consumer would become the bottleneck
 *       at line rate.
 */
public final class Aggregator implements FileConsumer {

    private final BlockingQueue<FileInfo> queue;   // APPLICATION QUEUE: handoff from an upstream producer (this class is the consumer); bounded for backpressure / OOM defense
    private final int consumerThreads;                           // number of parallel drainers; the caller reads it via consumerCount() to know how many POISON pills to enqueue
    private final ExecutorService pool;                          // fixed pool of N long-running drainers, submitted once at startup; each one loops until POISON. Work shape is flat - N identical consumers with no parent/child task relationships, no fork/join, no recursion - so a fixed pool is the minimal primitive that fits; anything fancier (work-stealing, join-helping) would add machinery we'd never exercise. Throughput is tuned by choosing N

    // ConcurrentHashMap + LongAdder is the canonical high-contention counter pattern (see notebook Java Q5):
    // CHM gives lock-striped get/put for the "first time we see this extension" path,
    // LongAdder shards increments per CPU cell so concurrent updates don't CAS-contend on one cache line.
    private final ConcurrentHashMap<String, LongAdder> countByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> bytesByType = new ConcurrentHashMap<>();

    // For the size buckets we know the keys at construction time (4 of them), so a plain LongAdder[]
    // indexed by SizeBucket.ordinal() is simpler and faster than another CHM lookup per file.
    private final LongAdder[] countBySize = new LongAdder[SizeBucket.values().length];
    private final LongAdder[] bytesBySize = new LongAdder[SizeBucket.values().length];

    // Cumulative date buckets: today subset-of this-week subset-of this-month subset-of this-year.
    // A file modified today counts in all four. Pre-allocated since the keys are fixed.
    private final LongAdder countToday = new LongAdder();
    private final LongAdder countWeek = new LongAdder();
    private final LongAdder countMonth = new LongAdder();
    private final LongAdder countYear = new LongAdder();
    private final LongAdder bytesToday = new LongAdder();
    private final LongAdder bytesWeek = new LongAdder();
    private final LongAdder bytesMonth = new LongAdder();
    private final LongAdder bytesYear = new LongAdder();
    // Prior years are unknown at construction time (could span decades), so they go in a CHM
    // keyed by the integer year. Each file from a prior year increments exactly one entry.
    private final ConcurrentHashMap<Integer, LongAdder> countByPriorYear = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LongAdder> bytesByPriorYear = new ConcurrentHashMap<>();

    /** Running total of files seen; used for the composition-root headline. */
    private final LongAdder totalFiles = new LongAdder();
    /** Running total of bytes seen; used for the composition-root headline. */
    private final LongAdder totalBytes = new LongAdder();

    // Epoch-milli boundaries computed once at construction so the consumer hot path is just
    // long-compares. Only files from a prior year pay for the Instant->LocalDate conversion
    // (to find their year), which keeps the common case (current-year files) allocation-free.
    private final long yearStartMillis;
    private final long monthStartMillis;
    private final long weekStartMillis;
    private final long todayStartMillis;
    private final long todayEndMillis;   // start of tomorrow; mtime >= this = future-dated, skip
    private final ZoneId zone = ZoneId.systemDefault();

    /** Suppresses the by-extension table in printed output; mirrors the -Dnotypes system property. */
    private static final boolean NO_TYPES = Boolean.getBoolean("notypes");

    /** Spaces prepended to every table line; one named constant beats the same literal duplicated across format strings. */
    private static final int TABLE_INDENT = 4;

    /**
     * What: wires the aggregator to a queue and creates a fixed-size consumer pool.
     * Why:  fixed pool over cached - file aggregation work is bounded, and a cached pool
     *       would spawn one thread per submitted Runnable, defeating the bounded-concurrency
     *       intent. The size-bucket adders are pre-allocated so consumers never allocate
     *       on the hot path.
     */
    public Aggregator(BlockingQueue<FileInfo> queue, int consumerThreads) {
        if (consumerThreads < 1) throw new IllegalArgumentException("consumerThreads must be >= 1");
        this.queue = queue;
        this.consumerThreads = consumerThreads;
        this.pool = Executors.newFixedThreadPool(consumerThreads);
        for (int i = 0; i < countBySize.length; i++) {
            countBySize[i] = new LongAdder();
            bytesBySize[i] = new LongAdder();
        }
        // Week starts on Sunday per the spec; DayOfWeek values are MON=1..SUN=7, so
        // "days since Sunday" is dow.getValue() for Mon-Sat, and 0 if today already is Sunday.
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

    /** Returns the number of consumer threads; the caller uses this count to enqueue exactly one POISON pill per consumer. */
    @Override
    public int consumerCount() { return consumerThreads; }

    /**
     * Returns the factory the producer will call. Computes a lowercase
     * extension or "(none)" for files without one; deliberately scoped to
     * this consumer because no other consumer needs an extension string.
     */
    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new TypeFileInfo(
                extensionOf(path),
                attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    /**
     * Lowercase file extension, or "(none)" for dotfiles and extension-less
     * names. dot==0 (.bashrc) is treated as no extension; a trailing dot
     * also yields "(none)". Package-private so the unit test in the same
     * package can call it directly.
     */
    static String extensionOf(java.nio.file.Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "(none)";
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * What: submits N consumer Runnables to the pool; each one drains the queue until it
     *       receives POISON, then exits.
     * Why:  starting consumers is separate from constructing the aggregator so the caller
     *       can wire everything before any thread starts - avoids subtle init-ordering races.
     */
    @Override
    public void start() {
        for (int i = 0; i < consumerThreads; i++) {
            pool.submit(this::consume);
        }
    }

    /**
     * What: a single consumer's loop - take a FileInfo, update both aggregations, exit on POISON.
     * Why:  exhaustive switch over the sealed FileInfo hierarchy so the compiler enforces that
     *       every variant is handled: a new permitted subtype added to FileInfo will fail to
     *       compile here instead of being silently dropped or surfacing as a ClassCastException
     *       on a pool thread. PathFileInfo is rejected explicitly because this consumer's
     *       factory() only produces TypeFileInfo; reaching that arm means the consumer was
     *       wired to a producer of the wrong shape. computeIfAbsent over get-then-put because
     *       the latter is a check-then-act race that could create duplicate LongAdder instances
     *       on first-sight extensions (see notebook Algo Q12). Package-private so the unit test
     *       in the same package can drive it directly.
     */
    void consume() {
        try {
            while (true) {
                FileInfo f = queue.take();
                switch (f) {
                    case TypeFileInfo t -> {
                        totalFiles.increment();
                        totalBytes.add(t.size());
                        countByType.computeIfAbsent(t.extension(), k -> new LongAdder()).increment();
                        bytesByType.computeIfAbsent(t.extension(), k -> new LongAdder()).add(t.size());
                        SizeBucket b = SizeBucket.of(t.size());
                        countBySize[b.ordinal()].increment();
                        bytesBySize[b.ordinal()].add(t.size());
                        classifyByDate(t.lastModifiedMillis(), t.size());
                    }
                    case PoisonPill ignored -> { return; }
                    case PathFileInfo ignored -> throw new IllegalStateException(
                            "Aggregator received PathFileInfo; its factory() produces only TypeFileInfo");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What: increments the cumulative date buckets (today/week/month/year) when the file's
     *       mtime falls within them, or the appropriate prior-year bucket otherwise.
     * Why:  cumulative not mutually-exclusive because the spec defines the periods as
     *       "Sunday until today including", "1st until today including", etc. - a file from
     *       today belongs in all four current-period buckets. Future-dated files (clock skew
     *       or bad metadata) are silently skipped here; the file still appears in the
     *       extension/size aggregations. Package-private so the unit test in the same
     *       package can drive it directly without spinning up the consumer threads.
     */
    void classifyByDate(long mtime, long size) {
        if (mtime >= todayEndMillis) return;   // future-dated, skip date bucket
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
            // Prior-year files: convert to LocalDate just to get the year; this is the only
            // path that allocates per-file, and only fires for files older than Jan 1 current year.
            int year = Instant.ofEpochMilli(mtime).atZone(zone).getYear();
            countByPriorYear.computeIfAbsent(year, k -> new LongAdder()).increment();
            bytesByPriorYear.computeIfAbsent(year, k -> new LongAdder()).add(size);
        }
    }

    /**
     * What: shuts down the pool, blocks until all consumers have exited, then prints the
     *       aggregation result to the given stream.
     * Why:  shutdown() lets queued tasks complete (it doesn't interrupt) - consumers will
     *       drain remaining items and exit on POISON normally. awaitTermination with a long
     *       bound is effectively "wait forever" but with a finite-time API for tests.
     */
    @Override
    public void awaitAndReport(PrintStream out) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS)) {
            throw new IllegalStateException("aggregator did not terminate within 1 hour");
        }
        printResult(out, snapshot());
    }

    /** Returns the total number of files processed; available after drainers exit. */
    @Override
    public long totalFilesSeen() { return totalFiles.sum(); }

    /** Returns the total bytes across all processed files; available after drainers exit. */
    @Override
    public long totalBytesSeen() { return totalBytes.sum(); }

    /**
     * What: builds an immutable snapshot of the current aggregations.
     * Why:  TreeMap for type-keyed outputs so the printed report has stable alphabetical
     *       ordering; EnumMap for size-keyed outputs so iteration order matches bucket
     *       definition order (LE_1KB first, GT_1GB last) without sorting. Package-private
     *       so the unit test in the same package can verify classifyByDate's effects
     *       without parsing the printed report.
     */
    AggregationResult snapshot() {
        Map<String, Long> countByExt = new TreeMap<>();
        Map<String, Long> bytesByExt = new TreeMap<>();
        countByType.forEach((k, v) -> countByExt.put(k, v.sum()));
        bytesByType.forEach((k, v) -> bytesByExt.put(k, v.sum()));
        Map<SizeBucket, Long> countBySizeMap = new EnumMap<>(SizeBucket.class);
        Map<SizeBucket, Long> bytesBySizeMap = new EnumMap<>(SizeBucket.class);
        for (SizeBucket b : SizeBucket.values()) {
            countBySizeMap.put(b, countBySize[b.ordinal()].sum());
            bytesBySizeMap.put(b, bytesBySize[b.ordinal()].sum());
        }
        // LinkedHashMap so iteration order matches the requested display order:
        // today, this week, this month, this year, then prior years descending.
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
        return new AggregationResult(countByExt, bytesByExt, countBySizeMap, bytesBySizeMap,
                countByDate, bytesByDate);
    }

    /**
     * Prints the by-extension, by-size, and by-date tables to the given stream.
     * The "Done in / Files / TotalBytes" headline is the composition root's
     * job; this method prints only the aggregator-specific body.
     */
    private static void printResult(PrintStream out, AggregationResult r) {
        if (!NO_TYPES) {
            out.println("\nBy extension:");
            out.print(buildTable("extension", 16,
                    r.countByExtension(), r.bytesByExtension(),
                    java.util.function.Function.identity()).indent(TABLE_INDENT));
        }

        out.println("\nBy size bucket:");
        out.print(buildTable("bucket", 20,
                r.countBySizeBucket(), r.bytesBySizeBucket(),
                SizeBucket::label).indent(TABLE_INDENT));

        out.println("\nBy date bucket (cumulative - a file from today is also in this week / month / year):");
        out.print(buildTable("date", 16,
                r.countByDateBucket(), r.bytesByDateBucket(),
                java.util.function.Function.identity()).indent(TABLE_INDENT));
    }

    /**
     * Renders one (count, bytes)-by-key table as a plain string with a
     * header row and one data row per key. Generic on key type so the
     * by-extension (String) and by-size-bucket (SizeBucket) aggregations
     * share the same formatter.
     */
    private static <K> String buildTable(String keyHeader, int keyWidth,
            java.util.Map<K, Long> counts, java.util.Map<K, Long> bytes,
            java.util.function.Function<K, String> keyLabel) {
        String fmt = "%-" + keyWidth + "s %10s %15s%n";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(fmt, keyHeader, "count", "bytes"));
        for (java.util.Map.Entry<K, Long> e : counts.entrySet()) {
            K key = e.getKey();
            long count = e.getValue();
            long byteCount = bytes.getOrDefault(key, 0L);
            sb.append(String.format(fmt,
                    keyLabel.apply(key), count, Format.humanBytes(byteCount)));
        }
        return sb.toString();
    }

}
