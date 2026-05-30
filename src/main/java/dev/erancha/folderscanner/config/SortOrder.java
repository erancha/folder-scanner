package dev.erancha.folderscanner.config;

/** Direction applied to the filemanager listing's sort key. */
public enum SortOrder {
    ASC, DESC;

    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Parses an explicit {@code --order} value. Unlike the other CLI enums this has no recovery
     * default: an absent flag is resolved by the caller to the sort key's natural direction (see
     * {@link SortKey#defaultOrder()}), so only a present-but-unknown value reaches here.
     */
    static SortOrder parseOrCollect(String raw, java.util.List<String> errors) {
        switch (raw.trim().toLowerCase()) {
        case "asc": return ASC;
        case "desc": return DESC;
        default:
            errors.add("Unknown --order: " + raw + " (expected asc or desc)");
            return DESC;
        }
    }
}
