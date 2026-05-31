package dev.erancha.folderscanner.producer;

import dev.erancha.folderscanner.data.FileInfo;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Producer-side SPI (Service Provider Interface) for building one FileInfo per regular file.
 * Supplied by the consumer so the producer is agnostic of which FileInfo variant the consumer
 * needs.
 */
@FunctionalInterface
public interface FileInfoFactory {
    FileInfo create(Path path, BasicFileAttributes attrs);
}
