package com.example.folderscanner.data;

/**
 * End-of-stream sentinel variant. The shared FileInfo.POISON singleton is
 * the only instance ever created (constructor is package-private); consumers
 * detect it via the PoisonPill arm of their exhaustive pattern-match switch
 * over the sealed FileInfo hierarchy.
 */
public final class PoisonPill implements FileInfo {

    /** Package-private so only FileInfo.POISON can construct one. */
    PoisonPill() {}

    @Override public long size() { return Long.MIN_VALUE; }
    @Override public long lastModifiedMillis() { return 0L; }
}
