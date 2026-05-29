package com.example.folderscanner.consumer.shell;

import com.example.folderscanner.data.Format;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Body-agnostic primitives shared by every consumer that emits a destructive shell script
 * (duplicates removal, filemanager deletion): the shebang preamble, the run-time staleness guard,
 * the soft-delete trash bin setup, the type-to-confirm safety banner, the per-file delete line, and
 * shell quoting.
 *
 * Each consumer composes these around its own header frame and file-listing body; nothing here
 * knows whether the files came from a duplicate group or a flat match list. Filenames in the
 * scanned tree are untrusted input, so every interpolation site routes through {@link #shellQuote}.
 */
public final class ShellScript {

    private ShellScript() {}

    /** {@code #!/usr/bin/env bash} + {@code set -euo pipefail} + a blank line. */
    public static void writeShebang(PrintWriter w) {
        w.println("#!/usr/bin/env bash");
        w.println("set -euo pipefail");
        w.println();
    }

    /**
     * Emits a runtime staleness check that warns when the script runs more than ten minutes after
     * it was generated. Targets are chosen (and, for duplicates, content-hashed) at scan time but
     * the {@code rm}/{@code mv} runs only when the user later executes the script; a tree mutated in
     * that window can leave the baked-in paths pointing at different bytes. The warning surfaces the
     * elapsed minutes so the user can re-scan rather than acting on a stale plan. It does not abort:
     * the gap is advisory, and the type-to-confirm banner remains the hard stop.
     *
     * @param createdEpochSeconds generation time as Unix epoch seconds, compared against the
     *     executing shell's {@code date +%s}
     */
    public static void writeStalenessGuard(PrintWriter w, long createdEpochSeconds) {
        w.printf("CREATED=%d%n", createdEpochSeconds);
        w.println("ELAPSED_MIN=$(( ( $(date +%s) - CREATED ) / 60 ))");
        w.println("if [ \"$ELAPSED_MIN\" -gt 10 ]; then");
        w.println("  echo \"WARNING: this script was generated $ELAPSED_MIN minutes ago; the scanned"
                + " tree may have changed since — re-scan if unsure.\" >&2");
        w.println("fi");
        w.println();
    }

    /**
     * Declares the soft-delete trash bin. Resolved relative to the script's own location (not the
     * caller's cwd) so the script works from any directory and the bin lands next to the script.
     * Soft-delete scripts only.
     */
    public static void writeBinDeclaration(PrintWriter w) {
        w.println("BIN=\"$(cd \"$(dirname \"$0\")\" && pwd)/trash\"");
        w.println();
    }

    /** Creates the trash bin once the user has confirmed. Soft-delete scripts only. */
    public static void writeBinMkdir(PrintWriter w) {
        w.println("mkdir -p \"$BIN\"\n");
    }

    /**
     * Emits the heredoc warning banner and the type-to-confirm guard. Hard delete demands the
     * literal string {@code DELETE}; soft delete accepts {@code y}, since it is recoverable.
     */
    public static void writeConfirmBanner(PrintWriter w, long fileCount, long totalBytes,
            boolean hardDelete) {
        String verb = hardDelete ? "DELETE" : "MOVE";
        String prompt = hardDelete ? "DELETE in capitals" : "y";
        String expected = hardDelete ? "DELETE" : "y";
        w.println("cat <<'BANNER'");
        w.println();
        w.println("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        w.printf ("  !!  YOU ARE ABOUT TO %s %d FILES (%s)%n",
                verb, fileCount, Format.humanBytes(totalBytes));
        if (hardDelete) {
            w.println("  !!  This action is IRREVERSIBLE (hard rm).               !!");
        } else {
            w.println("  !!  Files can be recovered by moving them back manually. !!");
        }
        w.println("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        w.println();
        w.println("BANNER");
        w.printf("read -r -p \"Type %s to proceed: \" CONFIRM%n", prompt);
        w.printf("[ \"$CONFIRM\" = \"%s\" ] || { echo \"Aborted.\"; exit 1; }%n", expected);
        w.println();
    }

    /**
     * One deletion line for a single file: {@code rm} in place when hard-deleting, otherwise
     * {@code mv} into {@code $BIN} under its pre-computed flat name. Both the source path and the
     * bin name are attacker-controlled, so each interpolation site is independently quoted.
     */
    public static void emitDeletion(PrintWriter w, Path path, String encodedName,
            boolean hardDelete) {
        if (hardDelete) {
            w.printf("rm \"%s\"%n", shellQuote(path));
        } else {
            w.printf("mv \"%s\" \"$BIN/%s\"%n", shellQuote(path), shellQuoteString(encodedName));
        }
    }

    /**
     * Escapes the four characters that retain special meaning inside double-quoted shell
     * arguments (\, ", $, `). Order matters: backslash must be replaced first, otherwise the
     * substitutions added by the later steps would themselves get re-escaped.
     *
     * Newlines and other control characters need no escaping and pass through unchanged: bash
     * keeps a literal newline as part of the surrounding double-quoted word, so the emitted
     * {@code mv}/{@code rm} line still targets the single file named verbatim — it is not split
     * across commands. This is the only correctness guarantee that matters here; the four
     * metacharacters above are the complete set this routine must neutralize.
     */
    public static String shellQuote(Path p) {
        return shellQuoteString(p.toString());
    }

    public static String shellQuoteString(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
    }
}
