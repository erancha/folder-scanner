package com.example.folderscanner.config;

import com.example.folderscanner.data.Format;
import com.example.folderscanner.producer.FileExtensions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Single owner of the user-facing CLI surface: the {@code --flag} names, their help text, and the
 * scan target. picocli converts argument syntax (types, arity, unknown flags); {@link #toConfig}
 * applies the semantic rules and aggregates their failures into one message. The launcher scripts
 * forward args verbatim and hold no flag knowledge, so this class is the single source of truth.
 */
@Command(name = "folder-scanner", mixinStandardHelpOptions = true, version = "folder-scanner 1.0",
        sortOptions = false, usageHelpWidth = 100,
        description = "Concurrently scan a directory tree and either aggregate files by "
                + "extension/size/date, locate duplicate content, or list/delete matching files.")
public final class Cli {

    // Field order is the --help option-list order (sortOptions = false): general knobs, then the
    // producer stage and the filters it applies, then the consumer stage, then the queue between
    // them. Keep new options inside the matching group rather than appending at the end.

    // Each description element is one clause sized to fit the help's description column at width
    // 100, so picocli breaks between clauses rather than mid-sentence.

    // --- General ---
    @Option(names = "--out", paramLabel = "PATH", description = {
            "Mirror output to PATH (omitted = stdout only).",
            "Aggregate: the report; a dir or trailing '/' auto-names aggregator-*.out.",
            "Filemanager --action=list: the listing; auto-names file-list-*.out.",
            "Duplicates / filemanager --action=delete: where to write the .sh."})
    String outPath = "";

    @Option(names = "--stats", description = {
            "Per-second thread, heap, and queue-depth snapshot during the scan."})
    boolean stats;

    @Option(names = "--log-level", paramLabel = "L", description = {
            "Logback root level (default INFO).",
            "DEBUG surfaces scanner/locator skip diagnostics."})
    String logLevel = "INFO";

    // --- Producers (folder walkers) and the filtering they apply before the queue ---
    @Option(names = "--producers", paramLabel = "N", description = {
            "Scanner (folder-walker) threads. Default NCPU*16.",
            "Directory walking is IO-bound, so over-subscribing CPUs is intentional."})
    Integer producers;

    @Option(names = "--min-size", paramLabel = "SIZE", description = {
            "Skip files smaller than SIZE at the producer (applies to all consumers).",
            "1024-based: raw bytes or NB/NKB/NMB/NGB/NTB (e.g. 1MB, 512KB). Default 0."})
    String minSizeRaw = "0";

    @Option(names = "--file-extensions", paramLabel = "LIST", description = {
            "Comma-separated extensions to include; others are skipped at the producer.",
            "Case-insensitive, leading dot optional. 'none' = extension-less files.",
            "Default * (no filter)."})
    String fileExtRaw = "*";

    @Option(names = "--exclude", paramLabel = "LIST", description = {
            "Comma-separated directory basenames to skip, plus the always-skipped .git.",
            "Quote the whole flag for names with spaces. Required (default .git)."})
    String excludeRaw = ".git";

    // --- Consumers ---
    @Option(names = "--consumer", paramLabel = "NAME", description = {
            "Consumer pipeline. aggregate (default): by-extension/size/date tables.",
            "duplicates: emit a script that quarantines or deletes redundant copies.",
            "filemanager: list or delete the files surviving the producer filters."})
    String consumerRaw = "aggregate";

    @Option(names = "--consumers", paramLabel = "N", description = {
            "Consumer drainer threads. Default max(4, NCPU*2).",
            "Also sizes the duplicate locator's phase-2 hashing pool."})
    Integer consumers;

    // null = flag absent (so --action with a non-filemanager consumer can be rejected as misuse);
    // ManageAction.parseOrCollect maps null to the LIST default.
    @Option(names = "--action", paramLabel = "A", description = {
            "Filemanager mode. list (default): print path/size/modified per file.",
            "delete: emit a script that quarantines (or, with --hard-delete, removes) them."})
    String actionRaw;

    @Option(names = "--hard-delete", description = {
            "Duplicates, or filemanager --action=delete. Generate rm instead of soft-delete moves.",
            "The script still prompts for the literal string DELETE first."})
    boolean hardDelete;

    @Option(names = "--sort", paramLabel = "KEY", description = {
            "Filemanager --action=list sort key. path (default): alphabetical.",
            "date: by modified time. size: by file size."})
    String sortRaw;

    // null = flag absent, so the effective direction falls back to the sort key's natural default
    // (path ascends; date/size lead with newest/largest) rather than a fixed asc/desc.
    @Option(names = "--order", paramLabel = "DIR", description = {
            "Listing sort direction: asc or desc.",
            "Default: path ascends; date and size lead with newest/largest first."})
    String orderRaw;

    // --- Bounded queue between producers and consumers ---
    @Option(names = "--queue-type", paramLabel = "T", description = {
            "Bounded-queue impl (default abq).",
            "abq = ArrayBlockingQueue: one lock, no per-put allocation.",
            "lbq = LinkedBlockingQueue: split put/take locks, per-put allocation."})
    String queueTypeRaw = "abq";

    @Option(names = "--queue-size", paramLabel = "N", description = {
            "Bounded queue capacity (default 4096).",
            "Larger lets the producer run further ahead before backpressure."})
    Integer queueSize;

    @Parameters(arity = "0..1", paramLabel = "PATH",
            description = "Directory to scan (default: current directory).")
    String target = ".";

    /** Logback root level requested via {@code --log-level}, defaulting to INFO. */
    public String logLevel() {
        return logLevel;
    }

    /**
     * Builds the validated {@link Config}. {@code ncpu} is a parameter (not read from the runtime)
     * so the same NCPU-scaled defaults can be reproduced on a different host. Every semantic
     * failure is collected so the user sees all mistakes from one run rather than one at a time.
     */
    public Config toConfig(int ncpu) {
        List<String> errors = new ArrayList<>();

        int queueSizeV = queueSize != null ? queueSize : 4096;
        int producersV = producers != null ? producers : ncpu * 16;
        int consumersV = consumers != null ? consumers : Math.max(4, ncpu * 2);
        if (producersV < 1) errors.add("producers must be >= 1");
        if (consumersV < 1) errors.add("consumers must be >= 1");

        QueueType queueType = QueueType.parseOrCollect(queueTypeRaw, errors);
        ConsumerKind consumerKind = ConsumerKind.parseOrCollect(consumerRaw, errors);
        ManageAction action = ManageAction.parseOrCollect(actionRaw, errors);

        SortKey sortKey = SortKey.parseOrCollect(sortRaw, errors);
        SortOrder sortOrder = orderRaw != null
                ? SortOrder.parseOrCollect(orderRaw, errors)
                : sortKey.defaultOrder();

        if (consumerKind != ConsumerKind.FILEMANAGER && actionRaw != null) {
            errors.add("--action only applies with --consumer=filemanager");
        }
        boolean filemanagerList = consumerKind == ConsumerKind.FILEMANAGER
                && action == ManageAction.LIST;
        if ((sortRaw != null || orderRaw != null) && !filemanagerList) {
            errors.add("--sort/--order only apply with --consumer=filemanager --action=list");
        }
        boolean deletingFiles = consumerKind == ConsumerKind.FILEMANAGER
                && action == ManageAction.DELETE;
        if (hardDelete && consumerKind != ConsumerKind.DUPLICATES && !deletingFiles) {
            errors.add("--hard-delete only applies with --consumer=duplicates or "
                    + "--consumer=filemanager --action=delete");
        }

        long minSizeBytes = 0L;
        try {
            minSizeBytes = Format.parseSize(minSizeRaw);
        } catch (IllegalArgumentException e) {
            errors.add("Invalid --min-size: " + e.getMessage());
        }

        FileExtensions.IncludeSet includeExtensions = FileExtensions.parse(fileExtRaw);

        Set<String> excludeDirs = parseExcludeDirs(excludeRaw);
        if (excludeDirs.isEmpty()) {
            errors.add("--exclude is required: pass a comma-separated list of directory basenames "
                    + "to skip (e.g. --exclude=.git,node_modules,target). "
                    + "See README for recommended lists.");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }

        return new Config(queueSizeV, stats, producersV, consumersV, queueType, consumerKind,
                action, sortKey, sortOrder, outPath, hardDelete, minSizeBytes, excludeDirs,
                includeExtensions, target);
    }

    private static Set<String> parseExcludeDirs(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }
}
