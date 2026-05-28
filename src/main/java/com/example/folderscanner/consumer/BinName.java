package com.example.folderscanner.consumer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encodes absolute paths into flat bin filenames ('/' → '_', leading underscore stripped).
 * Collisions are resolved up front in encodeAll by appending ".1", ".2", ... in input order,
 * so the generated script never relies on shell-side collision handling at run time.
 */
public final class BinName {

    private BinName() {}

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
