package com.example.folderscanner.consumer.duplicates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ContentHasher — the equality predicate behind every deletion the tool emits.
 * Two files are treated as duplicates only when their hashes match, so these tests pin the two
 * properties the kill-list depends on: identical bytes must hash equal, and any byte difference
 * must hash differently. smallHash is checked separately because it reads only the first 4 KB,
 * so the partial-read loop must fill exactly that window — no more, no less.
 */
final class ContentHasherTest {

    @TempDir
    Path dir;

    private Path write(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static byte[] repeat(byte value, int count) {
        byte[] b = new byte[count];
        Arrays.fill(b, value);
        return b;
    }

    @Test
    void fullHash_is_equal_for_identical_content() throws IOException {
        byte[] content = "the quick brown fox".getBytes();
        Path a = write("a.bin", content);
        Path b = write("b.bin", content.clone());
        assertEquals(ContentHasher.fullHash(a), ContentHasher.fullHash(b));
    }

    @Test
    void fullHash_differs_for_different_content() throws IOException {
        Path a = write("a.bin", "alpha".getBytes());
        Path b = write("b.bin", "beta".getBytes());
        assertNotEquals(ContentHasher.fullHash(a), ContentHasher.fullHash(b));
    }

    @Test
    void fullHash_differs_on_single_byte_flip() throws IOException {
        byte[] base = repeat((byte) 'x', ContentHasher.SMALL_HASH_BYTES * 3);
        byte[] flipped = base.clone();
        flipped[base.length - 1] = 'y';
        Path a = write("a.bin", base);
        Path b = write("b.bin", flipped);
        assertNotEquals(ContentHasher.fullHash(a), ContentHasher.fullHash(b));
    }

    @Test
    void smallHash_is_equal_when_first_page_matches_even_if_tails_differ() throws IOException {
        // Two files that share their first 4 KB but diverge afterward must collide on smallHash.
        // This is the property that lets phase 2 use smallHash as a cheap pre-filter: it reads
        // only the leading window, so anything past SMALL_HASH_BYTES cannot influence the result.
        byte[] sharedPage = repeat((byte) 'p', ContentHasher.SMALL_HASH_BYTES);
        byte[] withTailA = concat(sharedPage, "tail-A".getBytes());
        byte[] withTailB = concat(sharedPage, "tail-B-different-length".getBytes());
        Path a = write("a.bin", withTailA);
        Path b = write("b.bin", withTailB);
        assertEquals(ContentHasher.smallHash(a), ContentHasher.smallHash(b));
        // The tails differ, so the full hash must separate them — proving smallHash truly ignored
        // everything past the first page rather than the files being byte-identical.
        assertNotEquals(ContentHasher.fullHash(a), ContentHasher.fullHash(b));
    }

    @Test
    void smallHash_differs_when_first_page_differs() throws IOException {
        byte[] pageA = repeat((byte) 'p', ContentHasher.SMALL_HASH_BYTES);
        byte[] pageB = pageA.clone();
        pageB[0] = 'q';
        Path a = write("a.bin", pageA);
        Path b = write("b.bin", pageB);
        assertNotEquals(ContentHasher.smallHash(a), ContentHasher.smallHash(b));
    }

    @Test
    void smallHash_of_file_smaller_than_page_covers_whole_file() throws IOException {
        // For a file shorter than SMALL_HASH_BYTES the read loop stops at EOF; the digest must
        // cover exactly the bytes present, so any difference within them is detected.
        Path a = write("a.bin", "short".getBytes());
        Path b = write("b.bin", "shore".getBytes());
        assertNotEquals(ContentHasher.smallHash(a), ContentHasher.smallHash(b));
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
