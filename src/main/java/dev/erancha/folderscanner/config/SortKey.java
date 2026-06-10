package dev.erancha.folderscanner.config;

/**
 * Sort dimension for the filemanager {@code --action=list} report. Each key carries the direction
 * used when {@code --order} is omitted: alphabetical paths read most naturally ascending, while
 * date and size listings lead with the newest and largest files the user is usually hunting for.
 */
public enum SortKey {
    PATH(SortOrder.ASC),
    DATE(SortOrder.DESC),
    SIZE(SortOrder.DESC);

    private final SortOrder defaultOrder;

    SortKey(SortOrder defaultOrder) {
        this.defaultOrder = defaultOrder;
    }

    /** Direction applied when the user gives no explicit {@code --order}. */
    public SortOrder defaultOrder() {
        return defaultOrder;
    }

    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Recovers with the {@code PATH} default on unknown input so other validation can continue;
     * the caller's {@code errors} list carries the complaint forward.
     */
    static SortKey parseOrCollect(String raw, java.util.List<String> errors) {
        if (raw == null) return PATH;
        switch (raw.trim().toLowerCase()) {
        case "path": return PATH;
        case "date": return DATE;
        case "size": return SIZE;
        default:
            errors.add("Unknown --sort: " + raw + " (expected path, date or size)");
            return PATH;
        }
    }
}
