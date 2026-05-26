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

/**
 * What: walks a directory tree in parallel and pushes one FileInfo per regular file onto a
 *       shared bounded queue. One ForkJoin task per directory; subdirectories are forked off
 *       as their own tasks so independent subtrees scan concurrently.
 * Why:  ForkJoinPool gives free work-stealing across CPUs without us hand-managing a thread
 *       pool. A simple Executors.newFixedThreadPool + queue-of-paths would also work but would
 *       either serialize per-folder (one worker per folder) or risk an unbounded backlog of
 *       Path tasks. The hard constraint is OOM avoidance: backpressure comes from the queue
 *       being bounded (LinkedBlockingQueue capacity) - when consumers fall behind, scanner
 *       threads block on put() instead of buffering paths in memory.
 */
public final class FolderScanner {

    /**
     * Caller-supplied directory basenames (e.g. node_modules, target, .mvn, .git) that are
     * never recursed into. Frozen at construction so the per-directory hot path does a single
     * contains() without synchronization. The caller is responsible for including everything
     * that must be skipped — there are no built-in defaults; an empty set means "walk every
     * directory", which is rarely what the user wants and is rejected at the composition root.
     */
    private final Set<String> skipDirNames;

    // APPLICATION QUEUE: handoff to a downstream consumer (this class is the producer);
    // bounded => producer blocks => no OOM.
    private final BlockingQueue<FileInfo> queue;

    // Dedicated pool. Each FJP worker keeps its OWN internal per-task deque (JDK-internal,
    // NOT the application queue above) used for fork/steal/join — that's why FJP here and
    // not a fixed pool: tree-shaped recursive subtasks need work-stealing.
    // Work-stealing = an idle worker (empty own deque) pulls a pending task off the bottom
    // of a busy worker's deque with no central scheduler, so uneven subtrees self-balance.
    private final ForkJoinPool pool;

    /** Builds one FileInfo per regular file; supplied by the chosen consumer. */
    private final FileInfoFactory factory;

    /**
     * What: wires the scanner to a queue, a fresh ForkJoinPool sized to parallelism, the
     *       factory used to construct one FileInfo per regular file, and the set of directory
     *       basenames to skip during traversal.
     * Why:  parallelism is taken as a ctor arg rather than read from Runtime.getRuntime() so
     *       the composition root owns the sizing decision (testability + tuning live with the
     *       caller that wires everything together). The skip-set is fully caller-supplied —
     *       build outputs, dependency caches, and per-app data folders that the duplicate
     *       locator must not touch are listed by the user via start.sh --exclude rather than
     *       hard-coded here, so the policy stays editable per-machine without a rebuild.
     */
    public FolderScanner(BlockingQueue<FileInfo> queue, int parallelism, FileInfoFactory factory,
            Set<String> skipDirNames) {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        this.queue = queue;
        this.factory = factory;
        this.pool = new ForkJoinPool(parallelism);
        // Defensive copy so callers can't mutate the set after construction; immutable so
        // contains() is lock-free on the per-directory hot path.
        this.skipDirNames = Set.copyOf(skipDirNames);
    }

    /**
     * What: scans the given root directory recursively; blocks until every file has been
     *       enqueued and every subtree task has completed.
     * Why:  blocking simplifies the orchestration for the caller - once scan() returns, the
     *       producer is done and the caller can safely insert poison pills for the consumers.
     *       The internal parallelism is unchanged; only the caller-facing API is synchronous.
     */
    public void scan(Path root) throws IOException, InterruptedException {
        if (!Files.isDirectory(root)) throw new IOException("not a directory: " + root);
        try {
            pool.invoke(new ScanTask(root));
        } catch (RuntimeException e) {
            // Unwrap InterruptedException that a worker may have wrapped before propagating.
            if (e.getCause() instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw e;
        }
    }

    /**
     * What: shuts down the ForkJoinPool; safe to call after scan() returns.
     * Why:  the pool is owned by the scanner, so the scanner is responsible for releasing its
     *       worker threads - leaving them running would prevent JVM exit.
     */
    public void shutdown() {
        pool.shutdown();
    }

    /**
     * What: one task per directory; lists children, forks a ScanTask for each subdirectory,
     *       enqueues a FileInfo for each regular file, then joins all forked children.
     * Why:  ForkJoin's fork/join + work-stealing is the natural fit for tree-shaped parallelism
     *       - we don't need to know the tree shape in advance and CPUs stay busy across uneven
     *       subtrees. Symlinks are skipped via NOFOLLOW_LINKS to avoid infinite cycles, and
     *       unreadable entries are logged and skipped rather than aborting the whole scan.
     */
    private final class ScanTask extends RecursiveAction {

        private final Path dir;   // the single directory this task is responsible for

        ScanTask(Path dir) { this.dir = dir; }

        @Override
        protected void compute() {
            // refs to forked children so we can join them; one entry per subdir, NOT per file
            List<ScanTask> subtasks = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    BasicFileAttributes attrs;
                    try {
                        // NOFOLLOW_LINKS: a symlink to a directory reports isDirectory()=false here,
                        // so we naturally skip it and avoid recursion cycles.
                        attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        System.err.println("skip (attrs unreadable): " + entry + " — " + e.getMessage());
                        continue;
                    }
                    if (attrs.isDirectory()) {
                        if (skipDirNames.contains(entry.getFileName().toString())) {
                            continue;        // VCS metadata / build outputs; never recurse, never enqueue
                        }
                        ScanTask sub = new ScanTask(entry);
                        sub.fork();          // hand off to the pool; another worker may pick it up
                        subtasks.add(sub);
                    } else if (attrs.isRegularFile()) {
                        // put() blocks when the queue is full -> backpressure on the producer side.
                        // This is the OOM defense: the queue is bounded, so a slow consumer
                        // simply stalls the scanner instead of buffering paths in memory.
                        queue.put(factory.create(entry, attrs));
                    }
                    // symlinks / special files: silently skipped
                }
            } catch (IOException e) {
                System.err.println("skip (dir unreadable): " + dir + " — " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);   // unwrapped by FolderScanner.scan()
            }
            for (ScanTask sub : subtasks) sub.join();   // wait for our subtree to finish before reporting done
        }
    }

}
