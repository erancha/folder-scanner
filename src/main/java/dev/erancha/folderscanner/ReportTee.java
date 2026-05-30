package dev.erancha.folderscanner;

import dev.erancha.folderscanner.config.Config;
import dev.erancha.folderscanner.config.ConsumerKind;
import dev.erancha.folderscanner.config.ManageAction;
import dev.erancha.folderscanner.consumer.shell.OutPathResolver;
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
 * yields the {@link PrintStream} a report is written through: either a tee that fans output to both
 * the terminal and the file, or plain {@code System.out} when no file is requested. Closing flushes
 * and closes the file. Only the report-producing consumers tee — the script-emitting modes
 * (duplicates, filemanager --action=delete) resolve {@code --out} as a script path themselves.
 */
final class ReportTee implements AutoCloseable {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PrintStream out;
    private final PrintStream teeFile;

    private ReportTee(PrintStream out, PrintStream teeFile) {
        this.out = out;
        this.teeFile = teeFile;
    }

    /** Report stream: the tee when {@code --out} mirrors to a file, otherwise plain stdout. */
    PrintStream out() {
        return out;
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
     * Builds a tee stream when this run produces a report and {@code --out} was given; otherwise
     * hands back plain {@code System.out}. A directory / trailing-slash {@code --out} is auto-named
     * per consumer; a verbatim path is used as-is. Diagnostics on stderr stay terminal-only.
     */
    static ReportTee install(Config cfg) throws IOException {
        PrintStream terminal = System.out;
        if (!tees(cfg.consumerKind(), cfg.action()) || cfg.outPath().isEmpty()) {
            return new ReportTee(terminal, null);
        }
        Path outFile = OutPathResolver.resolve(cfg.outPath(),
                autoName(cfg.consumerKind(), LocalDateTime.now().format(STAMP)));
        PrintStream teeFile = new PrintStream(new BufferedOutputStream(Files.newOutputStream(outFile)),
                false, StandardCharsets.UTF_8);
        PrintStream tee = new PrintStream(new TeeOutputStream(terminal, teeFile), true,
                StandardCharsets.UTF_8);
        return new ReportTee(tee, teeFile);
    }

    @Override
    public void close() {
        if (teeFile != null) {
            out.flush();
            teeFile.close();
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
