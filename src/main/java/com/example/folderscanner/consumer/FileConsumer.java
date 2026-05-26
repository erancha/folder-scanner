package com.example.folderscanner.consumer;

import com.example.folderscanner.producer.FileInfoFactory;
import java.io.PrintStream;

/**
 * Lifecycle SPI for everything that drains the queue. The composition
 * root constructs one implementation per run based on the user's choice
 * and treats every consumer through this interface. Implementations own
 * their own thread pool, their own output format, and any post-drain
 * work (e.g. a second pass over collected state).
 */
public interface FileConsumer {

    /** Number of drainer threads; the composition root enqueues one POISON per drainer. */
    int consumerCount();

    /** Factory the producer will use; supplied here so this consumer gets the variant it needs. */
    FileInfoFactory factory();

    /** Spawn drainer threads. Returns immediately. */
    void start();

    /**
     * Block until all drainers exit AND any post-drain work is done, then
     * print or write this consumer's own output to the given stream.
     */
    void awaitAndReport(PrintStream out) throws InterruptedException;

    /** Used by the composition root for the canonical Files=... headline. */
    long totalFilesSeen();

    /** Used by the composition root for the canonical TotalBytes=... headline. */
    long totalBytesSeen();
}
