package dev.erancha.folderscanner;

import dev.erancha.folderscanner.config.Cli;
import dev.erancha.folderscanner.config.Config;
import dev.erancha.folderscanner.consumer.FileConsumer;
import dev.erancha.folderscanner.consumer.aggregator.Aggregator;
import dev.erancha.folderscanner.consumer.duplicates.DuplicateLocator;
import dev.erancha.folderscanner.consumer.filemanager.FileManager;
import dev.erancha.folderscanner.consumer.folders.FolderSizeReporter;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.Format;
import dev.erancha.folderscanner.producer.FolderScanner;
import com.sun.management.OperatingSystemMXBean;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * Composition root. Drives the picocli {@link Cli} to obtain a validated {@link Config}, then wires
 * the producer (FolderScanner), the consumer (Aggregator, DuplicateLocator, or FileManager), and
 * the bounded queue between them. CLI string handling lives in Cli, report formatting in
 * ReportPrinter, and the --out tee in ReportTee, so this class only orchestrates the run.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean OS_MX = (OperatingSystemMXBean) ManagementFactory
            .getOperatingSystemMXBean();

    private Main() {
    }

    public static void main(String[] args) {
        int ncpu = Runtime.getRuntime().availableProcessors();
        // benchmarks.sh appends its swept --producers/--consumers after the user's own flags, so an
        // option can appear twice on the command line; accept the repeat (last value wins) instead
        // of rejecting it as a duplicate.
        CommandLine cmd = new CommandLine(new Cli()).setOverwrittenOptionsAllowed(true);
        try {
            cmd.parseArgs(args);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            e.getCommandLine().usage(System.err);
            System.exit(2);
            return;
        }
        Cli cli = cmd.getCommand();
        if (cmd.isUsageHelpRequested()) {
            cmd.usage(System.out);
            if (cli.examples()) {
                System.out.println();
                System.out.print(Cli.examplesText());
            }
            return;
        }
        if (cmd.isVersionHelpRequested()) {
            cmd.printVersionHelp(System.out);
            return;
        }
        // --examples is a --help modifier, not a standalone command; alone it would otherwise fall
        // through into a real scan, so reject it with the same leveled-error-plus-exit-2 contract as
        // other flag misuse.
        if (cli.examples()) {
            LOGGER.error("--examples must be combined with --help");
            System.exit(2);
            return;
        }

        applyLogLevel(cli.logLevel());

        Config cfg;
        Path root;
        try {
            cfg = cli.toConfig(ncpu);
            root = Paths.get(cfg.target()).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            for (String line : e.getMessage().split("\n")) {
                LOGGER.error("{}", line);
            }
            System.exit(2);
            return;
        }
        if (!Files.isDirectory(root)) {
            LOGGER.error("Not a directory: {}", root);
            System.exit(2);
            return;
        }

        try {
            run(cfg, root);
        } catch (Exception e) {
            // Give run()'s failures the same surface as the config-error exits above: one leveled
            // line + exit 2, not an uncaught stack trace.
            Throwable cause = e.getCause();
            LOGGER.error("{}", cause != null ? e.getMessage() + ": " + cause.getMessage()
                    : e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Sets the logback root level at runtime so {@code --log-level} stays a parsed flag, not a -D.
     */
    private static void applyLogLevel(String level) {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(
                ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO));
    }

    static void run(Config cfg, Path root) throws Exception {
        try (ReportTee tee = ReportTee.install(cfg)) {
            PrintStream out = tee.out();
            BlockingQueue<FileInfo> queue = createQueue(cfg);
            FileConsumer consumer = createConsumer(cfg, queue, root);
            FolderScanner scanner = new FolderScanner(queue, cfg.producers(), consumer.factory(),
                    cfg.excludeDirs(), cfg.includeExtensions(), cfg.minSizeBytes());

            printScanHeader(out, cfg, root);

            long t0 = System.nanoTime();
            long cpu0 = OS_MX.getProcessCpuTime();
            consumer.start();
            executeScan(out, scanner, consumer, queue, cfg, root, t0);

            consumer.awaitAndReport(out);
            ReportPrinter.printFilterSummary(out,
                    new ReportPrinter.FilterTally(cfg.minSizeBytes(), scanner.filteredBySizeCount(),
                            scanner.filteredBySizeBytes(), cfg.includeExtensions(),
                            scanner.filteredByExtensionCount(), scanner.filteredByExtensionBytes(),
                            scanner.inaccessibleDirCount(), scanner.inaccessibleFileCount()));

            long elapsedNs = System.nanoTime() - t0;
            ReportPrinter.printDone(out, elapsedNs / 1_000_000, consumer.totalFilesSeen(),
                    consumer.totalBytesSeen());
            printRunSummary(out, elapsedNs, cpu0);

            if (cfg.statsEnabled())
                printStats(out, queue, t0, cfg.queueSize());
        }
    }

    /**
     * ABQ: one shared lock, no per-put allocation — pick when consumer work per item is heavy so
     * the queue rarely runs hot (e.g. DuplicateLocator hashing files). LBQ: split put/take locks,
     * one allocation per put — pick when both sides run hot enough for single-lock contention to
     * bite (e.g. Aggregator on a warm-cache tree).
     */
    private static BlockingQueue<FileInfo> createQueue(Config cfg) {
        return switch (cfg.queueType()) {
        case LBQ -> new LinkedBlockingQueue<>(cfg.queueSize());
        case ABQ -> new ArrayBlockingQueue<>(cfg.queueSize());
        };
    }

    private static FileConsumer createConsumer(Config cfg, BlockingQueue<FileInfo> queue,
            Path root) {
        return switch (cfg.consumerKind()) {
        case AGGREGATE -> new Aggregator(queue, cfg.consumers());
        case DUPLICATES -> new DuplicateLocator(queue, cfg.consumers(), cfg.outPath(),
                cfg.hardDelete(), root);
        case FILEMANAGER -> new FileManager(queue, cfg.consumers(), cfg.outPath(), cfg.action(),
                cfg.hardDelete(), root, cfg.sortKey(), cfg.sortOrder());
        case FOLDERS -> new FolderSizeReporter(queue, cfg.consumers(), root,
                cfg.minSizeRecursiveBytes(), cfg.baselinePath(), cfg.growthThresholdPct());
        };
    }

    private static void printScanHeader(PrintStream out, Config cfg, Path root) {
        out.printf("%n%nScanning %s (%d threads) ==> consumer=%s (%d threads) thru queue=%s/%d%n",
                root, cfg.producers(), cfg.consumerKind().cliName(), cfg.consumers(),
                cfg.queueType().cliName(), cfg.queueSize());
        out.printf("Excluding directories: %s%n", String.join(", ", cfg.excludeDirs()));
        out.printf("Extensions: %s%n", cfg.includeExtensions().displayList());
        out.printf("Min size: %s%n", Format.humanBytes(cfg.minSizeBytes()));
        if (cfg.minSizeRecursiveBytes() > 0) {
            out.printf("Min size recursive: %s%n", Format.humanBytes(cfg.minSizeRecursiveBytes()));
        }
    }

    /**
     * Runs the producer to completion, then feeds one poison pill per drainer so consumers stop.
     * Under --stats a daemon timer prints a live queue-depth/heap/thread snapshot every second
     * while the main thread is blocked in {@code scanner.scan()}.
     */
    private static void executeScan(PrintStream out, FolderScanner scanner, FileConsumer consumer,
            BlockingQueue<FileInfo> queue, Config cfg, Path root, long t0)
            throws InterruptedException {
        ScheduledExecutorService statsTimer = null;
        if (cfg.statsEnabled()) {
            statsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stat-reporter");
                t.setDaemon(true);
                return t;
            });
            statsTimer.scheduleAtFixedRate(() -> printStats(out, queue, t0, cfg.queueSize()), 1, 1,
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
    }

    /** cpu% = CPU consumed during run / wall time. >100% means effective multi-core use. */
    private static void printRunSummary(PrintStream out, long elapsedNs, long cpuStartNs) {
        long cpuNs = OS_MX.getProcessCpuTime() - cpuStartNs;
        double cpuPct = elapsedNs > 0 ? (cpuNs * 100.0 / elapsedNs) : 0.0;
        int threads = THREAD_MX.getThreadCount();
        int peak = THREAD_MX.getPeakThreadCount();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        out.printf("Run stats: threads=%d/peak=%d  heap=%d/%d MB  cpu=%.0f%%%n", threads, peak,
                usedMb, maxMb, cpuPct);
    }

    /**
     * Queue depth is the most informative signal: pinned at capacity means consumers are the
     * bottleneck; pinned near zero means producers are.
     */
    private static void printStats(PrintStream out, BlockingQueue<FileInfo> queue, long startNs,
            int queueCapacity) {
        long elapsedS = (System.nanoTime() - startNs) / 1_000_000_000L;
        int threads = THREAD_MX.getThreadCount();
        int peak = THREAD_MX.getPeakThreadCount();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        out.printf("[stat T+%3ds] threads=%d/%d  heapUsed=%dMB heapMax=%dMB  queue=%d/%d%n",
                elapsedS, threads, peak, usedMb, maxMb, queue.size(), queueCapacity);
    }
}
