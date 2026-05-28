package com.example.folderscanner.consumer.duplicates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for SoftDeletePathEncoder. Pins the leading-slash stripping rule, the
 * slash-to-underscore substitution, and the deterministic .1/.2 collision resolution that
 * the generated shell script relies on to give each redundant file a unique destination
 * filename.
 */
final class SoftDeletePathEncoderTest {

    @Test
    void encode_replaces_slashes_with_underscores_and_drops_leading_underscore() {
        assertEquals("home_user_file.txt",
                SoftDeletePathEncoder.encode(Paths.get("/home/user/file.txt")));
    }

    @Test
    void encode_handles_relative_paths_without_dropping_a_real_character() {
        assertEquals("a_b.txt", SoftDeletePathEncoder.encode(Paths.get("a/b.txt")));
    }

    @Test
    void encodeAll_returns_plain_name_when_paths_do_not_collide() {
        Map<Path, String> names = SoftDeletePathEncoder.encodeAll(List.of(
                Paths.get("/a/x.txt"),
                Paths.get("/a/y.txt")));
        assertEquals("a_x.txt", names.get(Paths.get("/a/x.txt")));
        assertEquals("a_y.txt", names.get(Paths.get("/a/y.txt")));
    }

    @Test
    void encodeAll_suffixes_colliding_arrivals_in_input_order() {
        Path first  = Paths.get("/a/b/c.txt");
        Path second = Paths.get("/a_b/c.txt");          // encodes to same string as first
        Path third  = Paths.get("/a_b_c.txt");          // also encodes to same string
        Map<Path, String> names = SoftDeletePathEncoder.encodeAll(List.of(first, second, third));

        assertEquals("a_b_c.txt",   names.get(first));
        assertEquals("a_b_c.txt.1", names.get(second));
        assertEquals("a_b_c.txt.2", names.get(third));
    }

    @Test
    void encodeAll_assigns_unique_names_to_every_input() {
        Path a = Paths.get("/x/y/z");
        Path b = Paths.get("/x_y/z");
        Map<Path, String> names = SoftDeletePathEncoder.encodeAll(List.of(a, b));
        assertNotEquals(names.get(a), names.get(b));
    }
}
