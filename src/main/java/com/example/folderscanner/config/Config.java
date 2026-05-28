package com.example.folderscanner.config;

import com.example.folderscanner.data.Format;
import com.example.folderscanner.producer.FileExtensions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Typed snapshot of the runtime configuration parsed from -D system properties.
 *
 * {@link #parse(Properties, int)} aggregates every validation failure into one exception so the
 * user sees all CLI mistakes from a single run rather than fixing them one at a time.
 */
public record Config(
        int queueSize,
        boolean statsEnabled,
        int producers,
        int consumers,
        QueueType queueType,
        ConsumerKind consumerKind,
        String outPath,
        boolean hardDelete,
        long minSizeBytes,
        Set<String> excludeDirs,
        FileExtensions.IncludeSet includeExtensions) {

    /**
     * Reads every supported -D property from {@code props}, applying defaults for missing keys
     * and collecting all validation failures into a single IllegalArgumentException. {@code ncpu}
     * is a parameter (not {@code Runtime.getRuntime().availableProcessors()}) so the same config
     * can be reproduced on a different host.
     */
    public static Config parse(Properties props, int ncpu) {
        List<String> errors = new ArrayList<>();

        int queueSize = parseIntOrDefault(props, "queuesize", "--queue-size", 4096, errors);
        boolean stats = parseBool(props, "stats");
        int producers = parseIntOrDefault(props, "producers", "--producers", Math.max(8, ncpu * 4), errors);
        int consumers = parseIntOrDefault(props, "consumers", "--consumers", Math.max(4, ncpu * 2), errors);
        if (producers < 1) errors.add("producers must be >= 1");
        if (consumers < 1) errors.add("consumers must be >= 1");

        QueueType queueType = QueueType.parseOrCollect(props.getProperty("queuetype", "abq"), errors);
        ConsumerKind consumerKind = ConsumerKind.parseOrCollect(
                props.getProperty("consumer", "aggregate"), errors);

        String outPath = props.getProperty("out", "");
        boolean hardDelete = parseBool(props, "harddelete");

        long minSizeBytes = 0L;
        try {
            minSizeBytes = Format.parseSize(props.getProperty("minsize", "0"));
        } catch (IllegalArgumentException e) {
            errors.add("Invalid --min-size: " + e.getMessage());
        }

        FileExtensions.IncludeSet includeExtensions = FileExtensions.parse(
                props.getProperty("fileextensions", "*"));

        Set<String> excludeDirs = parseExcludeDirs(props.getProperty("exclude", ".git"));
        if (excludeDirs.isEmpty()) {
            errors.add("--exclude is required: pass a comma-separated list of directory basenames "
                    + "to skip (e.g. --exclude=.git,node_modules,target). "
                    + "See README for recommended lists.");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }

        return new Config(queueSize, stats, producers, consumers, queueType, consumerKind, outPath,
                hardDelete, minSizeBytes, excludeDirs, includeExtensions);
    }

    private static int parseIntOrDefault(
            Properties props, String key, String cliFlag, int def, List<String> errors) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            errors.add(cliFlag + " must be an integer (got: " + v + ")");
            return def;
        }
    }

    private static boolean parseBool(Properties props, String key) {
        return Boolean.parseBoolean(props.getProperty(key, "false"));
    }

    private static Set<String> parseExcludeDirs(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }
}
