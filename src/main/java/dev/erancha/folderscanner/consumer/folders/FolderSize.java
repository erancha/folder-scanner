package dev.erancha.folderscanner.consumer.folders;

import java.nio.file.Path;

/**
 * One row of the folder-size report: a folder and the recursive file count and byte total of its
 * whole subtree (the folder itself plus every descendant).
 */
public record FolderSize(Path path, long count, long bytes) {}
