package dev.erancha.folderscanner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * Unit tests for the picocli {@link Cli} command and its {@link Cli#toConfig(int)} validation.
 * Mirrors how Main drives the parser: syntax errors (unknown flag, non-integer int, extra
 * positional) surface as picocli {@link ParameterException}s; semantic errors (bad enum, bad
 * size, hard-delete misuse, thread count < 1) aggregate into one
 * IllegalArgumentException from toConfig.
 */
final class CliTest {

    private static final int NCPU = 4;

    private static CommandLine commandLine(Cli cli) {
        // Last-wins for repeated options: the benchmark harness appends sweep flags after the
        // forwarded user flags and relies on the later value winning rather than erroring.
        return new CommandLine(cli).setOverwrittenOptionsAllowed(true);
    }

    private static Config parse(String... args) {
        Cli cli = new Cli();
        commandLine(cli).parseArgs(args);
        return cli.toConfig(NCPU);
    }

    @Test
    void defaults_apply_when_no_flags_are_passed() {
        Config cfg = parse();
        assertEquals(4096, cfg.queueSize());
        assertFalse(cfg.statsEnabled());
        assertEquals(NCPU * 16, cfg.producers());
        assertEquals(Math.max(4, NCPU * 2), cfg.consumers());
        assertEquals(QueueType.ABQ, cfg.queueType());
        assertEquals(ConsumerKind.AGGREGATE, cfg.consumerKind());
        assertEquals(ManageAction.LIST, cfg.action());
        assertEquals(SortKey.PATH, cfg.sortKey());
        assertEquals(SortOrder.ASC, cfg.sortOrder());
        assertEquals("", cfg.outPath());
        assertFalse(cfg.hardDelete());
        assertEquals(0L, cfg.minSizeBytes());
        assertEquals(Set.of(".git"), cfg.excludeDirs());
        assertTrue(cfg.includeExtensions().isAll());
        assertEquals(".", cfg.target());
    }

    @Test
    void typed_values_round_trip_from_flags() {
        Config cfg = parse("--queue-size=1024", "--stats", "--producers=12", "--consumers=6",
                "--queue-type=lbq", "--consumer=duplicates", "--out=/tmp/out.sh", "--hard-delete",
                "--min-size=1KB", "--exclude=.git,node_modules,target", "--file-extensions=txt,md",
                "/data/photos");
        assertEquals(1024, cfg.queueSize());
        assertTrue(cfg.statsEnabled());
        assertEquals(12, cfg.producers());
        assertEquals(6, cfg.consumers());
        assertEquals(QueueType.LBQ, cfg.queueType());
        assertEquals(ConsumerKind.DUPLICATES, cfg.consumerKind());
        assertEquals("/tmp/out.sh", cfg.outPath());
        assertTrue(cfg.hardDelete());
        assertEquals(1024L, cfg.minSizeBytes());
        assertEquals(Set.of(".git", "node_modules", "target"), cfg.excludeDirs());
        assertFalse(cfg.includeExtensions().isAll());
        assertTrue(cfg.includeExtensions().matches("txt"));
        assertTrue(cfg.includeExtensions().matches("md"));
        assertEquals("/data/photos", cfg.target());
    }

    @Test
    void positional_argument_becomes_the_scan_target() {
        assertEquals("/data/photos", parse("--stats", "/data/photos").target());
    }

    @Test
    void second_positional_is_rejected() {
        Cli cli = new Cli();
        assertThrows(ParameterException.class,
                () -> commandLine(cli).parseArgs("/first", "/second"));
    }

    @Test
    void last_occurrence_of_a_flag_wins() {
        assertEquals(8, parse("--producers=100", "--producers=8").producers());
    }

    @Test
    void unknown_flag_is_rejected_by_the_parser() {
        Cli cli = new Cli();
        assertThrows(ParameterException.class,
                () -> commandLine(cli).parseArgs("--not-a-real-flag"));
    }

    @Test
    void non_integer_int_flag_is_rejected_by_the_parser() {
        Cli cli = new Cli();
        assertThrows(ParameterException.class,
                () -> commandLine(cli).parseArgs("--producers=many"));
    }

    @Test
    void malformed_boolean_flag_value_is_rejected_by_the_parser() {
        // --stats/--hard-delete are presence flags; an explicit non-boolean value is a parse error
        // rather than silently defaulting to false.
        Cli cli = new Cli();
        assertThrows(ParameterException.class,
                () -> commandLine(cli).parseArgs("--stats=ys"));
    }

    @Test
    void queue_type_is_case_insensitive() {
        assertEquals(QueueType.ABQ, parse("--queue-type=ABQ").queueType());
        assertEquals(QueueType.LBQ, parse("--queue-type=Lbq").queueType());
    }

    @Test
    void unknown_queue_type_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--queue-type=bogus"));
        assertTrue(ex.getMessage().contains("queue type"),
                "expected queue-type complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("bogus"));
    }

    @Test
    void unknown_consumer_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=weirdo"));
        assertTrue(ex.getMessage().contains("--consumer"),
                "expected --consumer complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("weirdo"));
    }

    @Test
    void invalid_min_size_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--min-size=ten gigs"));
        assertTrue(ex.getMessage().contains("--min-size"),
                "expected --min-size complaint, got: " + ex.getMessage());
    }

    @Test
    void empty_exclude_falls_back_to_git_only() {
        assertEquals(Set.of(".git"), parse("--exclude=").excludeDirs());
    }

    @Test
    void git_is_added_when_user_list_omits_it() {
        assertEquals(Set.of(".git", "node_modules"),
                parse("--exclude=node_modules").excludeDirs());
    }

    @Test
    void zero_or_negative_thread_counts_are_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--producers=0", "--consumers=-1"));
        assertTrue(ex.getMessage().contains("producers"));
        assertTrue(ex.getMessage().contains("consumers"));
    }

    @Test
    void hard_delete_without_duplicates_consumer_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--hard-delete"));
        assertTrue(ex.getMessage().contains("--hard-delete"),
                "expected --hard-delete complaint, got: " + ex.getMessage());
    }

    @Test
    void hard_delete_with_duplicates_consumer_is_accepted() {
        Config cfg = parse("--hard-delete", "--consumer=duplicates");
        assertTrue(cfg.hardDelete());
        assertEquals(ConsumerKind.DUPLICATES, cfg.consumerKind());
    }

    @Test
    void action_defaults_to_list_for_filemanager() {
        Config cfg = parse("--consumer=filemanager");
        assertEquals(ConsumerKind.FILEMANAGER, cfg.consumerKind());
        assertEquals(ManageAction.LIST, cfg.action());
    }

    @Test
    void action_delete_parses_for_filemanager() {
        assertEquals(ManageAction.DELETE,
                parse("--consumer=filemanager", "--action=delete").action());
    }

    @Test
    void unknown_action_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=filemanager", "--action=erase"));
        assertTrue(ex.getMessage().contains("--action"),
                "expected --action complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("erase"));
    }

    @Test
    void action_with_a_non_filemanager_consumer_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=aggregate", "--action=list"));
        assertTrue(ex.getMessage().contains("--action only applies"),
                "expected --action misuse complaint, got: " + ex.getMessage());
    }

    @Test
    void hard_delete_with_filemanager_delete_is_accepted() {
        Config cfg = parse("--consumer=filemanager", "--action=delete", "--hard-delete");
        assertTrue(cfg.hardDelete());
        assertEquals(ManageAction.DELETE, cfg.action());
    }

    @Test
    void hard_delete_with_filemanager_list_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=filemanager", "--hard-delete"));
        assertTrue(ex.getMessage().contains("--hard-delete"),
                "expected --hard-delete complaint, got: " + ex.getMessage());
    }

    @Test
    void sort_defaults_to_path_ascending_for_filemanager_list() {
        Config cfg = parse("--consumer=filemanager");
        assertEquals(SortKey.PATH, cfg.sortKey());
        assertEquals(SortOrder.ASC, cfg.sortOrder());
    }

    @Test
    void sort_by_size_defaults_to_descending() {
        Config cfg = parse("--consumer=filemanager", "--sort=size");
        assertEquals(SortKey.SIZE, cfg.sortKey());
        assertEquals(SortOrder.DESC, cfg.sortOrder());
    }

    @Test
    void sort_by_date_defaults_to_descending() {
        Config cfg = parse("--consumer=filemanager", "--sort=date");
        assertEquals(SortKey.DATE, cfg.sortKey());
        assertEquals(SortOrder.DESC, cfg.sortOrder());
    }

    @Test
    void explicit_order_overrides_the_keys_default_direction() {
        Config cfg = parse("--consumer=filemanager", "--sort=size", "--order=asc");
        assertEquals(SortKey.SIZE, cfg.sortKey());
        assertEquals(SortOrder.ASC, cfg.sortOrder());
    }

    @Test
    void unknown_sort_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=filemanager", "--sort=name"));
        assertTrue(ex.getMessage().contains("--sort"),
                "expected --sort complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("name"));
    }

    @Test
    void unknown_order_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=filemanager", "--order=sideways"));
        assertTrue(ex.getMessage().contains("--order"),
                "expected --order complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("sideways"));
    }

    @Test
    void sort_with_a_non_filemanager_consumer_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=aggregate", "--sort=size"));
        assertTrue(ex.getMessage().contains("--sort/--order only apply"),
                "expected --sort misuse complaint, got: " + ex.getMessage());
    }

    @Test
    void sort_with_filemanager_delete_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--consumer=filemanager", "--action=delete", "--sort=size"));
        assertTrue(ex.getMessage().contains("--sort/--order only apply"),
                "expected --sort misuse complaint, got: " + ex.getMessage());
    }

    @Test
    void semantic_validation_errors_aggregate_in_one_exception() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--queue-type=nope", "--consumer=nope", "--min-size=ten",
                        "--producers=0"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("queue type"), msg);
        assertTrue(msg.contains("--consumer"), msg);
        assertTrue(msg.contains("--min-size"), msg);
        assertTrue(msg.contains("producers"), msg);
    }

    @Test
    void include_set_all_singleton_is_reused_when_no_filter_is_set() {
        assertSame(parse().includeExtensions(), parse().includeExtensions());
    }

    @Test
    void version_output_tracks_the_build_version() {
        // project.version is injected by surefire straight from the POM; the CLI derives its
        // version from the resource-filtered version.properties. Two paths, one POM source: this
        // catches any drift between the build artifact's version and what --version prints.
        String pomVersion = System.getProperty("project.version");
        assertNotNull(pomVersion, "surefire must inject project.version (run under Maven)");

        StringWriter sink = new StringWriter();
        new CommandLine(new Cli()).printVersionHelp(new PrintWriter(sink));

        assertEquals("folder-scanner " + pomVersion, sink.toString().trim());
    }

    @Test
    void cli_names_match_the_user_facing_tokens() {
        assertEquals("abq", QueueType.ABQ.cliName());
        assertEquals("lbq", QueueType.LBQ.cliName());
        assertEquals("aggregate", ConsumerKind.AGGREGATE.cliName());
        assertEquals("duplicates", ConsumerKind.DUPLICATES.cliName());
        assertEquals("filemanager", ConsumerKind.FILEMANAGER.cliName());
        assertEquals("list", ManageAction.LIST.cliName());
        assertEquals("delete", ManageAction.DELETE.cliName());
        assertEquals("path", SortKey.PATH.cliName());
        assertEquals("date", SortKey.DATE.cliName());
        assertEquals("size", SortKey.SIZE.cliName());
        assertEquals("asc", SortOrder.ASC.cliName());
        assertEquals("desc", SortOrder.DESC.cliName());
    }
}
