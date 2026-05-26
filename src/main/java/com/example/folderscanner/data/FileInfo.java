package com.example.folderscanner.data;

/**
 * Common shape for every message a producer enqueues for a consumer.
 * Sealed so consumers can destructure the hierarchy with an exhaustive
 * pattern-match switch: every variant must be handled, and adding a new
 * permitted subtype here is a compile error in every consumer until they
 * update. POISON is the end-of-stream sentinel; consumers detect it via
 * the PoisonPill arm of that switch.
 */
public sealed interface FileInfo permits TypeFileInfo, PathFileInfo, PoisonPill {

    /** Size in bytes of the file this message describes. */
    long size();

    /** Last-modified time in epoch millis. */
    long lastModifiedMillis();

    /** Shared end-of-stream sentinel. One enqueued per consumer thread. */
    FileInfo POISON = new PoisonPill();
}
