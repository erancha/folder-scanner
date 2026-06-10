package dev.erancha.folderscanner.consumer.duplicates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.ExtensionFileInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for DuplicateLocator. Two concerns are pinned here: consume()'s dispatch over the
 * sealed FileInfo hierarchy (a wrong subtype must surface as IllegalStateException rather than a
 * silent cast failure on a pool thread), and confirmGroup() — the small-hash→full-hash grouping
 * that decides which files land on the deletion list. The second is the dangerous part: a false
 * positive there means the generated script deletes a file that is not actually a duplicate, so
 * it is tested directly rather than left to the bash e2e harness.
 */
final class DuplicateLocatorTest {

    @TempDir
    Path dir;

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
        // reaches the drain loop (mis-wired factory in a future change), the consumer must surface
        // that as a clear configuration bug — not a bare ClassCastException that bubbles out of a
        // pool thread and silently corrupts the same-size bucketing.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        DuplicateLocator d = new DuplicateLocator(queue, 1, "", false, Paths.get("/tmp"));
        queue.put(new ExtensionFileInfo("txt", 100L, 0L));
        queue.put(FileInfo.POISON);
        d.start();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> d.awaitAndReport(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        // The guard, not a bare ClassCastException: DrainerPool wraps any drainer death as an
        // IllegalStateException, so the named cause is what proves the foreign variant was caught.
        assertTrue(thrown.getCause().getMessage().contains("received ExtensionFileInfo"),
                "expected the foreign-variant guard message; was " + thrown.getCause());
    }

    @Test
    void confirmGroup_groups_files_with_identical_content() throws IOException {
        byte[] content = "duplicate payload".getBytes();
        Path a = write("a.bin", content);
        Path b = write("b.bin", content.clone());

        List<DuplicateReport.Group> groups =
                locator().confirmGroup(content.length, List.of(a, b));

        assertEquals(1, groups.size(), "two identical files must form exactly one group");
        assertEquals(List.of(a, b), groups.get(0).paths(),
                "paths must be lexicographically sorted so the keeper at index 0 is deterministic");
    }

    @Test
    void confirmGroup_rejects_same_size_files_with_different_content() throws IOException {
        // Same byte length, different bytes within the first page: the small hash alone must
        // separate them, so nothing is proposed for deletion.
        Path a = write("a.bin", "alpha".getBytes());
        Path b = write("b.bin", "bravo".getBytes());

        List<DuplicateReport.Group> groups = locator().confirmGroup(5L, List.of(a, b));

        assertTrue(groups.isEmpty(), "files of equal size but differing content are not duplicates");
    }

    @Test
    void confirmGroup_rejects_files_that_collide_on_small_hash_but_differ_in_full_content()
            throws IOException {
        // The most dangerous false positive: two files share their first 4 KB (so they collide on
        // the cheap small-hash pre-filter) but diverge afterward. The full-hash stage must catch
        // the divergence; otherwise the script would delete a file that is not a true duplicate.
        byte[] sharedPage = repeat((byte) 'p', ContentHasher.SMALL_HASH_BYTES);
        byte[] a = concat(sharedPage, "tail-A".getBytes());
        byte[] b = concat(sharedPage, "tail-B".getBytes());
        Path pa = write("a.bin", a);
        Path pb = write("b.bin", b);

        List<DuplicateReport.Group> groups =
                locator().confirmGroup(a.length, List.of(pa, pb));

        assertTrue(groups.isEmpty(),
                "small-hash collision must not survive the full-hash check");
    }

    @Test
    void confirmGroup_separates_duplicates_from_a_unique_file_of_the_same_size() throws IOException {
        // Three files of identical size: two share content, one is unique. Only the matching pair
        // may form a group; the odd file out must be left untouched.
        byte[] shared = "0123456789".getBytes();
        byte[] unique = "9876543210".getBytes();
        Path a = write("a.bin", shared);
        Path b = write("b.bin", shared.clone());
        Path c = write("c.bin", unique);

        List<DuplicateReport.Group> groups =
                locator().confirmGroup(shared.length, List.of(a, b, c));

        assertEquals(1, groups.size());
        assertEquals(List.of(a, b), groups.get(0).paths());
    }

    @Test
    void confirmGroup_collapses_hardlinks_to_a_single_representative() throws IOException {
        // a.bin and b.bin are two names for the same inode; c.bin is an independent copy with
        // identical bytes. Only c.bin is a true redundant copy — deleting a hardlink frees no
        // space and removes a path the user may depend on, so at most one of {a,b} may appear.
        byte[] content = "shared inode payload".getBytes();
        Path a = write("a.bin", content);
        Path b = dir.resolve("b.bin");
        Files.createLink(b, a);
        Path c = write("c.bin", content.clone());

        List<DuplicateReport.Group> groups =
                locator().confirmGroup(content.length, List.of(a, b, c));

        assertEquals(1, groups.size());
        assertEquals(List.of(a, c), groups.get(0).paths(),
                "hardlinks to one inode collapse to the lexicographically-first name; the "
                        + "independent copy stays as the only redundant target");
    }

    @Test
    void confirmGroup_treats_a_pure_hardlink_pair_as_not_redundant() throws IOException {
        // Two names for one inode: identical size and content, but deleting either frees nothing.
        // With no independent copy, there is no recoverable space, so no group is proposed.
        byte[] content = "only hardlinks here".getBytes();
        Path a = write("a.bin", content);
        Path b = dir.resolve("b.bin");
        Files.createLink(b, a);

        List<DuplicateReport.Group> groups =
                locator().confirmGroup(content.length, List.of(a, b));

        assertTrue(groups.isEmpty(),
                "a hardlink pair shares an inode, so neither name is a reclaimable duplicate");
    }

    private DuplicateLocator locator() {
        // confirmGroup reads no mutable instance state, so a minimally-wired instance suffices.
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(1);
        return new DuplicateLocator(queue, 1, "", false, Paths.get("/tmp"));
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static byte[] repeat(byte value, int count) {
        byte[] b = new byte[count];
        Arrays.fill(b, value);
        return b;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
