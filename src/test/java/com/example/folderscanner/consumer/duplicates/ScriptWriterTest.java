package com.example.folderscanner.consumer.duplicates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Integration tests for ScriptWriter.write — the duplicates-specific framing on top of the shared
 * {@link com.example.folderscanner.consumer.shell.ShellScript} primitives. Shell-quoting itself is
 * covered by ShellScriptTest; these assert the two security-critical properties that depend on the
 * full generated script: the soft-delete bin name is escaped at its interpolation site, and the bin
 * resolves relative to the script rather than the caller's cwd.
 */
final class ScriptWriterTest {

    @Test
    void bin_name_is_escaped_inside_BIN_argument_to_prevent_injection() throws IOException {
        Path tmp = Files.createTempDirectory("scriptwriter-injection");
        Path scriptPath = tmp.resolve("remove.sh");
        Path keeper = Paths.get("/keeper.txt");
        Path danger = Paths.get("/foo/bar\"$.txt");
        DuplicateReport report = new DuplicateReport(
                List.of(new DuplicateReport.Group(100L, List.of(keeper, danger))),
                1L, 1L, 100L, 0L, 0L);

        ScriptWriter.write(scriptPath, tmp, report, false);

        String mvLine = Files.readAllLines(scriptPath).stream()
                .filter(l -> l.startsWith("mv ") && l.contains("$BIN/"))
                .findFirst().orElseThrow();

        // The bin-name interpolation must escape both " and $ so the surrounding
        // double quotes cannot be closed and $-expansion cannot fire.
        assertTrue(mvLine.endsWith("\"$BIN/foo_bar\\\"\\$.txt\""),
                "bin name not escaped — injection possible. Line was: " + mvLine);
    }

    @Test
    void bin_path_resolves_relative_to_script_location_not_cwd() throws IOException {
        Path tmp = Files.createTempDirectory("scriptwriter-bin-cwd");
        Path scriptPath = tmp.resolve("remove.sh");
        DuplicateReport report = new DuplicateReport(
                List.of(new DuplicateReport.Group(100L,
                        List.of(Paths.get("/a.txt"), Paths.get("/b.txt")))),
                1L, 1L, 100L, 0L, 0L);

        ScriptWriter.write(scriptPath, tmp, report, false);

        String binLine = Files.readAllLines(scriptPath).stream()
                .filter(l -> l.startsWith("BIN="))
                .findFirst().orElseThrow();
        assertFalse(binLine.equals("BIN=\"./trash\""),
                "BIN must not be cwd-relative ./trash; was: " + binLine);
        assertTrue(binLine.contains("dirname") && binLine.contains("$0"),
                "BIN must resolve via $(dirname \"$0\") so the script works from any cwd; was: "
                        + binLine);
    }

    @Test
    void embeds_staleness_guard_so_a_late_run_warns() throws IOException {
        Path tmp = Files.createTempDirectory("scriptwriter-staleness");
        Path scriptPath = tmp.resolve("remove.sh");
        DuplicateReport report = new DuplicateReport(
                List.of(new DuplicateReport.Group(100L,
                        List.of(Paths.get("/a.txt"), Paths.get("/b.txt")))),
                1L, 1L, 100L, 0L, 0L);

        ScriptWriter.write(scriptPath, tmp, report, false);

        assertTrue(Files.readAllLines(scriptPath).stream().anyMatch(l -> l.startsWith("CREATED=")),
                "generated script must embed a creation timestamp for the run-time staleness guard");
    }
}
