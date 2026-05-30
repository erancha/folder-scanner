package dev.erancha.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for DrainerPool, the shared drainer lifecycle every FileConsumer delegates to. The
 * load-bearing case is failure surfacing: submit() buries an unchecked throw in the task's Future,
 * so a pool that lost a drainer still terminates and the run would otherwise report success on an
 * under-counted scan. awaitTermination must turn that buried throw back into a thrown error. A
 * second concern is the long-running drain: the wait blocks until the pool truly terminates rather
 * than discarding work at a fixed ceiling, but logs a periodic heartbeat once the wait outlasts a
 * threshold so a genuinely stuck drainer is observable instead of silent.
 */
final class DrainerPoolTest {

    @Test
    void awaitTermination_rethrows_a_drainer_that_died_on_an_unchecked_throw() {
        DrainerPool pool = new DrainerPool(1, "test");
        pool.start(() -> {
            throw new IllegalStateException("boom");
        });
        IllegalStateException e =
                assertThrows(IllegalStateException.class, pool::awaitTermination);
        assertEquals("boom", e.getCause().getMessage(),
                "the original drainer throw must be preserved as the cause");
    }

    @Test
    void awaitTermination_returns_normally_when_every_drainer_exits_cleanly() throws Exception {
        DrainerPool pool = new DrainerPool(2, "test");
        pool.start(() -> { });
        pool.awaitTermination();
    }

    @Test
    void heartbeat_fires_once_the_drain_outlasts_the_threshold() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppenderToPoolLogger();
        DrainerPool pool = new DrainerPool(1, "slowpoke");
        pool.start(() -> sleepMillis(150));

        pool.awaitTermination(Duration.ZERO, Duration.ofMillis(20));

        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "a WARN heartbeat must appear while the drain is still running");
    }

    @Test
    void no_heartbeat_when_the_drain_finishes_before_the_threshold() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppenderToPoolLogger();
        DrainerPool pool = new DrainerPool(2, "quick");
        pool.start(() -> { });

        pool.awaitTermination(Duration.ofSeconds(30), Duration.ofMillis(20));

        assertTrue(appender.list.isEmpty(),
                "a drain that finishes before the threshold must stay silent");
    }

    @Test
    void rejects_a_pool_sized_below_one() {
        assertThrows(IllegalArgumentException.class, () -> new DrainerPool(0, "test"));
    }

    @Test
    void threadCount_reports_the_configured_size() {
        assertEquals(3, new DrainerPool(3, "test").threadCount());
    }

    private static ListAppender<ILoggingEvent> attachAppenderToPoolLogger() {
        Logger poolLogger = (Logger) LoggerFactory.getLogger(DrainerPool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        poolLogger.addAppender(appender);
        return appender;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
