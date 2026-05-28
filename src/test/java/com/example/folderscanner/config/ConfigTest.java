package com.example.folderscanner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Config.parse. Covers default-fill, full typed parse, and the
 * aggregated validation pass that collects every CLI error before main exits.
 */
final class ConfigTest {

    private static final int NCPU = 4;

    @Test
    void defaults_apply_when_no_properties_are_set() {
        Config cfg = Config.parse(new Properties(), NCPU);
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
    }

    @Test
    void typed_values_round_trip_from_properties() {
        Properties p = new Properties();
        p.setProperty("queuesize", "1024");
        p.setProperty("stats", "true");
        p.setProperty("producers", "12");
        p.setProperty("consumers", "6");
        p.setProperty("queuetype", "lbq");
        p.setProperty("consumer", "duplicates");
        p.setProperty("out", "/tmp/out.sh");
        p.setProperty("harddelete", "true");
        p.setProperty("minsize", "1KB");
        p.setProperty("exclude", ".git,node_modules,target");
        p.setProperty("fileextensions", "txt,md");

        Config cfg = Config.parse(p, NCPU);
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
    }

    @Test
    void queue_type_is_case_insensitive() {
        Properties p = new Properties();
        p.setProperty("queuetype", "ABQ");
        assertEquals(QueueType.ABQ, Config.parse(p, NCPU).queueType());
        p.setProperty("queuetype", "Lbq");
        assertEquals(QueueType.LBQ, Config.parse(p, NCPU).queueType());
    }

    @Test
    void unknown_queue_type_is_rejected() {
        Properties p = new Properties();
        p.setProperty("queuetype", "bogus");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("queue type"),
                "expected queue-type complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("bogus"));
    }

    @Test
    void unknown_consumer_is_rejected() {
        Properties p = new Properties();
        p.setProperty("consumer", "weirdo");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("--consumer"),
                "expected --consumer complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("weirdo"));
    }

    @Test
    void invalid_min_size_is_rejected() {
        Properties p = new Properties();
        p.setProperty("minsize", "ten gigs");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("--min-size"),
                "expected --min-size complaint, got: " + ex.getMessage());
    }

    @Test
    void empty_exclude_is_rejected() {
        Properties p = new Properties();
        p.setProperty("exclude", "");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("--exclude is required"),
                "expected --exclude complaint, got: " + ex.getMessage());
    }

    @Test
    void zero_or_negative_thread_counts_are_rejected() {
        Properties p = new Properties();
        p.setProperty("producers", "0");
        p.setProperty("consumers", "-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("producers"));
        assertTrue(ex.getMessage().contains("consumers"));
    }

    @Test
    void all_validation_errors_surface_in_a_single_exception() {
        Properties p = new Properties();
        p.setProperty("queuetype", "nope");
        p.setProperty("consumer", "nope");
        p.setProperty("minsize", "ten");
        p.setProperty("exclude", "");
        p.setProperty("producers", "0");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        String msg = ex.getMessage();
        assertTrue(msg.contains("queue type"), msg);
        assertTrue(msg.contains("--consumer"), msg);
        assertTrue(msg.contains("--min-size"), msg);
        assertTrue(msg.contains("--exclude"), msg);
        assertTrue(msg.contains("producers"), msg);
    }

    @Test
    void non_integer_queue_size_is_rejected() {
        Properties p = new Properties();
        p.setProperty("queuesize", "not-a-number");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("--queue-size"),
                "expected --queue-size complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not-a-number"),
                "expected echoed bad value, got: " + ex.getMessage());
    }

    @Test
    void non_integer_consumers_is_rejected_not_silently_defaulted() {
        Properties p = new Properties();
        p.setProperty("consumers", "banana");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        assertTrue(ex.getMessage().contains("--consumers"),
                "expected --consumers complaint, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("banana"),
                "expected echoed bad value, got: " + ex.getMessage());
    }

    @Test
    void non_integer_int_flags_join_the_aggregated_error_message() {
        Properties p = new Properties();
        p.setProperty("queuesize", "big");
        p.setProperty("producers", "many");
        p.setProperty("consumers", "few");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Config.parse(p, NCPU));
        String msg = ex.getMessage();
        assertTrue(msg.contains("--queue-size"), msg);
        assertTrue(msg.contains("--producers"), msg);
        assertTrue(msg.contains("--consumers"), msg);
    }

    @Test
    void cli_names_match_the_user_facing_tokens() {
        assertEquals("abq", QueueType.ABQ.cliName());
        assertEquals("lbq", QueueType.LBQ.cliName());
        assertEquals("aggregate", ConsumerKind.AGGREGATE.cliName());
        assertEquals("duplicates", ConsumerKind.DUPLICATES.cliName());
    }

    @Test
    void include_set_all_singleton_is_reused_when_no_filter_is_set() {
        Config a = Config.parse(new Properties(), NCPU);
        Config b = Config.parse(new Properties(), NCPU);
        assertSame(a.includeExtensions(), b.includeExtensions());
    }
}
