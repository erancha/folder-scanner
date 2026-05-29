package com.example.folderscanner;

import com.example.folderscanner.config.Config;
import com.example.folderscanner.consumer.FileConsumer;
import com.example.folderscanner.consumer.aggregator.Aggregator;
import com.example.folderscanner.consumer.duplicates.DuplicateLocator;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
import com.example.folderscanner.producer.FolderScanner;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root. Parses the typed {@link Config} from system properties, then wires the producer
 * (FolderScanner), the consumer (Aggregator or DuplicateLocator), and the bounded queue between
 * them. Every tuning knob comes from Config so CLI string handling stays at the boundary.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean OS_MX = (OperatingSystemMXBean) ManagementFactory
            .getOperatingSystemMXBean();

    private Main() {
    }

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
        Config cfg;
        try {
            cfg = Config.parse(System.getProperties(), ncpu);
        } catch (IllegalArgumentException e) {
            for (String line : e.getMessage().split("\n")) {
                LOGGER.error("{}", line);
            }
            System.exit(2);
            return;
        }

        // ABQ: one shared lock, no per-put allocation — pick when consumer work per item is
        // heavy so the queue rarely runs hot (e.g. DuplicateLocator hashing files).
        // LBQ: split put/take locks, one allocation per put — pick when both sides run hot
        // enough for single-lock contention to bite (e.g. Aggregator on a warm-cache tree).
        BlockingQueue<FileInfo> queue = switch (cfg.queueType()) {
        case LBQ -> new LinkedBlockingQueue<>(cfg.queueSize());
        case ABQ -> new ArrayBlockingQueue<>(cfg.queueSize());
        };

        FileConsumer consumer = switch (cfg.consumerKind()) {
        case AGGREGATE -> new Aggregator(queue, cfg.consumers());
        case DUPLICATES -> new DuplicateLocator(queue, cfg.consumers(), cfg.outPath(),
                cfg.hardDelete(), root);
        };

        FolderScanner scanner = new FolderScanner(queue, cfg.producers(), consumer.factory(),
                cfg.excludeDirs(), cfg.includeExtensions(), cfg.minSizeBytes());

        System.out.printf(
                "%nScanning %s (%d threads) ==> consumer=%s (%d threads) thru queue=%s/%d%n", root,
                cfg.producers(), cfg.consumerKind().cliName(), cfg.consumers(),
                cfg.queueType().cliName(), cfg.queueSize());
        System.out.printf("Excluding directories: %s%n", String.join(", ", cfg.excludeDirs()));

        long t0 = System.nanoTime();
        long cpu0 = OS_MX.getProcessCpuTime();
        consumer.start();

        // Under --stats, prints a live queue-depth/heap/thread snapshot every second so the
        // user can watch backpressure as the scan runs. Needs its own thread because main
        // is about to block in scanner.scan(). Daemon so it can't keep the JVM alive on its own.
        ScheduledExecutorService statsTimer = null;
        if (cfg.statsEnabled()) {
            statsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stat-reporter");
                t.setDaemon(true);
                return t;
            });
            statsTimer.scheduleAtFixedRate(() -> printStats(queue, t0, cfg.queueSize()), 1, 1,
                    TimeUnit.SECONDS);
        }

        try {
            scanner.scan(root);
            for (int i = 0; i < consumer.drainerCount(); i++) {
                queue.put(FileInfo.POISON);
            }
        } finally {
            scanner.shutdown();
            if (statsTimer != null)
                statsTimer.shutdown();
        }

        consumer.awaitAndReport(System.out);

        if (cfg.minSizeBytes() > 0) {
            System.out.printf("%nSkipped (size < %s): %,d files (%s).%n",
                    Format.humanBytes(cfg.minSizeBytes()), scanner.filteredBySizeCount(),
                    Format.humanBytes(scanner.filteredBySizeBytes()));
        }
        if (!cfg.includeExtensions().isAll()) {
            System.out.printf("%nSkipped (extension not in %s): %,d files (%s).%n",
                    cfg.includeExtensions().displayList(), scanner.filteredByExtensionCount(),
                    Format.humanBytes(scanner.filteredByExtensionBytes()));
        }
        long inaccessibleDirs = scanner.inaccessibleDirCount();
        long inaccessibleFiles = scanner.inaccessibleFileCount();
        if (inaccessibleDirs > 0 || inaccessibleFiles > 0) {
            System.out.printf(
                    "%nInaccessible (permission denied or IO error): %,d directories, %,d files "
                            + "— their contents are absent from this report.%n",
                    inaccessibleDirs, inaccessibleFiles);
        }

        long elapsedNs = System.nanoTime() - t0;
        long elapsedMs = elapsedNs / 1_000_000;
        System.out.printf("%nDone in %s. Files=%,d  TotalBytes=%s%n",
                Format.formatElapsed(elapsedMs), consumer.totalFilesSeen(),
                Format.humanBytes(consumer.totalBytesSeen()));
        printRunSummary(elapsedNs, cpu0);
        if (cfg.statsEnabled())
            printStats(queue, t0, cfg.queueSize());
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
     * Queue depth is the most informative signal: pinned at capacity means consumers are the
     * bottleneck; pinned near zero means producers are.
     */
    private static void printStats(BlockingQueue<FileInfo> queue, long startNs, int queueCapacity) {
        long elapsedS = (System.nanoTime() - startNs) / 1_000_000_000L;
        int threads = THREAD_MX.getThreadCount();
        int peak = THREAD_MX.getPeakThreadCount();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        System.out.printf("[stat T+%3ds] threads=%d/%d  heapUsed=%dMB heapMax=%dMB  queue=%d/%d%n",
                elapsedS, threads, peak, usedMb, maxMb, queue.size(), queueCapacity);
    }
}
