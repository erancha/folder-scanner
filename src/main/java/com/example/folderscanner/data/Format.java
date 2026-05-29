package com.example.folderscanner.data;

/** Dependency-free formatting helpers for byte counts and elapsed durations. */
public final class Format {

    private static final java.time.format.DateTimeFormatter TIMESTAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Format() {}

    /** Epoch-milli as local-zone "yyyy-MM-dd HH:mm:ss" for human-readable file listings. */
    public static String formatTimestamp(long epochMillis) {
        return TIMESTAMP.format(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault()));
    }

    /** Elapsed-ms as "X.X s", or "X.X s (Y.Y m)" once the value crosses one minute. */
    public static String formatElapsed(long elapsedMs) {
        double s = elapsedMs / 1000.0;
        if (s >= 60.0) return String.format("%.1f s (%.1f m)", s, s / 60.0);
        return String.format("%.1f s", s);
    }

    /** Byte count in B/KB/MB/GB/TB. Scale rolls up to the unit that keeps the value below 1024. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format("%.2f %s", v, units[i]);
    }

    /**
     * Inverse of humanBytes for round values: parses "N", "NB", "NKB", "NMB", "NGB", "NTB"
     * (case-insensitive, 1024-based). Null/empty returns 0; anything else invalid throws.
     */
    public static long parseSize(String s) {
        if (s == null) return 0;
        String t = s.trim().toUpperCase();
        if (t.isEmpty()) return 0;
        long mult = 1;
        // TB before B: every unit ends in "B", so the longer suffixes must be tested first.
        for (String unit : new String[] {"TB", "GB", "MB", "KB", "B"}) {
            if (t.endsWith(unit)) {
                mult = switch (unit) {
                    case "TB" -> 1024L * 1024 * 1024 * 1024;
                    case "GB" -> 1024L * 1024 * 1024;
                    case "MB" -> 1024L * 1024;
                    case "KB" -> 1024L;
                    default -> 1L;
                };
                t = t.substring(0, t.length() - unit.length()).trim();
                break;
            }
        }
        try {
            long n = Long.parseLong(t);
            if (n < 0) throw new IllegalArgumentException("negative size: " + s);
            return Math.multiplyExact(n, mult);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a size: " + s, e);
        }
    }
}
