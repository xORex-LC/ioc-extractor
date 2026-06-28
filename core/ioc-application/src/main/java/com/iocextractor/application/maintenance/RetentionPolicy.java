package com.iocextractor.application.maintenance;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure retention decision: given the entries under a target and the current time,
 * decide which ones are expired. An entry is expired when it is older than
 * {@code maxAge} <em>or</em> falls outside the {@code maxCount} newest entries.
 * The two criteria are unioned, so either can trigger reaping independently.
 *
 * <p>This class is deliberately side-effect free and clock-injected (via {@code now}),
 * so it is exhaustively testable with a table of synthetic entries.
 */
public final class RetentionPolicy {

    private RetentionPolicy() {
    }

    /**
     * Select the expired entries.
     *
     * @param entries  candidate entries (any order)
     * @param now      reference instant for age comparison
     * @param maxAge   maximum age; {@code null}/zero/negative disables age-based reaping
     * @param maxCount maximum number of newest entries to keep; {@code <= 0} disables count-based reaping
     * @return the entries to reap (subset of {@code entries})
     */
    public static List<RetentionEntry> select(List<RetentionEntry> entries,
                                              Instant now,
                                              Duration maxAge,
                                              int maxCount) {
        return selectValues(entries, RetentionEntry::lastModified, now, maxAge, maxCount);
    }

    /** Applies the same age/count union policy to a caller-defined retention unit. */
    public static <T> List<T> selectValues(List<T> entries,
                                           Function<T, Instant> timestamp,
                                           Instant now,
                                           Duration maxAge,
                                           int maxCount) {
        boolean ageActive = maxAge != null && !maxAge.isZero() && !maxAge.isNegative();
        boolean countActive = maxCount > 0;
        if (!ageActive && !countActive) {
            return List.of();
        }
        Set<T> reap = new LinkedHashSet<>();
        for (T entry : entries) {
            boolean tooOld = ageActive && Duration.between(timestamp.apply(entry), now).compareTo(maxAge) > 0;
            if (tooOld) {
                reap.add(entry);
            }
        }
        if (countActive) {
            List<T> sorted = entries.stream()
                    .sorted(Comparator.comparing(timestamp).reversed())
                    .toList();
            for (int i = maxCount; i < sorted.size(); i++) {
                reap.add(sorted.get(i));
            }
        }
        return List.copyOf(reap);
    }
}
