package com.example.folderscanner.consumer;

import com.example.folderscanner.data.FileInfo;
import java.io.PrintStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base FileConsumer that drains the shared producer queue with a pool of identical workers.
 *
 * Owns the plumbing every consumer would otherwise repeat — the DrainerPool wiring, the running
 * file/byte tallies, and the report order: drain to completion (surfacing any failed worker) before
 * the subclass writes its output.
 */
public abstract class AbstractFileConsumer implements FileConsumer {

    protected final Logger logger = LoggerFactory.getLogger(getClass()); // the concrete class
    protected final BlockingQueue<FileInfo> queue;
    private final DrainerPool drainerPool;

    // Running totals across every drainer; concurrent, so consume() increments without locking.
    protected final LongAdder totalFiles = new LongAdder();
    protected final LongAdder totalBytes = new LongAdder();

    protected AbstractFileConsumer(BlockingQueue<FileInfo> queue, int consumerThreads,
            String consumerName) {
        this.queue = queue;
        this.drainerPool = new DrainerPool(consumerThreads, consumerName);
    }

    @Override
    public final int drainerCount() {
        return drainerPool.threadCount();
    }

    @Override
    public void start() {
        drainerPool.start(this::consume);
    }

    /** Drains the queue one message at a time until this worker takes its POISON pill. */
    protected abstract void consume();

    @Override
    public final void awaitAndReport(PrintStream out) throws InterruptedException {
        drainerPool.awaitTermination();
        report(out);
    }

    @Override
    public final long totalFilesSeen() {
        return totalFiles.sum();
    }

    @Override
    public final long totalBytesSeen() {
        return totalBytes.sum();
    }

    /** Writes this consumer's output once every drainer has finished. */
    protected abstract void report(PrintStream out);
}
