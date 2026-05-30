package dev.erancha.folderscanner.consumer.filemanager;

import dev.erancha.folderscanner.config.ManageAction;
import dev.erancha.folderscanner.config.SortKey;
import dev.erancha.folderscanner.config.SortOrder;
import dev.erancha.folderscanner.consumer.AbstractFileConsumer;
import dev.erancha.folderscanner.consumer.shell.OutPathResolver;
import dev.erancha.folderscanner.data.ExtensionFileInfo;
import dev.erancha.folderscanner.data.FileInfo;
import dev.erancha.folderscanner.data.Format;
import dev.erancha.folderscanner.data.PathFileInfo;
import dev.erancha.folderscanner.data.PoisonPill;
import dev.erancha.folderscanner.producer.FileInfoFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

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
public final class FileManager extends AbstractFileConsumer {

    private final String outPathRaw;
    private final ManageAction action;
    private final boolean hardDelete;
    private final Path sourceTree;
    private final SortKey sortKey;
    private final SortOrder sortOrder;

    // Every file surviving the producer filters; multiple drainers append concurrently.
    private final Queue<PathFileInfo> matched = new ConcurrentLinkedQueue<>();

    public FileManager(BlockingQueue<FileInfo> queue, int consumerThreads, String outPathRaw,
            ManageAction action, boolean hardDelete, Path sourceTree, SortKey sortKey,
            SortOrder sortOrder) {
        super(queue, consumerThreads, "file manager");
        this.outPathRaw = outPathRaw == null ? "" : outPathRaw;
        this.action = action;
        this.hardDelete = hardDelete;
        this.sourceTree = sourceTree;
        this.sortKey = sortKey;
        this.sortOrder = sortOrder;
    }

    @Override
    public FileInfoFactory factory() {
        return (path, attrs) -> new PathFileInfo(path, attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    /**
     * Drainer loop: takes messages from the producer queue and records each matched file. Exits on
     * PoisonPill. Switch is over the sealed FileInfo type, so a future variant becomes a compile
     * error here; the ExtensionFileInfo arm surfaces a mis-wired producer instead of a silent cast.
     */
    @Override
    protected void consume() {
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
    protected void report(PrintStream out) {
        List<PathFileInfo> files = new ArrayList<>(matched);
        files.sort(listingComparator());
        if (action == ManageAction.DELETE) {
            reportDelete(out, files);
        } else {
            reportList(out, files);
        }
    }

    // Column widths used while a column precedes others, so the next column starts at a fixed
    // offset; a path long enough to overflow PATH_COLUMN_WIDTH pushes the rest right rather than
    // truncating. The variable-length path is placed last for date/size sorts and needs no width.
    private static final int PATH_COLUMN_WIDTH = 125;
    private static final int SIZE_COLUMN_WIDTH = 8; // widest humanBytesColumn cell, e.g. "999.9 KB"

    private Comparator<PathFileInfo> listingComparator() {
        Comparator<PathFileInfo> base = switch (sortKey) {
        case PATH -> Comparator.comparing((PathFileInfo p) -> p.path().toString());
        case DATE -> Comparator.comparingLong(PathFileInfo::lastModifiedMillis);
        case SIZE -> Comparator.comparingLong(PathFileInfo::size);
        };
        return sortOrder == SortOrder.DESC ? base.reversed() : base;
    }

    // The sorted dimension leads the row so the listing reads in the order it is ranked. Columns
    // preceding the path are fixed-width; the path is padded to a fixed width unless it is the
    // final column (date sort), so whatever follows it still lines up across rows.
    private String listingHeader() {
        return switch (sortKey) {
        case PATH -> "path  modified  size";
        case DATE -> "modified  size  path";
        case SIZE -> "size  path  modified";
        };
    }

    private String formatListRow(PathFileInfo p) {
        String path = p.path().toString();
        String modified = Format.formatTimestamp(p.lastModifiedMillis());
        String size = Format.humanBytesColumn(p.size());
        return switch (sortKey) {
        case PATH -> String.format("%-" + PATH_COLUMN_WIDTH + "s  %s  %s", path, modified, size);
        case DATE -> String.format("%s  %-" + SIZE_COLUMN_WIDTH + "s  %s", modified, size, path);
        case SIZE -> String.format("%-" + SIZE_COLUMN_WIDTH + "s  %-" + PATH_COLUMN_WIDTH + "s  %s",
                size, path, modified);
        };
    }

    private void reportList(PrintStream out, List<PathFileInfo> files) {
        out.printf("%nFiles (%s):%n", listingHeader());
        for (PathFileInfo p : files) {
            out.println(formatListRow(p));
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
}
