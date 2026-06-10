package dev.erancha.folderscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.erancha.folderscanner.ReportPrinter.FilterTally;
import dev.erancha.folderscanner.producer.FileExtensions;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Pins the end-of-run report formatting that used to be inlined in Main.run() and was therefore
 * only reachable through the bash e2e harness. Each filter section must appear exactly when its
 * filter was active and stay byte-for-byte stable, since the aggregate --out tee mirrors it to a
 * file users diff across runs.
 */
final class ReportPrinterTest {

    private static String capture(java.util.function.Consumer<PrintStream> body) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        body.accept(out);
        out.flush();
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void size_filter_section_appears_only_when_a_min_size_was_set() {
        FilterTally active = new FilterTally(1024L, 3L, 5_000L, FileExtensions.IncludeSet.ALL, 0L,
                0L, 0L, 0L);
        String out = capture(o -> ReportPrinter.printFilterSummary(o, active));
        assertEquals(String.format("%nSkipped (size < 1.00 KB): 3 files (4.88 KB).%n"), out);

        FilterTally inactive = new FilterTally(0L, 0L, 0L, FileExtensions.IncludeSet.ALL, 0L, 0L,
                0L, 0L);
        assertEquals("", capture(o -> ReportPrinter.printFilterSummary(o, inactive)));
    }

    @Test
    void extension_filter_section_appears_only_when_the_include_list_is_not_all() {
        FileExtensions.IncludeSet only = FileExtensions.parse("txt,md");
        FilterTally active = new FilterTally(0L, 0L, 0L, only, 7L, 2_048L, 0L, 0L);
        String out = capture(o -> ReportPrinter.printFilterSummary(o, active));
        assertEquals(String.format("%nSkipped (extension not in [md, txt]): 7 files (2.00 KB).%n"),
                out);

        FilterTally all = new FilterTally(0L, 0L, 0L, FileExtensions.IncludeSet.ALL, 0L, 0L, 0L, 0L);
        assertEquals("", capture(o -> ReportPrinter.printFilterSummary(o, all)));
    }

    @Test
    void inaccessible_section_appears_only_when_a_path_was_unreadable() {
        FilterTally active = new FilterTally(0L, 0L, 0L, FileExtensions.IncludeSet.ALL, 0L, 0L, 2L,
                4L);
        String out = capture(o -> ReportPrinter.printFilterSummary(o, active));
        assertTrue(out.contains("Inaccessible (permission denied or IO error): 2 directories, "
                + "4 files"), out);

        FilterTally none = new FilterTally(0L, 0L, 0L, FileExtensions.IncludeSet.ALL, 0L, 0L, 0L,
                0L);
        assertEquals("", capture(o -> ReportPrinter.printFilterSummary(o, none)));
    }

    @Test
    void emits_nothing_when_every_filter_was_inactive() {
        FilterTally none = new FilterTally(0L, 0L, 0L, FileExtensions.IncludeSet.ALL, 0L, 0L, 0L,
                0L);
        assertEquals("", capture(o -> ReportPrinter.printFilterSummary(o, none)));
    }

    @Test
    void done_line_carries_elapsed_file_count_and_total_bytes() {
        String out = capture(o -> ReportPrinter.printDone(o, 1_500L, 1_234L, 5_242_880L));
        assertEquals(String.format("%nDone in 1.5 s. Files=1,234  TotalBytes=5.00 MB%n"), out);
    }
}
