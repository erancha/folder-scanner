package com.example.folderscanner.data;

/**
 * End-of-stream sentinel. Consumers detect it by type in their dispatch switch, so it carries
 * no payload; FileInfo.POISON is the canonical instance and the constructor is package-private
 * to keep construction in one place.
 */
public final class PoisonPill implements FileInfo {

    PoisonPill() {}

    @Override public long size() { return Long.MIN_VALUE; }
    @Override public long lastModifiedMillis() { return 0L; }
}
