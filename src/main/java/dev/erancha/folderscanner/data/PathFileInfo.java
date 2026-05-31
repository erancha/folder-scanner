package dev.erancha.folderscanner.data;

import java.nio.file.Path;

/** Carries the full path. Used by consumers that need to read the file (e.g. hashing). */
public record PathFileInfo(Path path, long size, long lastModifiedMillis)
        implements FileInfo {}
