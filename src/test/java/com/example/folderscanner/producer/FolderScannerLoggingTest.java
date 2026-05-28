package com.example.folderscanner.producer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.PathFileInfo;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Verifies that recoverable IO errors during a scan are reported as DEBUG events on the
 * FolderScanner logger. Whether the events reach a console at runtime is the appender's
 * concern; this test pins only the API choice (level + message shape).
 */
final class FolderScannerLoggingTest {

    private static final FileInfoFactory PATH_FACTORY = (path, attrs) ->
            new PathFileInfo(path, attrs.size(), attrs.lastModifiedTime().toMillis());

    @Test
    void dir_unreadable_emits_debug_event(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions required to simulate an unreadable directory");

        // chmod 000 a child directory so newDirectoryStream(child) fails with AccessDenied
        // when the scanner tries to recurse into it, hitting the 'skip (dir unreadable)' branch.
        Path unreadable = root.resolve("locked");
        Files.createDirectory(unreadable);
        Files.setPosixFilePermissions(unreadable, Collections.emptySet());

        Logger logger = (Logger) LoggerFactory.getLogger(FolderScanner.class);
        ListAppender<ILoggingEvent> captor = new ListAppender<>();
        captor.start();
        logger.addAppender(captor);
        Level originalLevel = logger.getLevel();
        boolean originalAdditive = logger.isAdditive();
        logger.setLevel(Level.ALL);
        // Quiet the test run: stop events from propagating to root appenders for the duration.
        logger.setAdditive(false);
        try {
            BlockingQueue<FileInfo> queue = new ArrayBlockingQueue<>(8);
            FolderScanner scanner = new FolderScanner(queue, 2, PATH_FACTORY,
                    Set.of(), FileExtensions.IncludeSet.ALL, 0L);
            scanner.scan(root);
            scanner.shutdown();
        } finally {
            logger.detachAppender(captor);
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
            captor.stop();
            Files.setPosixFilePermissions(unreadable,
                    PosixFilePermissions.fromString("rwx------"));
        }

        List<ILoggingEvent> events = captor.list;
        boolean foundDebugSkip = events.stream().anyMatch(e ->
                e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage() != null
                        && e.getFormattedMessage().contains("skip (dir unreadable)"));
        assertTrue(foundDebugSkip,
                "expected a DEBUG-level 'skip (dir unreadable)' event; got: " + events);
    }
}
