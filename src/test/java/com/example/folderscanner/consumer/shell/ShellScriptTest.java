package com.example.folderscanner.consumer.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

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
