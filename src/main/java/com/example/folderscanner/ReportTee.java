package com.example.folderscanner;

import com.example.folderscanner.config.Config;
import com.example.folderscanner.config.ConsumerKind;
import com.example.folderscanner.config.ManageAction;
import com.example.folderscanner.consumer.shell.OutPathResolver;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Owns the optional mirroring of a consumer's stdout report to an {@code --out} file. Installing
 * redirects {@link System#out} through a {@link TeeOutputStream}; closing flushes, closes the file,
 * and restores the original stream. Only the report-producing consumers tee — the script-emitting
 * modes (duplicates, filemanager --action=delete) resolve {@code --out} as a script path themselves.
 */
final class ReportTee implements AutoCloseable {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PrintStream original;
    private final PrintStream teeFile;

    private ReportTee(PrintStream original, PrintStream teeFile) {
        this.original = original;
        this.teeFile = teeFile;
    }

    /** True for the consumers whose primary output is a stdout report that {@code --out} mirrors. */
    static boolean tees(ConsumerKind kind, ManageAction action) {
        return kind == ConsumerKind.AGGREGATE
                || (kind == ConsumerKind.FILEMANAGER && action == ManageAction.LIST);
    }

    /** Auto-name whose prefix distinguishes the aggregate report from the file-list report. */
    static String autoName(ConsumerKind kind, String timestamp) {
        String prefix = kind == ConsumerKind.AGGREGATE ? "aggregator-" : "file-list-";
        return prefix + timestamp + ".out";
    }

    /**
     * Redirects {@link System#out} to a tee when this run produces a report and {@code --out} was
     * given; otherwise returns a no-op handle. A directory / trailing-slash {@code --out} is
     * auto-named per consumer; a verbatim path is used as-is. Diagnostics on stderr stay
     * terminal-only.
     */
    static ReportTee install(Config cfg) throws IOException {
        PrintStream original = System.out;
        if (!tees(cfg.consumerKind(), cfg.action()) || cfg.outPath().isEmpty()) {
            return new ReportTee(original, null);
        }
        Path outFile = OutPathResolver.resolve(cfg.outPath(),
                autoName(cfg.consumerKind(), LocalDateTime.now().format(STAMP)));
        PrintStream teeFile = new PrintStream(new BufferedOutputStream(Files.newOutputStream(outFile)),
                false, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new TeeOutputStream(original, teeFile), true,
                StandardCharsets.UTF_8));
        return new ReportTee(original, teeFile);
    }

    @Override
    public void close() {
        if (teeFile != null) {
            System.out.flush();
            teeFile.close();
            System.setOut(original);
        }
    }

    /** Fans each byte to two streams so a consumer's {@code --out} can mirror stdout to a file. */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeOutputStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int x) throws IOException {
            a.write(x);
            b.write(x);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}
