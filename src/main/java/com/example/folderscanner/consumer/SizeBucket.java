package com.example.folderscanner.consumer;

/**
 * Size buckets used by the aggregator. Boundaries are inclusive on the upper side so a file of
 * exactly 1024 bytes lands in LE_1KB (not LE_1MB).
 */
public enum SizeBucket {
    LE_1KB("<= 1KB"), LE_1MB("> 1KB and <= 1MB"), LE_20MB("> 1MB and <= 20MB"),
    LE_1GB("> 20MB and <= 1GB"), GT_1GB("> 1GB");

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;
    private static final long MB_20 = 20L * MB;
    private static final long GB = 1024L * MB;

    private final String label;

    SizeBucket(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static SizeBucket of(long bytes) {
        if (bytes <= KB)
            return LE_1KB;
        if (bytes <= MB)
            return LE_1MB;
        if (bytes <= MB_20)
            return LE_20MB;
        if (bytes <= GB)
            return LE_1GB;
        return GT_1GB;
    }
}
