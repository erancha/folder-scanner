package dev.erancha.folderscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.erancha.folderscanner.config.Config;
import dev.erancha.folderscanner.config.ConsumerKind;
import dev.erancha.folderscanner.config.ManageAction;
import dev.erancha.folderscanner.config.QueueType;
import dev.erancha.folderscanner.config.SortKey;
import dev.erancha.folderscanner.config.SortOrder;
import dev.erancha.folderscanner.producer.FileExtensions;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the tee decision and auto-naming that used to be inlined in Main.run(). Only the consumers
 * whose primary output is a stdout report mirror it to --out; the script-emitting modes resolve
 * --out themselves and must not tee. The auto-name prefix is what disambiguates the two report
 * files on disk, so it is held stable here.
 */
final class ReportTeeTest {

    @Test
    void aggregate_tees_its_stdout_report() {
        assertTrue(ReportTee.tees(ConsumerKind.AGGREGATE, null));
    }

    @Test
    void filemanager_tees_only_for_the_list_action() {
        assertTrue(ReportTee.tees(ConsumerKind.FILEMANAGER, ManageAction.LIST));
        assertFalse(ReportTee.tees(ConsumerKind.FILEMANAGER, ManageAction.DELETE));
    }

    @Test
    void script_emitting_duplicates_mode_does_not_tee() {
        assertFalse(ReportTee.tees(ConsumerKind.DUPLICATES, null));
    }

    @Test
    void install_mirrors_through_an_explicit_stream_without_touching_System_out(@TempDir Path dir)
            throws IOException {
        Path outFile = dir.resolve("aggregate-report.out");
        Config cfg = new Config(1024, false, 1, 1, QueueType.LBQ, ConsumerKind.AGGREGATE, null,
                SortKey.PATH, SortOrder.ASC, outFile.toString(), false, 0L, Set.of(),
                FileExtensions.IncludeSet.ALL, ".");

        PrintStream original = System.out;
        try (ReportTee tee = ReportTee.install(cfg)) {
            assertSame(original, System.out,
                    "install must not redirect the JVM-global System.out");
            tee.out().print("mirrored-report-line");
        }

        assertSame(original, System.out, "close must leave System.out untouched");
        assertTrue(Files.readString(outFile).contains("mirrored-report-line"),
                "the explicit tee stream must mirror writes to the --out file");
    }

    @Test
    void auto_name_prefix_distinguishes_the_two_report_files() {
        assertEquals("aggregator-20260529-101500.out",
                ReportTee.autoName(ConsumerKind.AGGREGATE, "20260529-101500"));
        assertEquals("file-list-20260529-101500.out",
                ReportTee.autoName(ConsumerKind.FILEMANAGER, "20260529-101500"));
    }
}
