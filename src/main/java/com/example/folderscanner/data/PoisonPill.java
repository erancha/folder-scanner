package com.example.folderscanner.data;

/**
 * End-of-stream sentinel. Package-private constructor so FileInfo.POISON is the only
 * instance — identity-equality on the consumer side is therefore safe.
 */
public final class PoisonPill implements FileInfo {

    PoisonPill() {}

    @Override public long size() { return Long.MIN_VALUE; }
    @Override public long lastModifiedMillis() { return 0L; }
}
