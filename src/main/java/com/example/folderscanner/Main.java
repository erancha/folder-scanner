package com.example.folderscanner;

import com.example.folderscanner.consumer.Aggregator;
import com.example.folderscanner.consumer.DuplicateLocator;
import com.example.folderscanner.consumer.FileConsumer;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
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

/**
 * What: composition root that wires a FolderScanner (producer) and an Aggregator (consumer)
 *       through a bounded queue, runs them against a user-supplied directory, and prints the
 *       aggregated result.
 * Why:  manual constructor wiring (no Spring) keeps the dependencies explicit - every choice
 *       (queue capacity, scanner parallelism, consumer count) lives here in one block so it
 *       can be tuned or stubbed in tests without touching the workers.
 */
public final class Main {

    /**
     * What: bounded queue capacity, defaulting to 4096. Overridable via -Dqueuesize (set by
     *       start.sh --queue-size).
     * Why:  small enough that put() starts blocking quickly when consumers stall - that's the
     *       OOM defense; configurable so we can compare e.g. 4096 vs 8192 against both LBQ
     *       and ABQ.
     */
    private static final int QUEUE_CAPACITY = Integer.getInteger("queuesize", 4096);

    /**
     * What: enables once-per-second stdout stats.
     * Why:  -Dstat=true (set by start.sh --stat) makes it opt-in so normal runs stay quiet.
     */
    private static final boolean STAT = Boolean.getBoolean("stat");

    /**
     * What: cached ThreadMXBean.
     * Why:  ManagementFactory.getThreadMXBean() returns a singleton; cache the ref so the
     *       stat callback doesn't re-fetch each tick.
     */
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    /**
     * What: cached OperatingSystemMXBean (Sun-specific subtype).
     * Why:  needed for getProcessCpuTime() so we can report cpu% as total CPU consumed / wall
     *       time. On a parallel program this naturally exceeds 100% (e.g. 380% on 4 cores
     *       means we kept ~3.8 cores busy on average), which is exactly the signal we want.
     */
    private static final OperatingSystemMXBean OS_MX = (OperatingSystemMXBean) ManagementFactory
            .getOperatingSystemMXBean();

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("Not a directory: " + root);
            System.exit(2);
        }

        // -Dproducers / -Dconsumers (set by start.sh --producers / --consumers)
        // override the defaults. Defaults below come from the --combinations-q
        // benchmark on a ~1M-file IO-bound tree on /mnt/c (WSL): producers=100 +
        // consumers=48 was on the winning combination (352.4 s, lowest peak thread
        // count among the top 4 results within 0.6%). On small/fast trees, lower
        // values like producers=24 / consumers=16 may be faster - tune with
        // ./start.sh --combinations for your workload.
        int scannerParallelism = Integer.getInteger("producers", 100);
        int consumerThreads = Integer.getInteger("consumers", 48);
        if (scannerParallelism < 1 || consumerThreads < 1) {
            System.err.println("producers and consumers must be >= 1");
            System.exit(2);
        }

        // -Dqueuetype (set by start.sh --queue-type) selects the BlockingQueue impl:
        // abq -> ArrayBlockingQueue (default): pre-allocated array, zero per-put
        //        allocation, single lock. Wins on long IO-bound scans where LBQ's
        //        ~280MB of per-put Node garbage triggers GC pauses (see
        //        --combinations-q analysis: ABQ ~3x tighter tail and no outliers).
        // lbq -> LinkedBlockingQueue: linked nodes, one allocation per put, separate
        //        putLock + takeLock. Slightly faster on small/fast scans where GC
        //        cost is negligible and the two-lock design wins.
        // Both implement BlockingQueue<E> with identical put/take semantics, so the
        // producer (FolderScanner) and consumer (Aggregator) are agnostic.
        String queueType = System.getProperty("queuetype", "abq").toLowerCase();
        // APPLICATION QUEUE: producer (e.g. FolderScanner) -> consumer (e.g. Aggregator) handoff.
        BlockingQueue<FileInfo> queue;
        switch (queueType) {
            case "lbq" -> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            case "abq" -> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            default -> {
                System.err.println("Unknown queue type: " + queueType + " (expected lbq or abq)");
                System.exit(2);
                return; // unreachable, keeps the compiler happy about queue being unassigned
            }
        }
        String consumerName = System.getProperty("consumer", "aggregate");
        String outPath     = System.getProperty("out", "");
        boolean hardDelete = Boolean.getBoolean("harddelete");
        // -Dminsize (set by start.sh --min-size) filters small files out of the
        // duplicate locator before bucketing. Parsed here at the boundary so
        // bad input fails fast with a single clean error.
        long minSizeBytes;
        try {
            minSizeBytes = Format.parseSize(System.getProperty("minsize", "0"));
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid --min-size: " + e.getMessage());
            System.exit(2);
            return;
        }
        FileConsumer consumer = switch (consumerName) {
            case "aggregate"  -> new Aggregator(queue, consumerThreads);
            case "duplicates" -> new DuplicateLocator(queue, consumerThreads, outPath, hardDelete, root, minSizeBytes);
            default -> {
                System.err.println("Unknown --consumer: " + consumerName
                        + " (expected aggregate or duplicates)");
                System.exit(2);
                yield null;
            }
        };
        // -Dexclude (set by start.sh --exclude) is a comma-separated list of directory
        // basenames to skip during the walk (e.g. node_modules,target,.mvn,.git). Parsed
        // here at the boundary so the FolderScanner stays a plain Set-consuming API.
        // Required: an empty list would walk every directory including .git ref files and
        // per-app caches, which the duplicate locator would then flag for deletion — almost
        // always the wrong default. The policy lives in start.sh / README, not in Java.
        Set<String> excludeDirs = parseExcludeDirs(System.getProperty("exclude", ""));
        if (excludeDirs.isEmpty()) {
            System.err.println("--exclude is required: pass a comma-separated list of directory basenames "
                    + "to skip (e.g. --exclude=.git,node_modules,target). See README for recommended lists.");
            System.exit(2);
        }
        FolderScanner scanner = new FolderScanner(queue, scannerParallelism, consumer.factory(), excludeDirs);

        System.out.printf("Scanning %s  (consumer=%s  scanner=%d  consumer-threads=%d  queue=%s/%d)%n",
                root, consumerName, scannerParallelism, consumerThreads, queueType, QUEUE_CAPACITY);
        System.out.printf("Excluding directories: %s%n", String.join(", ", excludeDirs));

        long t0 = System.nanoTime();
        long cpu0 = OS_MX.getProcessCpuTime(); // baseline so the reported cpu% excludes JVM startup work
        consumer.start();

        ScheduledExecutorService statsTimer = null;
        if (STAT) {
            statsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stat-reporter");
                t.setDaemon(true); // daemon so it never blocks JVM exit if we forget to stop it
                return t;
            });
            statsTimer.scheduleAtFixedRate(() -> printStats(queue, t0), 1, 1, TimeUnit.SECONDS);
        }

        try {
            scanner.scan(root);
            // Producer side is done. Enqueue one POISON per consumer so each consumer
            // takes exactly one and exits cleanly; the queue's FIFO ordering guarantees
            // pills are taken last (after every real FileInfo already enqueued).
            for (int i = 0; i < consumer.consumerCount(); i++) {
                queue.put(FileInfo.POISON);
            }
        } finally {
            scanner.shutdown();
            if (statsTimer != null)
                statsTimer.shutdown();
        }

        consumer.awaitAndReport(System.out);

        long elapsedNs = System.nanoTime() - t0;
        long elapsedMs = elapsedNs / 1_000_000;
        System.out.printf("%nDone in %s. Files=%,d  TotalBytes=%s%n",
                Format.formatElapsed(elapsedMs),
                consumer.totalFilesSeen(),
                Format.humanBytes(consumer.totalBytesSeen()));
        printRunSummary(elapsedNs, cpu0);
        if (STAT)
            printStats(queue, t0); // one final tick so the closing thread/heap state is in the log
    }

    /**
     * What: splits a comma-separated -Dexclude value into a set of directory basenames,
     *       trimming whitespace and ignoring empty entries.
     * Why:  this is the boundary between the shell-level flag and the strongly-typed
     *       Set&lt;String&gt; the FolderScanner takes; parsing once here keeps the producer
     *       agnostic of how the user supplied the list.
     */
    private static Set<String> parseExcludeDirs(String raw) {
        Set<String> out = new LinkedHashSet<>(); // preserves user order for the startup banner
        if (raw == null) return out;
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /**
     * What: prints a one-line run-stats summary at end of run - threads, heap, cpu%.
     * Why:  the user always wants to see this for the headline run, not just under --stat
     *       (which is for periodic ticks). cpu% is total process CPU consumed during the run
     *       divided by wall time; values above 100% indicate effective multi-core utilisation.
     */
    private static void printRunSummary(long elapsedNs, long cpuStartNs) {
        long cpuNs = OS_MX.getProcessCpuTime() - cpuStartNs;
        double cpuPct = elapsedNs > 0 ? (cpuNs * 100.0 / elapsedNs) : 0.0;
        int threads = THREAD_MX.getThreadCount();
        int peak = THREAD_MX.getPeakThreadCount();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        System.out.printf("Run stats: threads=%d/peak=%d  heap=%d/%d MB  cpu=%.0f%%%n",
                threads, peak, usedMb, maxMb, cpuPct);
    }

    /**
     * What: prints a one-line stats snapshot - elapsed seconds, live thread count and peak,
     *       heap used vs max, and queue depth.
     * Why:  three things a senior would actually want to see while watching this run:
     *       (1) thread count - confirms scanner/aggregator workers are alive and JVM internals
     *           are reasonable;
     *       (2) heap used - the OOM-defense check (should stay flat under backpressure, not
     *           climb monotonically);
     *       (3) queue depth - if it's pinned at capacity, consumers are the bottleneck; if
     *           it's near zero, producers are.
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
