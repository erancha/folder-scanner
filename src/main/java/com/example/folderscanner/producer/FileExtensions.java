package com.example.folderscanner.producer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared filename-extension semantics: extension extraction plus the parser/matcher for
 * the producer's --file-extensions include list. Lives in producer/ because the producer is
 * the primary caller (filter before enqueue); Aggregator delegates here so there is one
 * definition of "extension-less" instead of two slightly different ones.
 */
public final class FileExtensions {

    /** Internal token used as the map/set key for extension-less files (README, .gitignore, foo.). */
    public static final String NONE = "(none)";

    private FileExtensions() {}

    /**
     * Lowercase extension, or "(none)" for dotfiles, extension-less names, and trailing dots.
     * Behavior preserved verbatim from the original Aggregator.extensionOf so the by-extension
     * table doesn't shift after the move.
     */
    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return NONE;
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * Parses --file-extensions into an IncludeSet. "*" anywhere short-circuits to all; null /
     * blank also means all (so the CLI parser can pass the raw -Dfileextensions value through
     * without a null guard). Empty tokens are dropped; case + leading dot are normalized.
     */
    public static IncludeSet parse(String raw) {
        if (raw == null) return IncludeSet.ALL;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return IncludeSet.ALL;
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : trimmed.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            if (t.equals("*")) return IncludeSet.ALL;
            if (t.startsWith(".")) t = t.substring(1);
            if (t.isEmpty()) continue;
            t = t.toLowerCase();
            if (t.equals("none")) tokens.add(NONE);
            else tokens.add(t);
        }
        if (tokens.isEmpty()) return IncludeSet.ALL;
        return new IncludeSet(tokens);
    }

    /**
     * Immutable set of accepted extensions, or the "all" sentinel. matches() is the hot path
     * called once per file in the producer — a frozen Set keeps it lock-free and allocation-free.
     */
    public static final class IncludeSet {

        public static final IncludeSet ALL = new IncludeSet(null);

        private final Set<String> accepted;

        private IncludeSet(Set<String> accepted) {
            this.accepted = accepted == null ? null : Set.copyOf(accepted);
        }

        public boolean isAll() { return accepted == null; }

        public boolean matches(String ext) {
            return accepted == null || accepted.contains(ext);
        }

        /** Sorted "[a, b, c]" for the end-of-run report. */
        public String displayList() {
            if (accepted == null) return "[*]";
            List<String> sorted = new ArrayList<>(accepted);
            Collections.sort(sorted);
            return sorted.toString();
        }
    }
}
