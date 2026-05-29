package com.example.folderscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.folderscanner.config.ConsumerKind;
import com.example.folderscanner.config.ManageAction;
import org.junit.jupiter.api.Test;

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
    void auto_name_prefix_distinguishes_the_two_report_files() {
        assertEquals("aggregator-20260529-101500.out",
                ReportTee.autoName(ConsumerKind.AGGREGATE, "20260529-101500"));
        assertEquals("file-list-20260529-101500.out",
                ReportTee.autoName(ConsumerKind.FILEMANAGER, "20260529-101500"));
    }
}
