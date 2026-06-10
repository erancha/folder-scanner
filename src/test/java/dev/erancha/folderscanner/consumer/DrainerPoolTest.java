package dev.erancha.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for DrainerPool, the shared drainer lifecycle every FileConsumer delegates to. The
 * load-bearing case is failure surfacing: submit() buries an unchecked throw in the task's Future,
 * so a pool that lost a drainer still terminates and the run would otherwise report success on an
 * under-counted scan. awaitTermination must turn that buried throw back into a thrown error.
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
    void rejects_a_pool_sized_below_one() {
        assertThrows(IllegalArgumentException.class, () -> new DrainerPool(0, "test"));
    }

    @Test
    void threadCount_reports_the_configured_size() {
        assertEquals(3, new DrainerPool(3, "test").threadCount());
    }
}
