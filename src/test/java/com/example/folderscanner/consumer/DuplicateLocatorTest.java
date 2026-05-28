package com.example.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.ExtensionFileInfo;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DuplicateLocator's consume() dispatch. The full size-bucket and hashing
 * pipeline is covered indirectly by the bash e2e harness; this test pins the one piece of
 * runtime behavior that the new exhaustive switch over the sealed FileInfo hierarchy adds:
 * a wrong-subtype message surfaces as IllegalStateException instead of a silent cast failure
 * on a pool thread.
 */
final class DuplicateLocatorTest {

    @Test
    void phase1_elapsed_includes_drain_wait_not_only_shutdown() throws Exception {
        // Phase 1 in the generated script header should reflect how long the size-bucket
        // ingest actually ran (from start() until the last POISON drains), not just the
        // near-zero time pool.shutdown() + awaitTermination spend after every consumer
        // has already exited.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(4);
        DuplicateLocator d = new DuplicateLocator(queue, 1, "", false, Paths.get("/tmp"));
        d.start();
        Thread.sleep(80);
        queue.put(FileInfo.POISON);
        d.awaitAndReport(new PrintStream(new ByteArrayOutputStream()));
        long elapsed = d.phase1ElapsedMs();
        assertTrue(elapsed >= 60,
                "phase1ElapsedMs should reflect the ~80ms the drainer spent waiting on the queue; "
                        + "was " + elapsed);
    }

    @Test
    void consume_rejects_wrong_FileInfo_subtype_with_IllegalStateException()
            throws InterruptedException {
        // DuplicateLocator's factory only ever produces PathFileInfo. If a ExtensionFileInfo somehow
        // reaches consume() (mis-wired factory in a future change), the exhaustive switch must
        // surface that as a clear configuration bug — not a bare ClassCastException that bubbles
        // out of a pool thread and silently corrupts the same-size bucketing.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        DuplicateLocator d = new DuplicateLocator(queue, 1, "", false, Paths.get("/tmp"));
        queue.put(new ExtensionFileInfo("txt", 100L, 0L));
        queue.put(FileInfo.POISON);
        assertThrows(IllegalStateException.class, d::consume);
    }
}
