package com.example.folderscanner.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FileExtensions — the shared extension-extraction + include-set parser used by
 * both the producer (for extension filtering) and the Aggregator (for the by-extension table).
 */
final class FileExtensionsTest {

    @Test
    void extensionOf_lowercase_simple() {
        assertEquals("txt", FileExtensions.extensionOf(Paths.get("notes.txt")));
        assertEquals("txt", FileExtensions.extensionOf(Paths.get("NOTES.TXT")));
    }

    @Test
    void extensionOf_dotfile_treated_as_none() {
        // Leading-dot-only names (.gitignore, .bashrc) have lastIndexOf('.') == 0,
        // which means there is no extension — they're config files, not "files of type
        // gitignore". The contract preserves the previous Aggregator behavior.
        assertEquals("(none)", FileExtensions.extensionOf(Paths.get(".gitignore")));
        assertEquals("(none)", FileExtensions.extensionOf(Paths.get(".bashrc")));
    }

    @Test
    void extensionOf_no_dot_is_none() {
        assertEquals("(none)", FileExtensions.extensionOf(Paths.get("README")));
        assertEquals("(none)", FileExtensions.extensionOf(Paths.get("Makefile")));
    }

    @Test
    void extensionOf_trailing_dot_is_none() {
        // Trailing dot means an empty extension — substring(dot+1) would be "", which is
        // not a useful key. Bucket it as "(none)" with the other no-extension cases.
        assertEquals("(none)", FileExtensions.extensionOf(Paths.get("foo.")));
    }

    @Test
    void extensionOf_multi_dot_takes_last_segment() {
        // "archive.tar.gz" is one of many .gz files, not one of two .tar files —
        // last-segment matches how `file` and shell globbing read the name.
        assertEquals("gz", FileExtensions.extensionOf(Paths.get("archive.tar.gz")));
    }

    @Test
    void parse_star_is_all() {
        assertTrue(FileExtensions.parse("*").isAll());
    }

    @Test
    void parse_null_or_blank_defaults_to_all() {
        // Unset / blank == default == "all", so a caller can pass the raw flag value straight
        // through without an extra null guard.
        assertTrue(FileExtensions.parse(null).isAll());
        assertTrue(FileExtensions.parse("").isAll());
        assertTrue(FileExtensions.parse("   ").isAll());
    }

    @Test
    void parse_single_token_matches_only_that_extension() {
        FileExtensions.IncludeSet set = FileExtensions.parse("txt");
        assertFalse(set.isAll());
        assertTrue(set.matches("txt"));
        assertFalse(set.matches("jpg"));
        assertFalse(set.matches("(none)"));
    }

    @Test
    void parse_normalizes_case_and_leading_dot() {
        // ".TXT", "TXT", and "txt" all describe the same extension. Normalize at parse
        // time so matches() can be a plain set lookup with no per-call lowercasing.
        FileExtensions.IncludeSet set = FileExtensions.parse(".TXT, .JPG ,PDF");
        assertTrue(set.matches("txt"));
        assertTrue(set.matches("jpg"));
        assertTrue(set.matches("pdf"));
        assertFalse(set.matches("png"));
    }

    @Test
    void parse_none_token_opts_in_extension_less_files() {
        // The user spells "no extension" as `none`; internally extensionOf returns the
        // literal "(none)" (kept stable so Aggregator's by-extension column doesn't change).
        // The parser bridges those two spellings.
        FileExtensions.IncludeSet set = FileExtensions.parse("txt,none");
        assertTrue(set.matches("txt"));
        assertTrue(set.matches("(none)"));
    }

    @Test
    void parse_drops_empty_tokens_and_whitespace() {
        FileExtensions.IncludeSet set = FileExtensions.parse(", ,txt, ");
        assertFalse(set.isAll());
        assertTrue(set.matches("txt"));
    }

    @Test
    void parse_star_anywhere_short_circuits_to_all() {
        // If "*" appears in the list, the rest is redundant — treat the whole list as "all".
        // Prevents a confusing config like `--file-extensions=txt,*` from quietly excluding pdf.
        assertTrue(FileExtensions.parse("txt,*,pdf").isAll());
    }

    @Test
    void extensionOf_yields_ascii_lowercase_under_turkish_default_locale() {
        // toLowerCase() with no Locale uses Locale.getDefault(); under tr_TR the ASCII 'I'
        // lower-cases to 'ı' (dotless), not 'i'. That breaks every downstream equality check
        // against ASCII keys like "tif". Pin Locale.ROOT for deterministic ASCII folding.
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals("tif", FileExtensions.extensionOf(Paths.get("photo.TIF")));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void parse_yields_ascii_lowercase_tokens_under_turkish_default_locale() {
        // Mirrors the extensionOf guarantee on the CLI side: a user passing
        // --file-extensions=TIF on a tr_TR host must still match files whose
        // extensionOf() returns the ASCII "tif".
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            FileExtensions.IncludeSet set = FileExtensions.parse("TIF");
            assertTrue(set.matches("tif"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void displayList_returns_sorted_normalized_tokens() {
        // Feeds the end-of-run "Skipped (extension not in [...])" report. Sorted so the line is
        // stable across runs regardless of input order.
        assertEquals("[jpg, pdf, txt]", FileExtensions.parse("PDF,.TXT,jpg").displayList());
        assertEquals("[(none), txt]", FileExtensions.parse("txt,none").displayList());
    }
}
