package com.example.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.TypeFileInfo;
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
    void consume_rejects_wrong_FileInfo_subtype_with_IllegalStateException() throws InterruptedException {
        // DuplicateLocator's factory only ever produces PathFileInfo. If a TypeFileInfo somehow
        // reaches consume() (mis-wired factory in a future change), the exhaustive switch must
        // surface that as a clear configuration bug — not a bare ClassCastException that bubbles
        // out of a pool thread and silently corrupts the same-size bucketing.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        DuplicateLocator d = new DuplicateLocator(queue, 1, "", false, Paths.get("/tmp"), 0L);
        queue.put(new TypeFileInfo("txt", 100L, 0L));
        queue.put(FileInfo.POISON);
        assertThrows(IllegalStateException.class, d::consume);
    }
}
