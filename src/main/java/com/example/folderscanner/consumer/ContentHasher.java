package com.example.folderscanner.consumer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for the duplicate-finder phase 2.
 *
 * smallHash reads at most the first SMALL_HASH_BYTES of a file; used to
 * cheaply split same-size groups before paying the full read cost.
 * fullHash reads the file end-to-end in FULL_READ_BUF chunks.
 *
 * Both methods return a stable lowercase hex string. IO errors propagate;
 * the caller logs and drops the offending path from its group.
 */
public final class ContentHasher {

    /** Initial cheap-split byte count (first page of most filesystems). */
    public static final int SMALL_HASH_BYTES = 4 * 1024;

    /** Streaming buffer for full hashes; 64 KB matches typical FS block sizes. */
    private static final int FULL_READ_BUF = 64 * 1024;

    private ContentHasher() {}

    /** SHA-256 of at most SMALL_HASH_BYTES from the start of the file. */
    public static String smallHash(Path p) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[SMALL_HASH_BYTES];
            int total = 0;
            int n;
            while (total < SMALL_HASH_BYTES
                    && (n = in.read(buf, total, SMALL_HASH_BYTES - total)) != -1) {
                total += n;
            }
            md.update(buf, 0, total);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** SHA-256 of the entire file, streamed in FULL_READ_BUF chunks. */
    public static String fullHash(Path p) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[FULL_READ_BUF];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
