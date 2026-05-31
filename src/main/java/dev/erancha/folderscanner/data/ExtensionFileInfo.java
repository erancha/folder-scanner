package dev.erancha.folderscanner.data;

/**
 * Carries only the file's extension, not its path — chosen by aggregating consumers
 * to keep per-message memory small at queue scale (millions of messages per scan).
 */
public record ExtensionFileInfo(String extension, long size, long lastModifiedMillis)
        implements FileInfo {}
