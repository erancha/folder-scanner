package com.example.folderscanner.consumer.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for OutPathResolver. Covers the four input shapes the resolver
 * promises to handle: null/empty, trailing slash, existing directory, and
 * verbatim file path.
 */
final class OutPathResolverTest {

    @Test
    void null_input_yields_default_in_cwd() {
        assertEquals(Paths.get("report.out"),
                OutPathResolver.resolve(null, "report.out"));
    }

    @Test
    void empty_input_yields_default_in_cwd() {
        assertEquals(Paths.get("report.out"),
                OutPathResolver.resolve("", "report.out"));
    }

    @Test
    void trailing_slash_appends_default_filename(@TempDir Path tmp) {
        Path expected = Paths.get(tmp + "/", "report.out");
        assertEquals(expected,
                OutPathResolver.resolve(tmp.toString() + "/", "report.out"));
    }

    @Test
    void existing_directory_appends_default_filename(@TempDir Path tmp) {
        assertEquals(tmp.resolve("report.out"),
                OutPathResolver.resolve(tmp.toString(), "report.out"));
    }

    @Test
    void explicit_file_path_is_returned_verbatim(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("custom-name.sh");
        Files.createFile(file);
        assertEquals(file, OutPathResolver.resolve(file.toString(), "report.out"));
    }

    @Test
    void nonexistent_non_directory_path_is_returned_verbatim() {
        Path raw = Paths.get("/tmp/does-not-exist-xyz/custom.sh");
        assertEquals(raw, OutPathResolver.resolve(raw.toString(), "report.out"));
    }
}
