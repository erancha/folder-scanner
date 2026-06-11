package dev.erancha.folderscanner.consumer.folders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import dev.erancha.folderscanner.data.ExtensionFileInfo;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.PathFileInfo;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the recursive folder-size roll-up: {@link FolderSizeReporter#rollUp} turns the
 * per-immediate-parent tallies the drainers collect into recursive totals for every ancestor up to
 * the scan root, then filters by the {@code --min-size-recursive} threshold and ranks by size.
 */
final class FolderSizeReporterTest {

    private static final long MB = 1024L * 1024L;

    private static long[] countBytes(long count, long bytes) {
        return new long[] { count, bytes };
    }

    @Test
    void rollUp_sums_descendants_into_every_ancestor_and_ranks_by_size() {
        // /mnt/c contains c1 (60MB) and c2; c2 contains x (120MB) and y (2MB). Threshold 50MB.
        Path root = Paths.get("/mnt/c");
        Map<Path, long[]> direct = new HashMap<>();
        direct.put(Paths.get("/mnt/c/c1"), countBytes(3, 60 * MB));
        direct.put(Paths.get("/mnt/c/c2/x"), countBytes(4, 120 * MB));
        direct.put(Paths.get("/mnt/c/c2/y"), countBytes(1, 2 * MB));

        List<FolderSize> rows = FolderSizeReporter.rollUp(direct, root, 50 * MB);

        // y (2MB) is below the threshold and dropped; everything else is ranked largest-first.
        assertEquals(List.of(
                new FolderSize(Paths.get("/mnt/c"), 8, 182 * MB),
                new FolderSize(Paths.get("/mnt/c/c2"), 5, 122 * MB),
                new FolderSize(Paths.get("/mnt/c/c2/x"), 4, 120 * MB),
                new FolderSize(Paths.get("/mnt/c/c1"), 3, 60 * MB)),
                rows);
    }

    @Test
    void rollUp_always_includes_the_scan_root_even_below_threshold() {
        Path root = Paths.get("/mnt/c");
        Map<Path, long[]> direct = new HashMap<>();
        direct.put(Paths.get("/mnt/c/small"), countBytes(1, 10L));

        List<FolderSize> rows = FolderSizeReporter.rollUp(direct, root, 1024L * MB);

        // The root is the orientation anchor / grand total, so it is shown even though its 10 bytes
        // are far below the 1GB threshold that filters out /mnt/c/small.
        assertEquals(List.of(new FolderSize(root, 1, 10L)), rows);
    }

    @Test
    void rollUp_breaks_size_ties_by_path_ascending() {
        Path root = Paths.get("/mnt/c");
        Map<Path, long[]> direct = new HashMap<>();
        direct.put(Paths.get("/mnt/c/b"), countBytes(1, 100L));
        direct.put(Paths.get("/mnt/c/a"), countBytes(1, 100L));

        List<FolderSize> rows = FolderSizeReporter.rollUp(direct, root, 0L);

        // root (200B) leads; the two equal 100B folders are ordered a before b for a stable table.
        assertEquals(List.of(
                new FolderSize(root, 2, 200L),
                new FolderSize(Paths.get("/mnt/c/a"), 1, 100L),
                new FolderSize(Paths.get("/mnt/c/b"), 1, 100L)),
                rows);
    }

    @Test
    void rollUp_collapses_a_passthrough_child_whose_parent_has_identical_totals() {
        // ug5/resources holds only the folder app, so resources and resources/app share the same
        // recursive totals (a pure pass-through). The deeper, redundant app is dropped; resources
        // is kept because its parent ug5 also holds a loose file and so has a different total.
        Path root = Paths.get("/mnt/c");
        Map<Path, long[]> direct = new HashMap<>();
        direct.put(Paths.get("/mnt/c/ug5/resources/app"), countBytes(134, 71 * MB));
        direct.put(Paths.get("/mnt/c/ug5"), countBytes(1, 1000));       // loose file beside resources
        direct.put(Paths.get("/mnt/c/sibling"), countBytes(1, 5 * MB)); // a second top-level branch

        List<Path> paths = FolderSizeReporter.rollUp(direct, root, 0L).stream()
                .map(FolderSize::path).toList();

        assertFalse(paths.contains(Paths.get("/mnt/c/ug5/resources/app")),
                "the pass-through child must be collapsed away");
        assertTrue(paths.contains(Paths.get("/mnt/c/ug5/resources")),
                "the topmost link of the pass-through chain is the kept representative");
        assertTrue(paths.contains(Paths.get("/mnt/c/ug5")), "ug5 has its own file, so it stays");
    }

    @Test
    void rollUp_keeps_a_folder_and_its_child_when_the_parent_holds_its_own_files() {
        // p holds a 10MB file of its own plus a 70MB child subtree, so p (80MB) and child (70MB)
        // differ and both are meaningful — not a pass-through. A sibling keeps p distinct from the
        // root too, so the only relationship under test is p vs. child.
        Path root = Paths.get("/mnt/c");
        Map<Path, long[]> direct = new HashMap<>();
        direct.put(Paths.get("/mnt/c/p"), countBytes(1, 10 * MB));
        direct.put(Paths.get("/mnt/c/p/child"), countBytes(1, 70 * MB));
        direct.put(Paths.get("/mnt/c/sibling"), countBytes(1, 1 * MB));

        List<Path> paths = FolderSizeReporter.rollUp(direct, root, 0L).stream()
                .map(FolderSize::path).toList();

        assertTrue(paths.contains(Paths.get("/mnt/c/p")));
        assertTrue(paths.contains(Paths.get("/mnt/c/p/child")));
    }

    @Test
    void factory_emits_PathFileInfo_so_the_folder_of_each_file_is_known() {
        FolderSizeReporter reporter = new FolderSizeReporter(
                new ArrayBlockingQueue<>(1), 1, Paths.get("/mnt/c"), 0L, "", 10.0);
        FileInfo produced = reporter.factory().create(Paths.get("/mnt/c/a/f.txt"),
                new StubAttrs(123L, 456L));
        assertEquals(new PathFileInfo(Paths.get("/mnt/c/a/f.txt"), 123L, 456L), produced);
    }

    @Test
    void consume_rejects_wrong_FileInfo_subtype_with_IllegalStateException()
            throws InterruptedException {
        // The reporter's factory only ever produces PathFileInfo; a foreign ExtensionFileInfo on the
        // queue means a mis-wired factory and must surface as the named base-class guard, not a bare
        // ClassCastException from a pool thread.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        FolderSizeReporter reporter = new FolderSizeReporter(queue, 1, Paths.get("/mnt/c"), 0L, "",
                10.0);
        queue.put(new ExtensionFileInfo("txt", 100L, 0L));
        queue.put(FileInfo.POISON);
        reporter.start();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> reporter.awaitAndReport(
                        new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        assertTrue(thrown.getCause().getMessage().contains("received ExtensionFileInfo"),
                "expected the foreign-variant guard message; was " + thrown.getCause());
    }

    /** Minimal BasicFileAttributes for exercising the factory without touching the filesystem. */
    private record StubAttrs(long size, long modifiedMillis)
            implements java.nio.file.attribute.BasicFileAttributes {
        @Override
        public long size() {
            return size;
        }

        @Override
        public java.nio.file.attribute.FileTime lastModifiedTime() {
            return java.nio.file.attribute.FileTime.fromMillis(modifiedMillis);
        }

        @Override
        public java.nio.file.attribute.FileTime lastAccessTime() {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }

        @Override
        public java.nio.file.attribute.FileTime creationTime() {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }

        @Override
        public boolean isRegularFile() {
            return true;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public Object fileKey() {
            return null;
        }
    }
}
