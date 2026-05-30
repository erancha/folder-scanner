package dev.erancha.folderscanner.consumer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed pool of queue-draining workers shared by every FileConsumer.
 *
 * Owns the drainer threads plus their Futures and collapses the drain-then-stop sequence each
 * consumer repeats: submit one worker per thread, then on shutdown wait for the queued POISON pills
 * to drain (shutdown, not shutdownNow, so nothing is dropped) and surface any drainer that died.
 * submit() captures an unchecked throw in the task's Future rather than propagating it, so without
 * that last step a pool that lost a drainer still terminates cleanly and the run would report
 * success on an under-counted scan.
 */
public final class DrainerPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DrainerPool.class);

    private static final Duration HEARTBEAT_AFTER = Duration.ofMinutes(10);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(1);

    private final ThreadPoolExecutor pool;
    private final String consumerName;
    private final List<Future<?>> drainers = new ArrayList<>(); // One per submitted drainer, each
                                                                // carrying that drainer's
                                                                // terminal outcome — including any
                                                                // unchecked throw

    public DrainerPool(int threads, String consumerName) {
        if (threads < 1)
            throw new IllegalArgumentException("consumerThreads must be >= 1");
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
        this.consumerName = consumerName;
    }

    public int threadCount() {
        return pool.getCorePoolSize();
    }

    /** Runs one copy of {@code drainer} per thread; each exits when it takes its POISON pill. */
    public void start(Runnable drainer) {
        for (int i = 0, n = pool.getCorePoolSize(); i < n; i++) {
            drainers.add(pool.submit(drainer));
        }
    }

    /**
     * Blocks until all drainers finish. If a drainer died on an unchecked throw, that throw is
     * rethrown here with its original cause attached. A drain still running after
     * {@link #HEARTBEAT_AFTER} logs a periodic heartbeat so a stuck drainer stays observable.
     */
    public void awaitTermination() throws InterruptedException {
        awaitTermination(HEARTBEAT_AFTER, HEARTBEAT_INTERVAL);
    }

    // package-private so tests can drive sub-second silent windows and heartbeat intervals
    void awaitTermination(Duration silentWindow, Duration heartbeatInterval)
            throws InterruptedException {
        pool.shutdown();
        long startNanos = System.nanoTime();
        Duration wait = silentWindow;
        while (!pool.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
            LOGGER.warn("{} still draining after {}s", consumerName,
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos));
            wait = heartbeatInterval;
        }
        for (Future<?> d : drainers) {
            try {
                d.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(consumerName + " drainer failed", e.getCause());
            }
        }
    }
}
