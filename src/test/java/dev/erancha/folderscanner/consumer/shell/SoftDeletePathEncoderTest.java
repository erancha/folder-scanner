package dev.erancha.folderscanner.consumer.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static Path deepPath(int segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) sb.append("/segment").append(i);
        sb.append("/file.bin");
        return Paths.get(sb.toString());
    }

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void encode_caps_target_filename_at_the_filesystem_byte_limit() {
        String name = SoftDeletePathEncoder.encode(deepPath(100));
        assertTrue(utf8Bytes(name) <= 255,
                "expected <= 255 bytes but was " + utf8Bytes(name) + ": " + name);
    }

    @Test
    void encode_is_deterministic_for_the_same_path() {
        Path p = deepPath(80);
        assertEquals(SoftDeletePathEncoder.encode(p), SoftDeletePathEncoder.encode(p));
    }

    @Test
    void encode_keeps_distinct_long_paths_distinct() {
        assertNotEquals(
                SoftDeletePathEncoder.encode(deepPath(100)),
                SoftDeletePathEncoder.encode(deepPath(101)));
    }

    @Test
    void encodeAll_keeps_every_name_within_the_limit_even_when_suffixing_collisions() {
        // Two distinct over-limit paths that flatten to the same string: one slash-separated,
        // one with the same components already underscore-joined. The second forces a .1 suffix
        // onto an already-truncated base — the case that compounds past the byte limit.
        String slashed = deepPath(100).toString();
        Path slash = Paths.get(slashed);
        Path underscore = Paths.get("/" + slashed.substring(1).replace('/', '_'));

        List<Path> paths = new ArrayList<>(List.of(slash, underscore));
        Map<Path, String> names = SoftDeletePathEncoder.encodeAll(paths);

        assertNotEquals(names.get(slash), names.get(underscore));
        Set<String> seen = new HashSet<>();
        for (String n : names.values()) {
            assertTrue(utf8Bytes(n) <= 255, "name exceeds 255 bytes: " + utf8Bytes(n) + " " + n);
            assertTrue(seen.add(n), "duplicate target name: " + n);
        }
    }
}
