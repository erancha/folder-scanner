package com.example.folderscanner.consumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the user-supplied --out=PATH into a concrete output file.
 *   - empty input            → defaultName in cwd
 *   - existing directory     → defaultName inside that directory
 *   - trailing '/'           → defaultName inside that (possibly missing) directory
 *   - anything else          → input verbatim
 */
public final class OutPathResolver {

    private OutPathResolver() {}

    public static Path resolve(String raw, String defaultName) {
        if (raw == null || raw.isEmpty()) {
            return Paths.get(defaultName);
        }
        if (raw.endsWith("/")) {
            return Paths.get(raw, defaultName);
        }
        Path p = Paths.get(raw);
        if (Files.isDirectory(p)) {
            return p.resolve(defaultName);
        }
        return p;
    }
}
