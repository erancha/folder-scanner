package com.example.folderscanner.config;

/**
 * Bounded-queue implementation choice for the producer-consumer hand-off.
 *
 * Lives as a typed enum (rather than a free-form string) so the CLI parse and the
 * queue-construction switch in Main reference the same closed set of values.
 */
public enum QueueType {
    ABQ, LBQ;

    /** Lowercased name used on the CLI and in the run banner. */
    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Case-insensitive parse of the -Dqueuetype value. Caller appends a complaint to {@code errors}
     * for unknown input and recovers with the {@code ABQ} default so other validation can continue.
     */
    static QueueType parseOrCollect(String raw, java.util.List<String> errors) {
        if (raw == null) return ABQ;
        switch (raw.trim().toLowerCase()) {
        case "abq": return ABQ;
        case "lbq": return LBQ;
        default:
            errors.add("Unknown queue type: " + raw + " (expected lbq or abq)");
            return ABQ;
        }
    }
}
