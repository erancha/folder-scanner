package com.example.folderscanner.producer;

import com.example.folderscanner.data.FileInfo;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * SPI the producer calls inside its hot loop to construct one FileInfo per
 * regular file. Supplied by the chosen consumer so the producer stays
 * agnostic of which variant ends up on the queue.
 */
@FunctionalInterface
public interface FileInfoFactory {

    /** Build the message for one regular file. Called from scanner worker threads. */
    FileInfo create(Path path, BasicFileAttributes attrs);
}
