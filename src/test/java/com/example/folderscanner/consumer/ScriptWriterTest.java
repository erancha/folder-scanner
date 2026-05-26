package com.example.folderscanner.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for ScriptWriter.shellQuote. The four characters that retain
 * special meaning inside bash double-quotes (\\, ", $, `) must each be
 * backslash-escaped; everything else passes through unchanged. Order
 * matters: backslash must be escaped first so the substitutions added by
 * the later steps are not themselves re-escaped.
 */
final class ScriptWriterTest {

    @Test
    void plain_path_passes_through_unchanged() {
        assertEquals("/home/user/file.txt",
                ScriptWriter.shellQuote(Paths.get("/home/user/file.txt")));
    }

    @Test
    void path_with_spaces_passes_through_unchanged() {
        // Spaces are safe inside double quotes; only the four special chars need escaping.
        assertEquals("/tmp/has space/file.txt",
                ScriptWriter.shellQuote(Paths.get("/tmp/has space/file.txt")));
    }

    @Test
    void double_quote_is_backslash_escaped() {
        assertEquals("/tmp/a\\\"b",
                ScriptWriter.shellQuote(Paths.get("/tmp/a\"b")));
    }

    @Test
    void dollar_sign_is_backslash_escaped_to_prevent_variable_expansion() {
        assertEquals("/tmp/\\$HOME/x",
                ScriptWriter.shellQuote(Paths.get("/tmp/$HOME/x")));
    }

    @Test
    void backtick_is_backslash_escaped_to_prevent_command_substitution() {
        assertEquals("/tmp/\\`rm -rf /\\`",
                ScriptWriter.shellQuote(Paths.get("/tmp/`rm -rf /`")));
    }

    @Test
    void backslash_is_doubled_and_does_not_re_escape_subsequent_substitutions() {
        // Input: \$ — a literal backslash followed by a dollar sign. The backslash must
        // become \\ AND the dollar must become \$. Order matters: a buggy implementation
        // that escaped $ first would re-escape its own added backslash.
        assertEquals("\\\\\\$", ScriptWriter.shellQuote(Paths.get("\\$")));
    }

    /**
     * Pins the second injection site in the soft-delete mv command. The source path is
     * already escaped via shellQuote, but the bin-name half of "$BIN/<binname>" is
     * substituted from BinName.encode, which only swaps '/' for '_' and leaves shell
     * metacharacters intact. A redundant file at /foo/bar";rm -rf $HOME;echo "x would
     * otherwise emit mv "..." "$BIN/foo_bar";rm -rf $HOME;echo "x" — every char after
     * the unescaped " is run as shell. The bin name must travel through the same
     * shellQuote so the double-quoted argument cannot be broken out of.
     */
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
}
