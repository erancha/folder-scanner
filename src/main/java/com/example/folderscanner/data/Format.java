package com.example.folderscanner.data;

/** Dependency-free formatting helpers for byte counts and elapsed durations. */
public final class Format {

    private Format() {}

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
        String[][] units = { {"TB", String.valueOf(1024L*1024*1024*1024)},
                             {"GB", String.valueOf(1024L*1024*1024)},
                             {"MB", String.valueOf(1024L*1024)},
                             {"KB", String.valueOf(1024L)},
                             {"B",  "1"} };
        for (String[] u : units) {
            if (t.endsWith(u[0])) {
                mult = Long.parseLong(u[1]);
                t = t.substring(0, t.length() - u[0].length()).trim();
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
