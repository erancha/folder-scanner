package dev.erancha.folderscanner.consumer;

import dev.erancha.folderscanner.data.ExtensionFileInfo;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.PathFileInfo;
import dev.erancha.folderscanner.data.PoisonPill;
import java.io.PrintStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base FileConsumer that drains the shared producer queue with a pool of identical workers.
 *
 * Owns the plumbing common to every consumer: the DrainerPool wiring, the drain loop, the running
 * file/byte tallies, and the report order — drain to completion (surfacing any failed worker)
 * before the subclass writes its output. Subclasses supply only {@link #accept} to record their one
 * FileInfo variant.
 *
 * @param <M> the FileInfo variant this consumer accepts
 */
public abstract class AbstractFileConsumer<M extends FileInfo> implements FileConsumer {

    protected final Logger logger = LoggerFactory.getLogger(getClass()); // the concrete class
    protected final BlockingQueue<FileInfo> queue;
    private final DrainerPool drainerPool;
    private final Class<M> acceptedType; // The FileInfo variant this consumer's factory() emits.

    // Running totals across every drainer; concurrent, so consume() increments without locking.
    protected final LongAdder totalFiles = new LongAdder();
    protected final LongAdder totalBytes = new LongAdder();

    protected AbstractFileConsumer(BlockingQueue<FileInfo> queue, int consumerThreads,
            String consumerName, Class<M> acceptedType) {
        this.queue = queue;
        this.drainerPool = new DrainerPool(consumerThreads, consumerName);
        this.acceptedType = acceptedType;
    }

    @Override
    public final int drainerCount() {
        return drainerPool.threadCount();
    }

    @Override
    public void start() {
        drainerPool.start(this::consume);
    }

    protected final void consume() {
        try {
            while (true) {
                FileInfo message = queue.take();
                switch (message) {
                case PoisonPill ignored -> {
                    return;
                }
                case ExtensionFileInfo ignored -> tallyAndAccept(message);
                case PathFileInfo ignored -> tallyAndAccept(message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A variant other than this consumer's acceptedType means a mis-wired factory put a foreign
     * message on the queue; surfaced as a named IllegalStateException on the pool thread rather
     * than an anonymous ClassCastException inside accept(). One guard for every consumer, so it
     * cannot drift between them.
     */
    private void tallyAndAccept(FileInfo message) {
        if (!acceptedType.isInstance(message)) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " received " + message.getClass().getSimpleName()
                            + "; its factory() produces only " + acceptedType.getSimpleName());
        }
        totalFiles.increment();
        totalBytes.add(message.size());
        accept(acceptedType.cast(message));
    }

    /** Records one message; the base has already counted it toward the file/byte totals. */
    protected abstract void accept(M message);

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
