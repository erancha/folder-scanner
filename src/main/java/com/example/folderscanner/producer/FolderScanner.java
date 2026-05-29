package com.example.folderscanner.producer;

import com.example.folderscanner.data.FileInfo;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a directory tree in parallel and enqueues one FileInfo per regular file.
 *
 * Uses ForkJoinPool because directory trees are uneven: work-stealing keeps idle workers useful
 * when one fat subtree (e.g. node_modules) would otherwise pin a single worker.
 */
public final class FolderScanner {

    /**
     * Channel for recoverable per-entry IO failures (unreadable directories or attributes). These
     * are expected during a deep walk — locked system folders, denied ACLs, transient races — so
     * each one is emitted only at DEBUG and stays below the default Logback threshold: surfacing a
     * line per skipped entry would bury the report under noise on permission-restricted trees.
     *
     * Suppressing the per-error detail must not hide that skips happened, so the failures are
     * tallied in {@link #inaccessibleDirCount} / {@link #inaccessibleFileCount} and reported once
     * at end of run — the user needs the count to judge whether the output is complete.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(FolderScanner.class);

    /** Frozen at construction (Set.copyOf) so the per-directory contains() takes no lock. */
    private final Set<String> skipDirNames;
    private final BlockingQueue<FileInfo> queue;
    private final ForkJoinPool scanWorkers;
    private final FileInfoFactory factory;
    private final long minSizeBytes;
    private final FileExtensions.IncludeSet includeExtensions;
    private final LongAdder filteredBySizeCount = new LongAdder();
    private final LongAdder filteredBySizeBytes = new LongAdder();
    private final LongAdder filteredByExtensionCount = new LongAdder();
    private final LongAdder filteredByExtensionBytes = new LongAdder();
    private final LongAdder inaccessibleDirCount = new LongAdder();
    private final LongAdder inaccessibleFileCount = new LongAdder();

    public FolderScanner(BlockingQueue<FileInfo> queue, int parallelism, FileInfoFactory factory,
            Set<String> skipDirNames, FileExtensions.IncludeSet includeExtensions,
            long minSizeBytes) {
        if (parallelism < 1)
            throw new IllegalArgumentException("parallelism must be >= 1");
        if (minSizeBytes < 0)
            throw new IllegalArgumentException("minSizeBytes must be >= 0");
        this.queue = queue;
        this.factory = factory;
        this.scanWorkers = new ForkJoinPool(parallelism);
        this.skipDirNames = Set.copyOf(skipDirNames);
        this.minSizeBytes = minSizeBytes;
        this.includeExtensions = includeExtensions;
    }

    /** Blocks until every file has been enqueued and every subtree task has completed. */
    public void scan(Path root) throws InterruptedException {
        try {
            scanWorkers.invoke(new ScanTask(root));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw e;
        }
    }

    public void shutdown() {
        scanWorkers.shutdown();
    }

    public long filteredBySizeCount() {
        return filteredBySizeCount.sum();
    }

    public long filteredBySizeBytes() {
        return filteredBySizeBytes.sum();
    }

    public long filteredByExtensionCount() {
        return filteredByExtensionCount.sum();
    }

    public long filteredByExtensionBytes() {
        return filteredByExtensionBytes.sum();
    }

    public long inaccessibleDirCount() {
        return inaccessibleDirCount.sum();
    }

    public long inaccessibleFileCount() {
        return inaccessibleFileCount.sum();
    }

    /** One task per directory: enumerate children, fork subdirs, enqueue regular files, join. */
    private final class ScanTask extends RecursiveAction {

        private final Path dir;

        ScanTask(Path dir) {
            this.dir = dir;
        }

        @Override
        protected void compute() {
            List<ScanTask> forkedSubtasks = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    BasicFileAttributes attrs;
                    try {
                        // NOFOLLOW_LINKS: a symlinked dir reports isDirectory()=false, so cycles
                        // are naturally broken without an explicit visited-set.
                        attrs = Files.readAttributes(child, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        inaccessibleFileCount.increment();
                        LOGGER.debug("skip (attrs unreadable): {} — {}", child, e.getMessage());
                        continue;
                    }
                    if (attrs.isDirectory()) {
                        if (skipDirNames.contains(child.getFileName().toString())) {
                            continue;
                        }
                        Path subfolder = child;
                        // Each subfolder becomes a ScanTask: `subfolder` → `subtask`. fork() pushes
                        // `subtask` onto the current worker's local deque (LIFO); idle workers in
                        // `scanWorkers` steal from the tail, so the subfolder runs on whichever
                        // worker grabs the task first — no upfront worker↔subfolder binding.
                        ScanTask subtask = new ScanTask(subfolder);
                        subtask.fork();
                        forkedSubtasks.add(subtask);
                    } else if (attrs.isRegularFile()) {
                        if (attrs.size() < minSizeBytes) {
                            // Check size before extension: branch on already-loaded attrs.
                            filteredBySizeCount.increment();
                            filteredBySizeBytes.add(attrs.size());
                            continue;
                        }
                        if (!includeExtensions.isAll()) {
                            String ext = FileExtensions.extensionOf(child);
                            if (!includeExtensions.matches(ext)) {
                                filteredByExtensionCount.increment();
                                filteredByExtensionBytes.add(attrs.size());
                                continue;
                            }
                        }
                        // Bounded-queue backpressure: blocks here when consumers stall.
                        queue.put(factory.create(child, attrs));
                    }
                }
            } catch (IOException e) {
                inaccessibleDirCount.increment();
                LOGGER.debug("skip (dir unreadable): {} — {}", dir, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e); // FJP compute() can't throw checked exceptions
            }
            for (ScanTask subtask : forkedSubtasks)
                subtask.join();
        }
    }
}
