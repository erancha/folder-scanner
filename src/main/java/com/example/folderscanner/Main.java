package com.example.folderscanner;

import com.example.folderscanner.config.Cli;
import com.example.folderscanner.config.Config;
import com.example.folderscanner.config.ConsumerKind;
import com.example.folderscanner.consumer.FileConsumer;
import com.example.folderscanner.consumer.aggregator.Aggregator;
import com.example.folderscanner.consumer.duplicates.DuplicateLocator;
import com.example.folderscanner.consumer.duplicates.OutPathResolver;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
import com.example.folderscanner.producer.FolderScanner;
import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * the producer (FolderScanner), the consumer (Aggregator or DuplicateLocator), and the bounded
 * queue between them. All CLI string handling lives in Cli so this class only orchestrates the run.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean OS_MX = (OperatingSystemMXBean) ManagementFactory
            .getOperatingSystemMXBean();

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int ncpu = Runtime.getRuntime().availableProcessors();
        // Last-wins for repeated options: the benchmark harness appends sweep flags after the
        // forwarded user flags and relies on the later value winning rather than erroring.
        CommandLine cmd = new CommandLine(new Cli()).setOverwrittenOptionsAllowed(true);
        try {
            cmd.parseArgs(args);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            e.getCommandLine().usage(System.err);
            System.exit(2);
            return;
        }
        if (cmd.isUsageHelpRequested()) {
            cmd.usage(System.out);
            return;
        }
        if (cmd.isVersionHelpRequested()) {
            cmd.printVersionHelp(System.out);
            return;
        }

        Cli cli = cmd.getCommand();
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

        run(cfg, root);
    }

    /** Sets the logback root level at runtime so {@code --log-level} stays a parsed flag, not a -D. */
    private static void applyLogLevel(String level) {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO));
    }

    static void run(Config cfg, Path root) throws Exception {
        // Aggregate --out tees stdout (the report) to a file. A directory / trailing-slash target
        // is auto-named; a verbatim path is used as-is. Diagnostics on stderr stay terminal-only.
        PrintStream teeFile = null;
        if (cfg.consumerKind() == ConsumerKind.AGGREGATE && !cfg.outPath().isEmpty()) {
            String stamp = "aggregator-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".out";
            Path outFile = OutPathResolver.resolve(cfg.outPath(), stamp);
            teeFile = new PrintStream(new BufferedOutputStream(Files.newOutputStream(outFile)), false,
                    StandardCharsets.UTF_8);
            System.setOut(new PrintStream(new TeeOutputStream(System.out, teeFile), true,
                    StandardCharsets.UTF_8));
        }

        try {
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
                    "%nScanning %s (%d threads) ==> consumer=%s (%d threads) thru queue=%s/%d%n",
                    root, cfg.producers(), cfg.consumerKind().cliName(), cfg.consumers(),
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

            long elapsedNs = System.nanoTime() - t0;
            long elapsedMs = elapsedNs / 1_000_000;
            System.out.printf("%nDone in %s. Files=%,d  TotalBytes=%s%n",
                    Format.formatElapsed(elapsedMs), consumer.totalFilesSeen(),
                    Format.humanBytes(consumer.totalBytesSeen()));
            printRunSummary(elapsedNs, cpu0);
            if (cfg.statsEnabled())
                printStats(queue, t0, cfg.queueSize());
        } finally {
            if (teeFile != null) {
                System.out.flush();
                teeFile.close();
            }
        }
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

    /** Fans each byte to two streams so aggregate {@code --out} can mirror stdout to a file. */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeOutputStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int x) throws IOException {
            a.write(x);
            b.write(x);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}
