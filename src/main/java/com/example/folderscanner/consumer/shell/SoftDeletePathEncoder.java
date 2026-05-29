package com.example.folderscanner.consumer.shell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the destination filename each redundant file is renamed to when the soft-delete
 * script moves it into the trash directory.
 *
 * The whole flat-naming problem is resolved up front: encodeAll assigns deterministic
 * .1/.2/... suffixes to colliding inputs in arrival order, so the generated shell script
 * is fully decided at generation time and never depends on runtime filesystem state to
 * disambiguate targets.
 *
 * Every produced name is capped at {@link #MAX_FILENAME_BYTES} so the script's {@code mv}
 * always has a creatable target: a flattened path that would exceed the cap is truncated and
 * a hash of the full path is appended, keeping the name deterministic and distinct per source
 * path. Collision suffixes are folded into the same cap, so they cannot push a name back over.
 */
public final class SoftDeletePathEncoder {

    // ext4 and most other Unix filesystems cap a single path component at 255 bytes (not chars).
    private static final int MAX_FILENAME_BYTES = 255;

    private SoftDeletePathEncoder() {}

    /** Single-path encoding with no collision awareness. Use encodeAll for the batch case. */
    public static String encode(Path absolutePath) {
        return cap(flatten(absolutePath));
    }

    /** Batch encoding with deterministic .1/.2 suffixes on collision (first arrival keeps base). */
    public static Map<Path, String> encodeAll(List<Path> paths) {
        Map<Path, String> out = new HashMap<>();
        Set<String> taken = new HashSet<>();
        for (Path p : paths) {
            String base = flatten(p);
            String name = cap(base);
            int n = 1;
            while (!taken.add(name)) {
                name = cap(base + "." + n++);
            }
            out.put(p, name);
        }
        return out;
    }

    private static String flatten(Path absolutePath) {
        String s = absolutePath.toString().replace('/', '_');
        return s.startsWith("_") ? s.substring(1) : s;
    }

    /**
     * Bounds a flattened name to {@link #MAX_FILENAME_BYTES} UTF-8 bytes. Short names pass
     * through untouched; longer ones are cut on a code-point boundary and suffixed with a hash
     * of the full input, so distinct inputs stay distinct and the result is reproducible.
     */
    private static String cap(String name) {
        if (utf8Length(name) <= MAX_FILENAME_BYTES) return name;
        String tail = "~" + sha256Prefix(name);
        int budget = MAX_FILENAME_BYTES - tail.length();   // tail is ASCII, so chars == bytes
        return truncateToBytes(name, budget) + tail;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String truncateToBytes(String s, int maxBytes) {
        StringBuilder head = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int chars = Character.charCount(cp);
            int bytes = s.substring(i, i + chars).getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) break;
            head.appendCodePoint(cp);
            used += bytes;
            i += chars;
        }
        return head.toString();
    }

    private static String sha256Prefix(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JVM digest", e);
        }
    }
}
