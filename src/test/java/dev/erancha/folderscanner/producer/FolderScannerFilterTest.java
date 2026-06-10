package dev.erancha.folderscanner.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.PathFileInfo;
import dev.erancha.folderscanner.data.PoisonPill;
import dev.erancha.folderscanner.data.ExtensionFileInfo;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
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

    @Test
    void scan_on_a_non_directory_root_enqueues_nothing_without_throwing(@TempDir Path root)
            throws Exception {
        // A non-directory root is skipped like any other unreadable entry (DEBUG-logged), per the
        // walker's debug-and-continue error model. Main owns the user-facing rejection + exit code;
        // scan() does not re-reject.
        Path file = root.resolve("not-a-dir.txt");
        writeBytes(file, 10);

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.IncludeSet.ALL, 0);
        Set<String> names = drainNames(scanner, file, queue);

        assertTrue(names.isEmpty(), "non-directory root should enqueue no files; was " + names);
    }

    @Test
    void unreadable_directory_is_counted_not_silently_skipped(@TempDir Path root) throws Exception {
        // A permission-denied subtree must not vanish silently: the user needs the count to judge
        // whether the duplicate/aggregate report is complete. `locked` is listed by its parent but
        // its own contents cannot be enumerated.
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions required to simulate a permission-denied directory");
        writeBytes(root.resolve("visible.txt"), 100);
        Path locked = Files.createDirectory(root.resolve("locked"));
        writeBytes(locked.resolve("hidden.txt"), 100);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(locked), "running as root bypasses permission checks");

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.IncludeSet.ALL, 0L);
        try {
            Set<String> names = drainNames(scanner, root, queue);
            assertEquals(Set.of("visible.txt"), names, "hidden.txt is behind the locked directory");
            assertEquals(1, scanner.inaccessibleDirCount(),
                    "the unlistable directory must be counted");
        } finally {
            // Restore permissions so @TempDir cleanup can delete the tree.
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }

    @Test
    void unreadable_file_attributes_are_counted_not_silently_skipped(@TempDir Path root)
            throws Exception {
        // A directory readable but not executable (r--) can be listed, but its children cannot be
        // stat'd — readAttributes fails per child. Those skips must be counted, not lost.
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions required to simulate unreadable file attributes");
        writeBytes(root.resolve("visible.txt"), 100);
        Path noexec = Files.createDirectory(root.resolve("noexec"));
        writeBytes(noexec.resolve("stat-me.txt"), 100);
        Files.setPosixFilePermissions(noexec, PosixFilePermissions.fromString("r--------"));
        assumeTrue(!canReadChildAttributes(noexec.resolve("stat-me.txt")),
                "running as root bypasses permission checks");

        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                Set.of(), FileExtensions.IncludeSet.ALL, 0L);
        try {
            Set<String> names = drainNames(scanner, root, queue);
            assertEquals(Set.of("visible.txt"), names, "stat-me.txt's attributes are unreadable");
            assertEquals(1, scanner.inaccessibleFileCount(),
                    "the un-stat-able file must be counted");
            assertEquals(0, scanner.inaccessibleDirCount(),
                    "noexec itself is listable, so it is not an inaccessible directory");
        } finally {
            // Restore permissions so @TempDir cleanup can delete the tree.
            Files.setPosixFilePermissions(noexec, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }

    // ---- helpers ----

    /** True if the entry's attributes can be read; used to detect root, which bypasses POSIX bits. */
    private static boolean canReadChildAttributes(Path entry) {
        try {
            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

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
