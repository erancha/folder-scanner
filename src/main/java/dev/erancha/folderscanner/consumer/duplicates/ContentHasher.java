package dev.erancha.folderscanner.consumer.duplicates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for duplicate detection.
 *
 * Two-stage by design: smallHash reads only the first page (4 KB), enough to split most
 * same-size groups without paying the full read cost. Only files that still collide on
 * the small hash get full-hashed. On typical trees this skips ~95% of bytes.
 */
public final class ContentHasher {

    /** First page of most filesystems — enough entropy to split nearly all same-size groups. */
    public static final int SMALL_HASH_BYTES = 4 * 1024;

    /** Sized to a typical FS block so reads align to block boundaries. */
    private static final int FULL_READ_BUF = 64 * 1024;

    private ContentHasher() {}

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
