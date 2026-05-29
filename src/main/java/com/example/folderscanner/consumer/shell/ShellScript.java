package com.example.folderscanner.consumer.shell;

import com.example.folderscanner.data.Format;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Body-agnostic primitives shared by every consumer that emits a destructive shell script
 * (duplicates removal, filemanager deletion): the shebang preamble, the soft-delete trash bin
 * setup, the type-to-confirm safety banner, the per-file delete line, and shell quoting.
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
     * Limitation: literal newlines and other control characters are passed through unchanged.
     * On Unix, a filename can legitimately contain {@code \n}, and the resulting {@code mv}
     * or {@code rm} line would be split across two shell commands. Out of scope for the
     * target environments (these scripts run against user data trees, not adversarial fuzzing
     * inputs); called out here so a future caller does not assume otherwise.
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
