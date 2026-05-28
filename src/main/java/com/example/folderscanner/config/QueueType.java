package com.example.folderscanner.config;

/** Bounded-queue implementation choice for the producer-consumer hand-off. */
public enum QueueType {
    ABQ, LBQ;

    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Case-insensitive. Recovers with the {@code ABQ} default on unknown input so other
     * validation can continue; the caller's {@code errors} list carries the complaint forward.
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
