package com.example.folderscanner.config;

/**
 * Which consumer pipeline runs against the scanned files.
 *
 * Lives as a typed enum (rather than a free-form string) so the CLI parse and the
 * consumer-construction switch in Main reference the same closed set of values.
 */
public enum ConsumerKind {
    AGGREGATE, DUPLICATES;

    /** Lowercased name used on the CLI and in the run banner. */
    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Parse of the -Dconsumer value. Caller appends a complaint to {@code errors} for unknown
     * input and recovers with the {@code AGGREGATE} default so other validation can continue.
     */
    static ConsumerKind parseOrCollect(String raw, java.util.List<String> errors) {
        if (raw == null) return AGGREGATE;
        switch (raw.trim()) {
        case "aggregate": return AGGREGATE;
        case "duplicates": return DUPLICATES;
        default:
            errors.add("Unknown --consumer: " + raw + " (expected aggregate or duplicates)");
            return AGGREGATE;
        }
    }
}
