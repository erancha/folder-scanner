package dev.erancha.folderscanner.consumer.folders.growth;

import dev.erancha.folderscanner.consumer.folders.FolderSize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for GrowthReport, the shared renderer of the two delta sections — "Folder growth
 * since…" and "New folders since…" — used by both the scan path and the on-demand compare path.
 */
final class GrowthReportTest {

    private static String render(Map<Path, Long> was, List<FolderSize> current, double thresholdPct) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        GrowthReport.print(new PrintStream(buf, true, StandardCharsets.UTF_8),
                Instant.parse("2026-06-10T12:00:00Z"), thresholdPct, was, current);
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void prints_grown_folders_above_threshold_and_lists_new_folders_separately() {
        Map<Path, Long> was = Map.of(
                Paths.get("/mnt/c/grower"), 100L,
                Paths.get("/mnt/c/stable"), 100L);
        List<FolderSize> current = List.of(
                new FolderSize(Paths.get("/mnt/c/grower"), 2, 150L), // +50%, above the 10% threshold
                new FolderSize(Paths.get("/mnt/c/stable"), 1, 100L), // unchanged
                new FolderSize(Paths.get("/mnt/c/fresh"), 3, 300L)); // absent from the baseline

        String out = render(was, current, 10.0);

        assertTrue(out.contains("Folder growth since"), out);
        assertTrue(out.contains("(> 10%)"), out);
        assertTrue(out.contains("/mnt/c/grower"), "grown folder must appear in growth section");
        assertTrue(out.contains("New folders since"), out);
        assertTrue(out.contains("/mnt/c/fresh"), "new folder must appear in new section");

        String growthSection = out.substring(out.indexOf("Folder growth since"),
                out.indexOf("New folders since"));
        assertFalse(growthSection.contains("/mnt/c/stable"),
                "an unchanged folder must not appear in the growth section");
        assertFalse(growthSection.contains("/mnt/c/fresh"),
                "a brand-new folder has no growth percentage and must not appear in growth section");
    }
}
