package com.example.folderscanner.config;

/** Which consumer pipeline runs against the scanned files. */
public enum ConsumerKind {
    AGGREGATE, DUPLICATES, FILEMANAGER;

    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Recovers with the {@code AGGREGATE} default on unknown input so other validation can
     * continue; the caller's {@code errors} list carries the complaint forward.
     */
    static ConsumerKind parseOrCollect(String raw, java.util.List<String> errors) {
        if (raw == null) return AGGREGATE;
        switch (raw.trim()) {
        case "aggregate": return AGGREGATE;
        case "duplicates": return DUPLICATES;
        case "filemanager": return FILEMANAGER;
        default:
            errors.add("Unknown --consumer: " + raw
                    + " (expected aggregate, duplicates or filemanager)");
            return AGGREGATE;
        }
    }
}
