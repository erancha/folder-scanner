package com.example.folderscanner.data;

import java.nio.file.Path;

/**
 * Variant carrying the full filesystem path. Used by consumers that need
 * to read or act on the file itself (e.g. duplicate detection by content).
 */
public record PathFileInfo(Path path, long size, long lastModifiedMillis)
        implements FileInfo {}
