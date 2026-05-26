package com.example.folderscanner.data;

/**
 * Variant carrying the file's extension. Used by consumers that aggregate
 * by file type without needing the path (saves memory at queue scale).
 * extension is lowercase or the literal "(none)" for files without one.
 */
public record TypeFileInfo(String extension, long size, long lastModifiedMillis)
        implements FileInfo {}
