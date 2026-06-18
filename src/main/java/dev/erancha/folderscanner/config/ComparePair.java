package dev.erancha.folderscanner.config;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The two snapshot dates behind {@code --compare=FROM,TO}: the earlier {@code from} baseline and the
 * later {@code to} the diff is rendered for. Present only when the flag is passed.
 */
public record ComparePair(LocalDate from, LocalDate to) {

    /**
     * Parses {@code FROM,TO} (two ISO {@code YYYY-MM-DD} dates) or returns {@code null} when the flag
     * is absent. Malformed input adds one complaint to {@code errors} and returns {@code null} so the
     * rest of validation can continue, mirroring the other parse-or-collect helpers in this package.
     */
    static ComparePair parseOrCollect(String raw, List<String> errors) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != 2) {
            errors.add("--compare must be FROM,TO (two YYYY-MM-DD dates): " + raw);
            return null;
        }
        try {
            return new ComparePair(LocalDate.parse(parts[0].trim()), LocalDate.parse(parts[1].trim()));
        } catch (DateTimeParseException e) {
            errors.add("--compare dates must be YYYY-MM-DD: " + raw);
            return null;
        }
    }
}
