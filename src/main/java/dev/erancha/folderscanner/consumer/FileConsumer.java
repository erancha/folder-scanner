package dev.erancha.folderscanner.consumer;

import dev.erancha.folderscanner.producer.FileInfoFactory;
import java.io.PrintStream;

/**
 * SPI (Service Provider Interface) every queue-draining consumer implements. The composition
 * root picks one implementation per run and treats it through this interface only. Each
 * implementation owns its own thread pool, output format, and any post-drain work (e.g. the
 * duplicate locator's phase 2).
 */
public interface FileConsumer {

    /** Caller enqueues exactly this many POISON pills, one per drainer. */
    int drainerCount();

    /** Consumer-supplied so the producer stays agnostic of which FileInfo variant it builds. */
    FileInfoFactory factory();

    void start();

    /** Block until every drainer + post-drain work finishes, then write this consumer's output. */
    void awaitAndReport(PrintStream out) throws InterruptedException;

    long totalFilesSeen();

    long totalBytesSeen();
}
