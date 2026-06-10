package dev.erancha.folderscanner.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    private final ThreadPoolExecutor pool;
    private final String consumerName;
    private final List<Future<?>> drainers = new ArrayList<>(); // One Future per drainer, carrying its terminal outcome (incl. unchecked throw).

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
     * rethrown here with its original cause attached.
     */
    public void awaitTermination() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        for (Future<?> d : drainers) {
            try {
                d.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(consumerName + " drainer failed", e.getCause());
            }
        }
    }
}
