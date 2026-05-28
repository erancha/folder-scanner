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

/**
 * Walks a directory tree in parallel and enqueues one FileInfo per regular file.
 *
 * Why ForkJoinPool over a fixed pool: directory trees are uneven (a node_modules subtree can be
 * 100k files while a sibling has 3). Work-stealing lets idle workers pull tasks off busy workers'
 * deques, so a fat subtree doesn't pin one worker while others sit idle.
 *
 * Uses blocking queue.put() — backpressure is built in; the bound (and the OOM defense it provides)
 * is Main's call.
 */
public final class FolderScanner {

    /** Frozen at construction (Set.copyOf) so the per-directory contains() takes no lock. */
    private final Set<String> skipDirNames;
    private final BlockingQueue<FileInfo> queue;
    private final ForkJoinPool pool; // AKA FJP
    private final FileInfoFactory factory;
    private final long minSizeBytes;
    private final FileTypes.IncludeSet includeTypes;
    private final LongAdder filteredBySizeCount = new LongAdder();
    private final LongAdder filteredBySizeBytes = new LongAdder();
    private final LongAdder filteredByTypeCount = new LongAdder();
    private final LongAdder filteredByTypeBytes = new LongAdder();

    public FolderScanner(BlockingQueue<FileInfo> queue, int parallelism, FileInfoFactory factory,
            Set<String> skipDirNames, FileTypes.IncludeSet includeTypes, long minSizeBytes) {
        if (parallelism < 1)
            throw new IllegalArgumentException("parallelism must be >= 1");
        if (minSizeBytes < 0)
            throw new IllegalArgumentException("minSizeBytes must be >= 0");
        this.queue = queue;
        this.factory = factory;
        this.pool = new ForkJoinPool(parallelism);
        this.skipDirNames = Set.copyOf(skipDirNames);
        this.minSizeBytes = minSizeBytes;
        this.includeTypes = includeTypes;
    }

    /** Blocks until every file has been enqueued and every subtree task has completed. */
    public void scan(Path root) throws IOException, InterruptedException {
        if (!Files.isDirectory(root))
            throw new IOException("not a directory: " + root);
        try {
            pool.invoke(new ScanTask(root));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw e;
        }
    }

    public void shutdown() {
        pool.shutdown();
    }

    public long filteredBySizeCount() { return filteredBySizeCount.sum(); }
    public long filteredBySizeBytes() { return filteredBySizeBytes.sum(); }
    public long filteredByTypeCount() { return filteredByTypeCount.sum(); }
    public long filteredByTypeBytes() { return filteredByTypeBytes.sum(); }

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
                        System.err.println(
                                "skip (attrs unreadable): " + child + " — " + e.getMessage());
                        continue;
                    }
                    if (attrs.isDirectory()) {
                        if (skipDirNames.contains(child.getFileName().toString())) {
                            continue;
                        }
                        Path subfolder = child;
                        // Each subfolder becomes a ScanTask: `subfolder` → `subtask`. fork() pushes
                        // `subtask` onto the current FJP worker's local deque (LIFO); idle workers
                        // in `pool` steal from the tail, so the subfolder runs on whichever worker
                        // grabs the task first — no upfront worker↔subfolder binding.
                        ScanTask subtask = new ScanTask(subfolder);
                        subtask.fork();
                        forkedSubtasks.add(subtask);
                    } else if (attrs.isRegularFile()) {
                        if (attrs.size() < minSizeBytes) {
                            // Size-first: one branch on already-loaded attrs, no String work.
                            filteredBySizeCount.increment();
                            filteredBySizeBytes.add(attrs.size());
                            continue;
                        }
                        if (!includeTypes.isAll()) {
                            String ext = FileTypes.extensionOf(child);
                            if (!includeTypes.matches(ext)) {
                                filteredByTypeCount.increment();
                                filteredByTypeBytes.add(attrs.size());
                                continue;
                            }
                        }
                        // Bounded-queue backpressure: blocks here when consumers stall.
                        queue.put(factory.create(child, attrs));
                    }
                }
            } catch (IOException e) {
                System.err.println("skip (dir unreadable): " + dir + " — " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e); // unwrapped by FolderScanner.scan()
            }
            for (ScanTask subtask : forkedSubtasks)
                subtask.join();
        }
    }
}
