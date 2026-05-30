package dev.erancha.folderscanner.consumer.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared shell-script primitives. shellQuote pins the four characters that
 * retain special meaning inside bash double-quotes (\\, ", $, `) — each must be backslash-escaped,
 * everything else passes through, and backslash must be escaped first so the later substitutions
 * are not themselves re-escaped. emitDeletion and writeConfirmBanner pin the line shapes both the
 * duplicates and filemanager scripts depend on.
 */
final class ShellScriptTest {

    @Test
    void plain_path_passes_through_unchanged() {
        assertEquals("/home/user/file.txt",
                ShellScript.shellQuote(Paths.get("/home/user/file.txt")));
    }

    @Test
    void path_with_spaces_passes_through_unchanged() {
        assertEquals("/tmp/has space/file.txt",
                ShellScript.shellQuote(Paths.get("/tmp/has space/file.txt")));
    }

    @Test
    void double_quote_is_backslash_escaped() {
        assertEquals("/tmp/a\\\"b", ShellScript.shellQuote(Paths.get("/tmp/a\"b")));
    }

    @Test
    void dollar_sign_is_backslash_escaped_to_prevent_variable_expansion() {
        assertEquals("/tmp/\\$HOME/x", ShellScript.shellQuote(Paths.get("/tmp/$HOME/x")));
    }

    @Test
    void backtick_is_backslash_escaped_to_prevent_command_substitution() {
        assertEquals("/tmp/\\`rm -rf /\\`", ShellScript.shellQuote(Paths.get("/tmp/`rm -rf /`")));
    }

    @Test
    void backslash_is_doubled_and_does_not_re_escape_subsequent_substitutions() {
        // Input: \$ — a literal backslash followed by a dollar sign. The backslash must become \\
        // AND the dollar must become \$. Order matters: a buggy implementation that escaped $ first
        // would re-escape its own added backslash.
        assertEquals("\\\\\\$", ShellScript.shellQuote(Paths.get("\\$")));
    }

    @Test
    void emit_deletion_soft_moves_into_bin_under_encoded_name() {
        StringWriter sw = new StringWriter();
        ShellScript.emitDeletion(new PrintWriter(sw), Paths.get("/a/b.txt"), "a_b.txt", false);
        assertEquals("mv \"/a/b.txt\" \"$BIN/a_b.txt\"\n", sw.toString());
    }

    @Test
    void emit_deletion_hard_removes_in_place_and_ignores_encoded_name() {
        StringWriter sw = new StringWriter();
        ShellScript.emitDeletion(new PrintWriter(sw), Paths.get("/a/b.txt"), "unused", true);
        assertEquals("rm \"/a/b.txt\"\n", sw.toString());
    }

    @Test
    void newline_in_filename_stays_one_rm_target_when_executed_by_bash() throws Exception {
        // A literal newline inside a double-quoted word is preserved by bash as part of the single
        // word, so the emitted `rm "..."` line targets exactly the one real file — it is not split
        // into two commands. Proven end-to-end by running the line and asserting the file is gone.
        Assumptions.assumeTrue(Files.isExecutable(Paths.get("/bin/bash")), "bash required");
        Path dir = Files.createTempDirectory("shellquote");
        Path victim;
        try {
            victim = Files.createFile(dir.resolve("a\nb.txt"));
        } catch (java.io.IOException unsupported) {
            Assumptions.abort("filesystem rejects newline in filename: " + unsupported.getMessage());
            return;
        }

        StringWriter sw = new StringWriter();
        ShellScript.emitDeletion(new PrintWriter(sw), victim, "unused", true);

        Process p = new ProcessBuilder("/bin/bash", "-c", sw.toString())
                .redirectErrorStream(true).start();
        int exit = p.waitFor();

        assertEquals(0, exit, "rm line should run cleanly as a single command");
        assertFalse(Files.exists(victim), "the one newline-named file should be deleted");
    }

    @Test
    void staleness_guard_embeds_creation_epoch_and_ten_minute_threshold() {
        StringWriter sw = new StringWriter();
        ShellScript.writeStalenessGuard(new PrintWriter(sw), 1_700_000_000L);
        String out = sw.toString();
        assertTrue(out.contains("CREATED=1700000000"), out);
        assertTrue(out.contains("-gt 10"), out);
        assertTrue(out.contains("$ELAPSED_MIN minutes"), out);
    }

    @Test
    void staleness_guard_warns_with_elapsed_minutes_when_run_long_after_creation() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Paths.get("/bin/bash")), "bash required");
        long twentyMinutesAgo = Instant.now().getEpochSecond() - 20 * 60;
        StringWriter sw = new StringWriter();
        ShellScript.writeStalenessGuard(new PrintWriter(sw), twentyMinutesAgo);

        Process p = new ProcessBuilder("/bin/bash", "-c", sw.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();

        assertTrue(output.contains("WARNING"), output);
        assertTrue(output.contains("20 minutes"), output);
        // Redirected stderr is not a terminal, so the color must collapse to nothing: a raw escape
        // byte here would corrupt a log file the user piped the warning into.
        assertFalse(output.contains("\u001b"),
                "no raw ANSI escape may leak when stderr is not a terminal: " + output);
    }

    @Test
    void staleness_warning_is_red_when_stderr_is_a_terminal() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Paths.get("/usr/bin/script")),
                "script(1) required to allocate a pty");
        long twentyMinutesAgo = Instant.now().getEpochSecond() - 20 * 60;
        StringWriter sw = new StringWriter();
        ShellScript.writeStalenessGuard(new PrintWriter(sw), twentyMinutesAgo);
        Path guard = Files.createTempFile("staleness", ".sh");
        Files.writeString(guard, sw.toString());

        // script(1) runs the child under a pseudo-terminal, so `[ -t 2 ]` is true and the warning
        // must come back ANSI-colored. Captured via the relayed pty stream on stdout.
        Process p = new ProcessBuilder("/usr/bin/script", "-qec", "bash " + guard, "/dev/null")
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();

        assertTrue(output.contains("WARNING"), output);
        assertTrue(output.contains("\u001b["),
                "warning must be ANSI-colored when stderr is a terminal: " + output);
    }

    @Test
    void staleness_guard_is_silent_when_run_immediately_after_creation() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Paths.get("/bin/bash")), "bash required");
        StringWriter sw = new StringWriter();
        ShellScript.writeStalenessGuard(new PrintWriter(sw), Instant.now().getEpochSecond());

        Process p = new ProcessBuilder("/bin/bash", "-c", sw.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();

        assertTrue(output.isEmpty(), "expected no warning for a fresh script, got: " + output);
    }

    @Test
    void confirm_banner_demands_literal_DELETE_for_hard_delete() {
        StringWriter sw = new StringWriter();
        ShellScript.writeConfirmBanner(new PrintWriter(sw), 3, 100, true);
        String out = sw.toString();
        assertTrue(out.contains("ABOUT TO DELETE 3 FILES"), out);
        assertTrue(out.contains("IRREVERSIBLE"), out);
        assertTrue(out.contains("[ \"$CONFIRM\" = \"DELETE\" ]"), out);
    }

    @Test
    void confirm_banner_accepts_y_for_soft_delete() {
        StringWriter sw = new StringWriter();
        ShellScript.writeConfirmBanner(new PrintWriter(sw), 2, 100, false);
        String out = sw.toString();
        assertTrue(out.contains("ABOUT TO MOVE 2 FILES"), out);
        assertTrue(out.contains("recovered"), out);
        assertTrue(out.contains("[ \"$CONFIRM\" = \"y\" ]"), out);
    }
}
