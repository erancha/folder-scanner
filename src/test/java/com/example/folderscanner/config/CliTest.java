package com.example.folderscanner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * Unit tests for the picocli {@link Cli} command and its {@link Cli#toConfig(int)} validation.
 * Mirrors how Main drives the parser: syntax errors (unknown flag, non-integer int, extra
 * positional) surface as picocli {@link ParameterException}s; semantic errors (bad enum, bad
 * size, empty exclude, hard-delete misuse, thread count < 1) aggregate into one
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
        assertEquals(Math.max(8, NCPU * 4), cfg.producers());
        assertEquals(Math.max(4, NCPU * 2), cfg.consumers());
        assertEquals(QueueType.ABQ, cfg.queueType());
        assertEquals(ConsumerKind.AGGREGATE, cfg.consumerKind());
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
    void empty_exclude_is_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--exclude="));
        assertTrue(ex.getMessage().contains("--exclude is required"),
                "expected --exclude complaint, got: " + ex.getMessage());
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
    void semantic_validation_errors_aggregate_in_one_exception() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("--queue-type=nope", "--consumer=nope", "--min-size=ten",
                        "--exclude=", "--producers=0"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("queue type"), msg);
        assertTrue(msg.contains("--consumer"), msg);
        assertTrue(msg.contains("--min-size"), msg);
        assertTrue(msg.contains("--exclude"), msg);
        assertTrue(msg.contains("producers"), msg);
    }

    @Test
    void include_set_all_singleton_is_reused_when_no_filter_is_set() {
        assertSame(parse().includeExtensions(), parse().includeExtensions());
    }

    @Test
    void cli_names_match_the_user_facing_tokens() {
        assertEquals("abq", QueueType.ABQ.cliName());
        assertEquals("lbq", QueueType.LBQ.cliName());
        assertEquals("aggregate", ConsumerKind.AGGREGATE.cliName());
        assertEquals("duplicates", ConsumerKind.DUPLICATES.cliName());
    }
}
