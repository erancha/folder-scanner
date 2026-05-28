package com.example.folderscanner;

import com.example.folderscanner.consumer.Aggregator;
import com.example.folderscanner.consumer.DuplicateLocator;
import com.example.folderscanner.consumer.FileConsumer;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
import com.example.folderscanner.producer.FileExtensions;
import com.example.folderscanner.producer.FolderScanner;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root. Wires the producer (FolderScanner), the consumer (Aggregator or
 * DuplicateLocator), and the bounded queue between them. Every tuning knob lives here in one block
 * so it can be overridden from tests or from --combinations runs.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    /**
     * Bounded queue capacity. Small on purpose: put() starts blocking quickly when consumers stall,
     * which is the producer-side OOM (Out Of Memory) defense.
     */
    private static final int QUEUE_CAPACITY = Integer.getInteger("queuesize", 4096);

    private static final boolean STATS_ENABLED = Boolean.getBoolean("stats");
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean OS_MX = (OperatingSystemMXBean) ManagementFactory
            .getOperatingSystemMXBean();

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            LOGGER.error("Not a directory: {}", root);
            System.exit(2);
        }

        // NCPU-scaled (Number of CPU cores) defaults so they travel across machines. Producers run
        // higher than consumers because directory walking is IO-bound (over-subscribing CPUs while
        // waiting on the disk is the win); consumer work per message is small and CPU-bound.
        int ncpu = Runtime.getRuntime().availableProcessors();
        int producers = Integer.getInteger("producers", Math.max(8, ncpu * 4));
        int consumers = Integer.getInteger("consumers", Math.max(4, ncpu * 2));
        if (producers < 1 || consumers < 1) {
            LOGGER.error("producers and consumers must be >= 1");
            System.exit(2);
        }

        // ABQ vs LBQ trade contention shape against allocation:
        // ABQ (default): pre-allocated array, one lock — no per-put allocation but producers and
        // consumers contend on the same lock. Pick when consumer work per item is non-trivial
        // (DuplicateLocator hashing files): queue rarely runs hot, so the shared lock doesn't bite.
        // LBQ: linked nodes, separate put/take locks — one allocation per put, but producers and
        // consumers don't fight for the same lock. Pick when both sides run hot enough that
        // single-lock contention shows up (Aggregator on a warm-cache tree, where per-item
        // consumer work is tiny).
        String queueType = System.getProperty("queuetype", "abq").toLowerCase();
        BlockingQueue<FileInfo> queue;
        switch (queueType) {
        case "lbq" -> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        case "abq" -> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        default -> {
            LOGGER.error("Unknown queue type: {} (expected lbq or abq)", queueType);
            System.exit(2);
            return;
        }
        }

        String consumerName = System.getProperty("consumer", "aggregate");
        String outPath = System.getProperty("out", "");
        boolean hardDelete = Boolean.getBoolean("harddelete");

        long minSizeBytes;
        try {
            minSizeBytes = Format.parseSize(System.getProperty("minsize", "0"));
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid --min-size: {}", e.getMessage());
            System.exit(2);
            return;
        }

        FileExtensions.IncludeSet includeExtensions = FileExtensions.parse(System.getProperty("fileextensions", "*"));
        FileConsumer consumer = switch (consumerName) {
        case "aggregate" -> new Aggregator(queue, consumers);
        case "duplicates" -> new DuplicateLocator(queue, consumers, outPath, hardDelete, root);
        default -> {
            LOGGER.error("Unknown --consumer: {} (expected aggregate or duplicates)", consumerName);
            System.exit(2);
            yield null;
        }
        };

        Set<String> excludeDirs = parseExcludeDirs(System.getProperty("exclude", ".git"));
        if (excludeDirs.isEmpty()) {
            LOGGER.error("--exclude is required: pass a comma-separated list of directory basenames "
                    + "to skip (e.g. --exclude=.git,node_modules,target). "
                    + "See README for recommended lists.");
            System.exit(2);
        }
        FolderScanner scanner = new FolderScanner(queue, producers, consumer.factory(), excludeDirs,
                includeExtensions, minSizeBytes);

        System.out.printf(
                "%nScanning %s (%d threads) ==> consumer=%s (%d threads) thru queue=%s/%d%n", root,
                producers, consumerName, consumers, queueType, QUEUE_CAPACITY);
        System.out.printf("Excluding directories: %s%n", String.join(", ", excludeDirs));

        long t0 = System.nanoTime();
        long cpu0 = OS_MX.getProcessCpuTime();
        consumer.start();

        // Under --stats, prints a live queue-depth/heap/thread snapshot every second so the
        // user can watch backpressure as the scan runs. Needs its own thread because main
        // is about to block in scanner.scan(). Daemon so it can't keep the JVM alive on its own.
        ScheduledExecutorService statsTimer = null;
        if (STATS_ENABLED) {
            statsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stat-reporter");
                t.setDaemon(true);
                return t;
            });
            statsTimer.scheduleAtFixedRate(() -> printStats(queue, t0), 1, 1, TimeUnit.SECONDS);
        }

        try {
            scanner.scan(root);
            // One POISON per consumer; the queue is FIFO so pills come after every real file.
            for (int i = 0; i < consumer.drainerCount(); i++) {
                queue.put(FileInfo.POISON);
            }
        } finally {
            scanner.shutdown();
            if (statsTimer != null)
                statsTimer.shutdown();
        }

        consumer.awaitAndReport(System.out);

        if (minSizeBytes > 0) {
            System.out.printf("%nSkipped (size < %s): %,d files (%s).%n",
                    Format.humanBytes(minSizeBytes), scanner.filteredBySizeCount(),
                    Format.humanBytes(scanner.filteredBySizeBytes()));
        }
        if (!includeExtensions.isAll()) {
            System.out.printf("%nSkipped (extension not in %s): %,d files (%s).%n",
                    includeExtensions.displayList(), scanner.filteredByExtensionCount(),
                    Format.humanBytes(scanner.filteredByExtensionBytes()));
        }

        long elapsedNs = System.nanoTime() - t0;
        long elapsedMs = elapsedNs / 1_000_000;
        System.out.printf("%nDone in %s. Files=%,d  TotalBytes=%s%n",
                Format.formatElapsed(elapsedMs), consumer.totalFilesSeen(),
                Format.humanBytes(consumer.totalBytesSeen()));
        printRunSummary(elapsedNs, cpu0);
        if (STATS_ENABLED)
            printStats(queue, t0);
    }

    private static Set<String> parseExcludeDirs(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null)
            return out;
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (!name.isEmpty())
                out.add(name);
        }
        return out;
    }

    /** cpu% = CPU consumed during run / wall time. >100% means effective multi-core use. */
    private static void printRunSummary(long elapsedNs, long cpuStartNs) {
        long cpuNs = OS_MX.getProcessCpuTime() - cpuStartNs;
        double cpuPct = elapsedNs > 0 ? (cpuNs * 100.0 / elapsedNs) : 0.0;
        int threads = THREAD_MX.getThreadCount();
        int peak = THREAD_MX.getPeakThreadCount();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        System.out.printf("Run stats: threads=%d/peak=%d  heap=%d/%d MB  cpu=%.0f%%%n", threads,
                peak, usedMb, maxMb, cpuPct);
    }

    /**
     * Periodic stats. Queue depth is the most informative signal: pinned at capacity means
     * consumers are the bottleneck; pinned near zero means producers are.
     */
    private static void printStats(BlockingQueue<FileInfo> queue, long startNs) {
        long elapsedS = (System.nanoTime() - startNs) / 1_000_000_000L;
        int threads = THREAD_MX.getThreadCount();
        int peak = THREAD_MX.getPeakThreadCount();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        System.out.printf("[stat T+%3ds] threads=%d/%d  heapUsed=%dMB heapMax=%dMB  queue=%d/%d%n",
                elapsedS, threads, peak, usedMb, maxMb, queue.size(), QUEUE_CAPACITY);
    }
}
