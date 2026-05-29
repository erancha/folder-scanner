package com.example.folderscanner.consumer.filemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.folderscanner.config.ManageAction;
import com.example.folderscanner.data.ExtensionFileInfo;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.PathFileInfo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for FileManager. Pins consume()'s dispatch over the sealed FileInfo hierarchy (a wrong
 * subtype must surface as IllegalStateException rather than a silent cast on a pool thread), the
 * list report (path-sorted "path size modified" lines plus a total), and the delete report (soft
 * quarantine vs hard rm script, and the empty short-circuit that writes no script).
 */
final class FileManagerTest {

    @TempDir
    Path dir;

    private static final long T1 = 1_700_000_000_000L; // fixed epoch-millis for stable assertions

    @Test
    void consume_rejects_wrong_FileInfo_subtype_with_IllegalStateException()
            throws InterruptedException {
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        FileManager fm = new FileManager(queue, 1, "", ManageAction.LIST, false, Paths.get("/tmp"));
        queue.put(new ExtensionFileInfo("txt", 100L, 0L));
        queue.put(FileInfo.POISON);
        assertThrows(IllegalStateException.class, fm::consume);
    }

    @Test
    void list_prints_path_size_modified_sorted_by_path_with_a_total() throws Exception {
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        // Enqueued out of path order to prove the listing sorts.
        queue.put(new PathFileInfo(Paths.get("/z/last.bin"), 2048L, T1));
        queue.put(new PathFileInfo(Paths.get("/a/first.txt"), 1L, T1));
        queue.put(FileInfo.POISON);

        FileManager fm = new FileManager(queue, 1, "", ManageAction.LIST, false, dir);
        String out = run(fm);

        assertTrue(out.contains("/a/first.txt"), out);
        assertTrue(out.contains("/z/last.bin"), out);
        assertTrue(out.indexOf("/a/first.txt") < out.indexOf("/z/last.bin"),
                "listing must be sorted by path; was:\n" + out);
        assertTrue(out.contains("2.00 KB"), "size column missing humanBytes; was:\n" + out);
        assertTrue(out.contains(com.example.folderscanner.data.Format.formatTimestamp(T1)),
                "modified column missing; was:\n" + out);
        assertTrue(out.contains("Listed 2 files"), out);
        assertEquals(2, fm.totalFilesSeen());
        assertEquals(2049L, fm.totalBytesSeen());
    }

    @Test
    void delete_soft_writes_a_quarantine_script_with_one_mv_per_file() throws Exception {
        Path script = dir.resolve("delete-files.sh");
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
        queue.put(new PathFileInfo(Paths.get("/data/a.tmp"), 10L, T1));
        queue.put(new PathFileInfo(Paths.get("/data/b.tmp"), 20L, T1));
        queue.put(FileInfo.POISON);

        FileManager fm =
                new FileManager(queue, 1, script.toString(), ManageAction.DELETE, false, dir);
        String out = run(fm);

        assertTrue(Files.exists(script), "delete script must be written");
        assertTrue(out.contains("to quarantine"), out);
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        long mvCount = lines.stream().filter(l -> l.startsWith("mv ")).count();
        assertEquals(2, mvCount, "one active mv per matched file");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("BIN=")), "soft delete needs a trash bin");
        assertTrue(lines.stream().anyMatch(l -> l.contains("--consumer=filemanager")),
                "header must identify the generating consumer");
    }

    @Test
    void delete_hard_writes_rm_lines_and_no_trash_bin() throws Exception {
        Path script = dir.resolve("delete-files.sh");
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(4);
        queue.put(new PathFileInfo(Paths.get("/data/a.tmp"), 10L, T1));
        queue.put(FileInfo.POISON);

        FileManager fm =
                new FileManager(queue, 1, script.toString(), ManageAction.DELETE, true, dir);
        String out = run(fm);

        assertTrue(out.contains("to hard-delete"), out);
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        assertEquals(1, lines.stream().filter(l -> l.startsWith("rm ")).count(),
                "hard delete emits rm, not mv");
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("BIN=")),
                "hard delete must not declare a trash bin");
    }

    @Test
    void delete_with_no_matches_writes_no_script_and_says_so() throws Exception {
        Path script = dir.resolve("delete-files.sh");
        BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(2);
        queue.put(FileInfo.POISON);

        FileManager fm =
                new FileManager(queue, 1, script.toString(), ManageAction.DELETE, false, dir);
        String out = run(fm);

        assertTrue(out.contains("no files matched"), out);
        assertFalse(Files.exists(script), "no script should be written when nothing matched");
    }

    @Test
    void delete_script_embeds_staleness_guard_for_a_late_run() throws Exception {
        Path script = dir.resolve("delete-files.sh");
        DeleteScript.write(script, dir, List.of(Paths.get("/data/a.tmp")), 10L, true);
        assertTrue(Files.readAllLines(script).stream().anyMatch(l -> l.startsWith("CREATED=")),
                "filemanager delete script must embed a creation timestamp for the staleness guard");
    }

    private static String run(FileManager fm) throws InterruptedException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        fm.start();
        fm.awaitAndReport(new PrintStream(buf, true, StandardCharsets.UTF_8));
        return buf.toString(StandardCharsets.UTF_8);
    }
}
