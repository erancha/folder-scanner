package com.example.folderscanner.consumer;

/**
 * Four size buckets used by the aggregator. Boundaries are inclusive on
 * the upper side so a file of exactly 1024 bytes lands in LE_1KB.
 */
public enum SizeBucket {
    LE_1KB("<= 1KB"),
    LE_1MB("> 1KB and <= 1MB"),
    LE_1GB("> 1MB and <= 1GB"),
    GT_1GB("> 1GB");

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;
    private static final long GB = 1024L * MB;

    /** Human-readable label used by the printed report. */
    private final String label;

    SizeBucket(String label) { this.label = label; }

    public String label() { return label; }

    /** Maps a byte count to its bucket. Cascading <= reads top-down. */
    public static SizeBucket of(long bytes) {
        if (bytes <= KB) return LE_1KB;
        if (bytes <= MB) return LE_1MB;
        if (bytes <= GB) return LE_1GB;
        return GT_1GB;
    }
}
