package dev.erancha.folderscanner.config;

/** What the filemanager consumer does with the files surviving the producer filters. */
public enum ManageAction {
    LIST, DELETE;

    public String cliName() {
        return name().toLowerCase();
    }

    /**
     * Recovers with the {@code LIST} default on unknown input so other validation can continue;
     * the caller's {@code errors} list carries the complaint forward.
     */
    static ManageAction parseOrCollect(String raw, java.util.List<String> errors) {
        if (raw == null) return LIST;
        switch (raw.trim().toLowerCase()) {
        case "list": return LIST;
        case "delete": return DELETE;
        default:
            errors.add("Unknown --action: " + raw + " (expected list or delete)");
            return LIST;
        }
    }
}
