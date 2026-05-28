package com.example.folderscanner.consumer.duplicates;

import java.nio.file.Path;
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
 */
public final class SoftDeletePathEncoder {

    private SoftDeletePathEncoder() {}

    /** Single-path encoding with no collision awareness. Use encodeAll for the batch case. */
    public static String encode(Path absolutePath) {
        String s = absolutePath.toString().replace('/', '_');
        return s.startsWith("_") ? s.substring(1) : s;
    }

    /** Batch encoding with deterministic .1/.2 suffixes on collision (first arrival keeps base). */
    public static Map<Path, String> encodeAll(List<Path> paths) {
        Map<Path, String> out = new HashMap<>();
        Set<String> taken = new HashSet<>();
        for (Path p : paths) {
            String base = encode(p);
            String name = base;
            int n = 1;
            while (!taken.add(name)) {
                name = base + "." + n++;
            }
            out.put(p, name);
        }
        return out;
    }
}
