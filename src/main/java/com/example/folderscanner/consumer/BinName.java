package com.example.folderscanner.consumer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encodes absolute filesystem paths into flat bin filenames by replacing
 * every '/' with '_' and dropping the leading underscore. Resolves
 * collisions deterministically by appending ".1", ".2", ... to the
 * second, third, ... arrivals.
 *
 * Called once per script generation with the full list of paths, so the
 * generated script never relies on shell-side collision handling.
 */
public final class BinName {

    private BinName() {}

    /** Encode one path in isolation. Does NOT handle collisions across a batch. */
    public static String encode(Path absolutePath) {
        String s = absolutePath.toString().replace('/', '_');
        return s.startsWith("_") ? s.substring(1) : s;
    }

    /**
     * Encode every path in a batch. Returns a map from each input path to
     * its bin filename. On collisions the first-arrival keeps the plain
     * encoded name; subsequent arrivals receive ".1", ".2", ... suffixes
     * in input order.
     */
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
