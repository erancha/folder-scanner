package com.example.folderscanner.data;

/**
 * Sealed message interface for the producer/consumer queue. Sealed so each consumer's
 * dispatch switch is exhaustive: adding a new permitted variant breaks every consumer
 * at compile time instead of being silently dropped at runtime.
 */
public sealed interface FileInfo permits TypeFileInfo, PathFileInfo, PoisonPill {

    long size();

    long lastModifiedMillis();

    /** End-of-stream sentinel. The composition root enqueues one per drainer thread. */
    FileInfo POISON = new PoisonPill();
}
