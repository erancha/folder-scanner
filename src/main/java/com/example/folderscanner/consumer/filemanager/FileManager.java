package com.example.folderscanner.consumer.filemanager;

import com.example.folderscanner.config.ManageAction;
import com.example.folderscanner.consumer.FileConsumer;
import com.example.folderscanner.consumer.shell.OutPathResolver;
import com.example.folderscanner.data.ExtensionFileInfo;
import com.example.folderscanner.data.FileInfo;
import com.example.folderscanner.data.Format;
import com.example.folderscanner.data.PathFileInfo;
import com.example.folderscanner.data.PoisonPill;
import com.example.folderscanner.producer.FileInfoFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lists or deletes the files surviving the producer filters (--exclude / --min-size /
 * --file-extensions), making those filters actionable.
 *
 * Single phase: drainer threads record each file's path/size/modified time as it arrives — the
 * per-message work is tiny but high-frequency, so multiple drainers keep the producer's
 * queue.put() from blocking. After POISON, awaitAndReport either prints the listing
 * (--action=list) or writes an inspect-before-run deletion script (--action=delete), reusing the
 * same soft-quarantine/hard-rm machinery ({@link DeleteScript} over the shared shell primitives)
 * as the duplicates consumer.
 */
public final class FileManager implements FileConsumer {

    private final BlockingQueue<FileInfo> queue;
    private final ThreadPoolExecutor drainerPool;
    private final String outPathRaw;
    private final ManageAction action;
    private final boolean hardDelete;
    private final Path sourceTree;

    // Every file surviving the producer filters; multiple drainers append concurrently.
    private final Queue<PathFileInfo> matched = new ConcurrentLinkedQueue<>();
    private final LongAdder totalFiles = new LongAdder();
    private final LongAdder totalBytes = new LongAdder();

    public FileManager(BlockingQueue<FileInfo> queue, int consumerThreads, String outPathRaw,
            ManageAction action, boolean hardDelete, Path sourceTree) {
        if (consumerThreads < 1)
            throw new IllegalArgumentException("consumerThreads must be >= 1");
        this.queue = queue;
        this.outPathRaw = outPathRaw == null ? "" : outPathRaw;
        this.action = action;
        this.hardDelete = hardDelete;
        this.sourceTree = sourceTree;
        this.drainerPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(consumerThreads);
    }

    @Override
    public int drainerCount() {
        return drainerPool.getCorePoolSize();
    }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new PathFileInfo(path, attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    @Override
    public void start() {
        for (int i = 0, n = drainerPool.getCorePoolSize(); i < n; i++) {
            drainerPool.submit(this::consume);
        }
    }

    /**
     * Drainer loop: takes messages from the producer queue and records each matched file. Exits on
     * PoisonPill. Switch is over the sealed FileInfo type, so a future variant becomes a compile
     * error here; the ExtensionFileInfo arm surfaces a mis-wired producer instead of a silent cast.
     */
    void consume() {
        try {
            while (true) {
                FileInfo f = queue.take();
                switch (f) {
                case PathFileInfo p -> {
                    totalFiles.increment();
                    totalBytes.add(p.size());
                    matched.add(p);
                }
                case PoisonPill ignored -> {
                    return;
                }
                case ExtensionFileInfo ignored -> throw new IllegalStateException(
                        "FileManager received ExtensionFileInfo; its factory() produces only "
                                + "PathFileInfo");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void awaitAndReport(PrintStream out) throws InterruptedException {
        drainerPool.shutdown();
        if (!drainerPool.awaitTermination(1, TimeUnit.HOURS)) {
            throw new IllegalStateException("file manager did not terminate within 1 hour");
        }
        List<PathFileInfo> files = new ArrayList<>(matched);
        files.sort(Comparator.comparing(p -> p.path().toString()));
        if (action == ManageAction.DELETE) {
            reportDelete(out, files);
        } else {
            reportList(out, files);
        }
    }

    private void reportList(PrintStream out, List<PathFileInfo> files) {
        out.println("\nFiles (path  size  modified):");
        for (PathFileInfo p : files) {
            out.printf("%s  %s  %s%n", p.path(), Format.humanBytes(p.size()),
                    Format.formatTimestamp(p.lastModifiedMillis()));
        }
        out.printf("%nListed %,d files (%s).%n", totalFiles.sum(),
                Format.humanBytes(totalBytes.sum()));
    }

    private void reportDelete(PrintStream out, List<PathFileInfo> files) {
        if (files.isEmpty()) {
            out.println("FileManager: no files matched. No script written.");
            return;
        }
        List<Path> paths = files.stream().map(PathFileInfo::path).toList();
        Path scriptPath = OutPathResolver.resolve(outPathRaw, "delete-files.sh");
        try {
            DeleteScript.write(scriptPath, sourceTree, paths, totalBytes.sum(), hardDelete);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + scriptPath, e);
        }
        out.printf("FileManager: %d files (%s) %s.%n", files.size(),
                Format.humanBytes(totalBytes.sum()),
                hardDelete ? "to hard-delete" : "to quarantine");
        out.printf("Wrote %s — INSPECT BEFORE RUNNING.%n", scriptPath.toAbsolutePath());
    }

    @Override
    public long totalFilesSeen() {
        return totalFiles.sum();
    }

    @Override
    public long totalBytesSeen() {
        return totalBytes.sum();
    }
}
