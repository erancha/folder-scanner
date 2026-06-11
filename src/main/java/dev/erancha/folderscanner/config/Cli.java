package dev.erancha.folderscanner.config;

import dev.erancha.folderscanner.data.Format;
import dev.erancha.folderscanner.producer.FileExtensions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Single owner of the user-facing CLI surface: the {@code --flag} names, their help text, and the
 * scan target. picocli converts argument syntax (types, arity, unknown flags); {@link #toConfig}
 * applies the semantic rules and aggregates their failures into one message. The launcher scripts
 * forward args verbatim and hold no flag knowledge, so this class is the single source of truth.
 */
@Command(name = "<folder-scanner>", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class, sortOptions = false, usageHelpWidth = 120, description = "Concurrently scan a directory tree and either aggregate files by "
                + "extension/size/date, locate duplicate content, list/delete matching files, or rank folders by "
                + "recursive size and report day-over-day growth.")
public final class Cli {

        private static final Logger LOGGER = LoggerFactory.getLogger(Cli.class);

        private static final String GIT_DIR = ".git";

        // Default --min-size-recursive for the folders consumer: below this, subtrees are noise that
        // buries the ranking. Overridable per run, including =0 to list every folder.
        private static final long DEFAULT_MIN_SIZE_RECURSIVE_BYTES = 10L * 1024 * 1024;

        // Field order is the --help option-list order (sortOptions = false): general knobs, then
        // the
        // producer stage and the filters it applies, then the consumer stage, then the queue
        // between
        // them. Keep new options inside the matching group rather than appending at the end.

        // Each description element is one clause sized to fit the help's description column at
        // width
        // 120, so picocli breaks between clauses rather than mid-sentence.

        // --- General ---
        @Option(names = "--out", paramLabel = "PATH", description = {
                        "Mirror output to PATH (omitted = stdout only).",
                        "Aggregate: the report; a dir or trailing '/' auto-names aggregator-*.out.",
                        "Folders: the report; auto-names folder-sizes-*.out.",
                        "Filemanager --action=list: the listing; auto-names file-list-*.out.",
                        "Duplicates / filemanager --action=delete: where to write the .sh." })
        String outPath = "";

        @Option(names = "--stats", description = {
                        "Per-second thread, heap, and queue-depth snapshot during the scan." })
        boolean stats;

        @Option(names = "--examples", description = {
                        "With --help, append a worked example for each consumer to the usage text." })
        boolean examples;

        @Option(names = "--log-level", paramLabel = "L", description = {
                        "Logback root level (default INFO).",
                        "DEBUG surfaces scanner/locator skip diagnostics." })
        String logLevel = "INFO";

        // --- Producers (folder walkers) and the filtering they apply before the queue ---
        @Option(names = "--producers", paramLabel = "N", description = {
                        "Scanner (folder-walker) threads. Default NCPU*16.",
                        "Directory walking is IO-bound, so over-subscribing CPUs is intentional." })
        Integer producers;

        @Option(names = "--min-size", paramLabel = "SIZE", description = {
                        "Skip files smaller than SIZE at the producer (applies to all consumers).",
                        "1024-based: raw bytes or NB/NKB/NMB/NGB/NTB (e.g. 1MB, 512KB). Default 0." })
        String minSizeRaw = "0";

        @Option(names = "--file-extensions", paramLabel = "LIST", description = {
                        "Comma-separated extensions to include; others are skipped at the producer.",
                        "Case-insensitive, leading dot optional. 'none' = extension-less files.",
                        "Default * (no filter)." })
        String fileExtRaw = "*";

        @Option(names = "--exclude", paramLabel = "LIST", description = {
                        "Comma-separated directory basenames to skip; .git is always added.",
                        "Quote the whole flag for names with spaces. Optional (default .git)." })
        String excludeRaw = GIT_DIR;

        // --- Consumers ---
        @Option(names = "--consumer", paramLabel = "NAME", description = {
                        "Consumer pipeline. aggregate (default): by-extension/size/date tables.",
                        "duplicates: emit a script that quarantines or deletes redundant copies.",
                        "filemanager: list or delete the files surviving the producer filters.",
                        "folders: rank folders by recursive subtree size, largest first." })
        String consumerRaw = "aggregate";

        @Option(names = "--consumers", paramLabel = "N", description = {
                        "Consumer drainer threads. Default max(4, NCPU*2).",
                        "Also sizes the duplicate locator's phase-2 hashing pool." })
        Integer consumers;

        // null = flag absent (so --action with a non-filemanager consumer can be rejected as
        // misuse);
        // ManageAction.parseOrCollect maps null to the LIST default.
        @Option(names = "--action", paramLabel = "A", description = {
                        "Filemanager mode. list (default): print path/size/modified per file.",
                        "delete: emit a script that quarantines (or, with --hard-delete, removes) them." })
        String actionRaw;

        @Option(names = "--hard-delete", description = {
                        "Duplicates, or filemanager --action=delete. Generate rm instead of soft-delete moves.",
                        "The script still prompts for the literal string DELETE first." })
        boolean hardDelete;

        @Option(names = "--sort", paramLabel = "KEY", description = {
                        "Filemanager --action=list sort key. path (default): alphabetical.",
                        "date: by modified time. size: by file size." })
        String sortRaw;

        // null = flag absent, so the effective direction falls back to the sort key's natural
        // default
        // (path ascends; date/size lead with newest/largest) rather than a fixed asc/desc.
        @Option(names = "--order", paramLabel = "DIR", description = {
                        "Listing sort direction: asc or desc.",
                        "Default: path ascends; date and size lead with newest/largest first." })
        String orderRaw;

        // null = flag absent, so misuse with a non-folders consumer can be rejected; absent maps to
        // 0 (every folder listed).
        @Option(names = "--min-size-recursive", paramLabel = "SIZE", description = {
                        "Folders consumer: omit folders whose recursive subtree is smaller than SIZE.",
                        "Same 1024-based syntax as --min-size (e.g. 50MB). Default 10MB; pass 0 for all folders." })
        String minSizeRecursiveRaw;

        @Option(names = "--baseline", paramLabel = "PATH", description = {
                        "Folders consumer: diff this run against the snapshot at PATH and report growth.",
                        "Missing on first use; written after each run, so the next run compares to today." })
        String baselinePath = "";

        @Option(names = "--growth-threshold", paramLabel = "PCT", description = {
                        "Folders consumer with --baseline: report folders that grew more than PCT percent.",
                        "Default 10." })
        String growthThresholdRaw;

        // --- Bounded queue between producers and consumers ---
        @Option(names = "--queue-type", paramLabel = "T", description = {
                        "Bounded-queue impl (default abq).",
                        "abq = ArrayBlockingQueue: one lock, no per-put allocation.",
                        "lbq = LinkedBlockingQueue: split put/take locks, per-put allocation." })
        String queueTypeRaw = "abq";

        @Option(names = "--queue-size", paramLabel = "N", description = {
                        "Bounded queue capacity (default 4096).",
                        "Larger lets the producer run further ahead before backpressure." })
        Integer queueSize;

        @Parameters(arity = "0..1", paramLabel = "PATH", description = "Directory to scan (default: current directory).")
        String target = ".";

        /** Logback root level requested via {@code --log-level}, defaulting to INFO. */
        public String logLevel() {
                return logLevel;
        }

        /** Whether {@code --examples} was passed; Main prints {@link #examplesText()} and exits. */
        public boolean examples() {
                return examples;
        }

        /**
         * Worked one-liner per consumer, shown by {@code --examples}. Kept here beside the flag
         * help so the user-facing CLI text stays in this single owner rather than drifting into a
         * script.
         */
        public static String examplesText() {
                return """
                                Examples (replace <folder-scanner> with the jar, e.g. java -jar target/folder-scanner-*.jar,
                                or ./scripts/start.sh; the scan target defaults to the current directory):

                                  # A reusable exclude list for scanning a WSL -> Windows drive; every example reuses it.
                                  EXCLUDE="Windows,ProgramData,Program Files,Program Files (x86),\\$Recycle.Bin,System Volume Information,workspaceStorage,extensions,.idea,.git,node_modules,target,.mvn,build,dist,.gradle,bin,EBWebView,WebviewCacheX64,ebview2_user_data,cef_cache,WidevineCdm,component_crx_cache,AmazonQ,puppeteer,.nuget,Adobe,AzureFunctionsTools,CSharpier,Windsurf,ws-browser,Postman-Agent,DBeaverData,Chrome,Old Firefox Data"

                                  # Aggregate by extension, size, and date (the default consumer)
                                  <folder-scanner> --exclude="$EXCLUDE" /mnt/c

                                  # Rank folders by recursive subtree size, largest first, only folders >= 100MB
                                  <folder-scanner> --consumer=folders --exclude="$EXCLUDE" --min-size-recursive=100MB /mnt/c

                                  # Daily growth check: diff against the saved snapshot, flag folders that grew > 10%
                                  <folder-scanner> --consumer=folders --exclude="$EXCLUDE" --baseline=folder-sizes.tsv /mnt/c

                                  # Locate duplicate-content files and write a script that quarantines the copies
                                  <folder-scanner> --consumer=duplicates --exclude="$EXCLUDE" --min-size=1MB /mnt/c

                                  # List the tmp/log files that are at least 10MB
                                  <folder-scanner> --consumer=filemanager --exclude="$EXCLUDE" --file-extensions=tmp,log --min-size=10MB /mnt/c

                                  # Write a script to quarantine every tmp file
                                  <folder-scanner> --consumer=filemanager --exclude="$EXCLUDE" --action=delete --file-extensions=tmp /mnt/c
                                """;
        }

        /**
         * Builds the validated {@link Config}. {@code ncpu} is a parameter (not read from the
         * runtime) so the same NCPU-scaled defaults can be reproduced on a different host. Every
         * semantic failure is collected so the user sees all mistakes from one run rather than one
         * at a time.
         */
        public Config toConfig(int ncpu) {
                List<String> errors = new ArrayList<>();

                int queueSizeV = queueSize != null ? queueSize : 4096;
                int producersV = producers != null ? producers : ncpu * 16;
                int consumersV = consumers != null ? consumers : Math.max(4, ncpu * 2);
                if (producersV < 1)
                        errors.add("producers must be >= 1");
                if (consumersV < 1)
                        errors.add("consumers must be >= 1");

                QueueType queueType = QueueType.parseOrCollect(queueTypeRaw, errors);
                ConsumerKind consumerKind = ConsumerKind.parseOrCollect(consumerRaw, errors);
                ManageAction action = ManageAction.parseOrCollect(actionRaw, errors);

                SortKey sortKey = SortKey.parseOrCollect(sortRaw, errors);
                SortOrder sortOrder = orderRaw != null ? SortOrder.parseOrCollect(orderRaw, errors)
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

                // Only the folders consumer reads this; others stay at 0 so their header omits the line.
                long minSizeRecursiveBytes = consumerKind == ConsumerKind.FOLDERS
                                ? DEFAULT_MIN_SIZE_RECURSIVE_BYTES
                                : 0L;
                if (minSizeRecursiveRaw != null) {
                        try {
                                minSizeRecursiveBytes = Format.parseSize(minSizeRecursiveRaw);
                        } catch (IllegalArgumentException e) {
                                errors.add("Invalid --min-size-recursive: " + e.getMessage());
                        }
                }
                if (consumerKind != ConsumerKind.FOLDERS && minSizeRecursiveRaw != null) {
                        errors.add("--min-size-recursive only applies with --consumer=folders");
                }

                boolean baselineSet = !baselinePath.isEmpty();
                if (baselineSet && consumerKind != ConsumerKind.FOLDERS) {
                        errors.add("--baseline only applies with --consumer=folders");
                }
                double growthThresholdPct = 10.0;
                if (growthThresholdRaw != null) {
                        if (!baselineSet) {
                                errors.add("--growth-threshold requires --baseline");
                        }
                        try {
                                growthThresholdPct = Double.parseDouble(growthThresholdRaw.trim());
                                if (growthThresholdPct < 0) {
                                        errors.add("--growth-threshold must be >= 0");
                                }
                        } catch (NumberFormatException e) {
                                errors.add("Invalid --growth-threshold: " + growthThresholdRaw);
                        }
                }

                FileExtensions.IncludeSet includeExtensions = FileExtensions.parse(fileExtRaw);

                Set<String> excludeDirs = parseExcludeDirs(excludeRaw);
                boolean gitWasAdded = excludeDirs.add(GIT_DIR);

                if (!errors.isEmpty()) {
                        throw new IllegalArgumentException(String.join("\n", errors));
                }

                // Deferred past the error gate so a run that is about to abort stays quiet.
                if (gitWasAdded) {
                        LOGGER.info("Excluding .git: a repo's .git is internal and should be hidden.");
                }

                return new Config(queueSizeV, stats, producersV, consumersV, queueType,
                                consumerKind, action, sortKey, sortOrder, outPath, hardDelete,
                                minSizeBytes, minSizeRecursiveBytes, baselinePath,
                                growthThresholdPct, excludeDirs, includeExtensions, target);
        }

        private static Set<String> parseExcludeDirs(String raw) {
                Set<String> out = new LinkedHashSet<>();
                if (raw == null)
                        return out;
                for (String token : raw.split(",")) {
                        String name = token.trim();
                        if (!name.isEmpty())
                                out.add(name);
                }
                return out;
        }
}
