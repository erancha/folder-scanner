package com.example.folderscanner.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.PathFileInfo;
import com.example.folderscanner.data.PoisonPill;
import com.example.folderscanner.data.ExtensionFileInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of producer-side filtering: builds a small fixture under @TempDir, runs
 * FolderScanner with various minSize / includeExtensions combinations, and asserts that only the
 * matching files reach the queue and the skip counters are correct.
 */
final class FolderScannerFilterTest {

    private static final long ONE_KB = 1024L;
    private static final FileInfoFactory PATH_FACTORY = (path, attrs) ->
            new PathFileInfo(path, attrs.size(), attrs.lastModifiedTime().toMillis());

    @Test
    void minSize_filters_files_below_threshold(@TempDir Path root) throws Exception {
        // tiny.txt (10 B) is below; big.txt (2 KB) is above. Threshold is 1 KB.
        writeBytes(root.resolve("tiny.txt"), 10);
        writeBytes(root.resolve("big.txt"), 2 * (int) ONE_KB);

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.IncludeSet.ALL, ONE_KB);
        Set<String> names = drainNames(scanner, root, queue);

        assertEquals(Set.of("big.txt"), names);
        assertEquals(1, scanner.filteredBySizeCount());
        assertEquals(10L, scanner.filteredBySizeBytes());
        assertEquals(0, scanner.filteredByExtensionCount());
    }

    @Test
    void includeExtensions_filters_by_extension(@TempDir Path root) throws Exception {
        // Only .txt should survive when the include set is { txt }.
        writeBytes(root.resolve("a.txt"), 100);
        writeBytes(root.resolve("b.jpg"), 100);
        writeBytes(root.resolve("c.pdf"), 100);

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.parse("txt"), 0L);
        Set<String> names = drainNames(scanner, root, queue);

        assertEquals(Set.of("a.txt"), names);
        assertEquals(0, scanner.filteredBySizeCount());
        assertEquals(2, scanner.filteredByExtensionCount());
        assertEquals(200L, scanner.filteredByExtensionBytes());
    }

    @Test
    void includeExtensions_none_token_opts_in_extension_less_files(@TempDir Path root)
            throws Exception {
        // README has no extension; default behavior excludes it once the extension filter is on.
        // The `none` token brings it back.
        writeBytes(root.resolve("README"), 100);
        writeBytes(root.resolve("a.txt"), 100);
        writeBytes(root.resolve("b.jpg"), 100);

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.parse("txt,none"), 0L);
        Set<String> names = drainNames(scanner, root, queue);

        assertEquals(Set.of("README", "a.txt"), names);
        assertEquals(1, scanner.filteredByExtensionCount());
    }

    @Test
    void size_filter_runs_before_type_filter(@TempDir Path root) throws Exception {
        // The file is too small AND of an excluded type. Counter accounting must credit
        // it to size only — size is checked first; double-counting would mislead the
        // end-of-run diagnostic.
        writeBytes(root.resolve("tiny.jpg"), 10);

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.parse("txt"), ONE_KB);
        Set<String> names = drainNames(scanner, root, queue);

        assertTrue(names.isEmpty());
        assertEquals(1, scanner.filteredBySizeCount());
        assertEquals(0, scanner.filteredByExtensionCount(),
                "size-skipped files must not also be counted as extension-skipped");
    }

    @Test
    void no_filters_pass_everything(@TempDir Path root) throws Exception {
        // minSize=0 and IncludeSet.ALL together must let every regular file through —
        // this is the default-config path users hit when they pass no filter flags.
        writeBytes(root.resolve("a"), 5);
        writeBytes(root.resolve("b.txt"), 5);

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.IncludeSet.ALL, 0L);
        Set<String> names = drainNames(scanner, root, queue);

        assertEquals(Set.of("a", "b.txt"), names);
        assertEquals(0, scanner.filteredBySizeCount());
        assertEquals(0, scanner.filteredByExtensionCount());
    }

    // ---- helpers ----

    /** Writes `size` zero bytes at `p`. */
    private static void writeBytes(Path p, int size) throws IOException {
        Files.write(p, new byte[size]);
    }

    /**
     * Runs the scanner, then drains every PathFileInfo from the queue. Adds one extra POISON
     * pill so the drainer terminates without coupling to drainerCount semantics. The queue
     * is passed in (not extracted from the scanner) because tests own the queue lifecycle.
     */
    private static Set<String> drainNames(FolderScanner scanner, Path root,
            BlockingQueue<FileInfo> queue) throws Exception {
        scanner.scan(root);
        queue.put(FileInfo.POISON);
        Set<String> names = new HashSet<>();
        while (true) {
            FileInfo f = queue.poll(1, TimeUnit.SECONDS);
            if (f instanceof PoisonPill) break;
            if (f instanceof PathFileInfo p) names.add(p.path().getFileName().toString());
            if (f instanceof ExtensionFileInfo) throw new AssertionError("unexpected ExtensionFileInfo");
        }
        scanner.shutdown();
        return names;
    }
}
