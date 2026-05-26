package com.example.folderscanner.data;

/**
 * Pure-function formatting helpers for byte counts and elapsed durations.
 * Placed in the data package because it is dependency-free and can be
 * imported from anywhere that displays such values without dragging in
 * producer- or consumer-side dependencies.
 */
public final class Format {

    private Format() {}   // no instances

    /**
     * Formats an elapsed-ms value as "X.X s", or "X.X s (Y.Y m)" when the
     * value is at least 60 seconds. The dual unit keeps both numbers
     * visible when a scan runs into minutes.
     */
    public static String formatElapsed(long elapsedMs) {
        double s = elapsedMs / 1000.0;
        if (s >= 60.0) return String.format("%.1f s (%.1f m)", s, s / 60.0);
        return String.format("%.1f s", s);
    }

    /**
     * Pretty-prints a byte count in B / KB / MB / GB / TB. The scale rolls
     * up to whichever unit keeps the printed number below 1024.
     */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format("%.2f %s", v, units[i]);
    }

    /**
     * Parses a byte-count string of the form "N", "NB", "NKB", "NMB", "NGB",
     * or "NTB" (case-insensitive, optional whitespace). The unit suffix uses
     * the same 1024-based scale as humanBytes, so parseSize is its inverse
     * for round numbers. Empty or null input returns 0. Invalid input throws
     * IllegalArgumentException.
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
