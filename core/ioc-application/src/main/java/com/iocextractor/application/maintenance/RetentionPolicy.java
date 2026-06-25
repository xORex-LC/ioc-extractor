package com.iocextractor.application.maintenance;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return select(entries, now, maxAge, maxCount, ignored -> "");
    }

    /**
     * Select expired entries while applying count-retention independently per
     * group. Age-retention remains global because age is entry-local.
     *
     * @param entries   candidate entries (any order)
     * @param now       reference instant for age comparison
     * @param maxAge    maximum age; {@code null}/zero/negative disables age-based reaping
     * @param maxCount  maximum number of newest entries to keep per group; {@code <= 0} disables count reaping
     * @param groupBy   group classifier for count-retention
     * @return entries to reap
     */
    public static List<RetentionEntry> select(List<RetentionEntry> entries,
                                              Instant now,
                                              Duration maxAge,
                                              int maxCount,
                                              Function<RetentionEntry, String> groupBy) {
        boolean ageActive = maxAge != null && !maxAge.isZero() && !maxAge.isNegative();
        boolean countActive = maxCount > 0;
        if (!ageActive && !countActive) {
            return List.of();
        }
        Set<RetentionEntry> reap = new LinkedHashSet<>();
        for (RetentionEntry entry : entries) {
            boolean tooOld = ageActive && Duration.between(entry.lastModified(), now).compareTo(maxAge) > 0;
            if (tooOld) {
                reap.add(entry);
            }
        }
        if (countActive) {
            Map<String, List<RetentionEntry>> byGroup = new LinkedHashMap<>();
            for (RetentionEntry entry : entries) {
                byGroup.computeIfAbsent(groupBy.apply(entry), ignored -> new java.util.ArrayList<>()).add(entry);
            }
            for (List<RetentionEntry> group : byGroup.values()) {
                List<RetentionEntry> sorted = group.stream()
                        .sorted(Comparator.comparing(RetentionEntry::lastModified).reversed())
                        .toList();
                for (int i = maxCount; i < sorted.size(); i++) {
                    reap.add(sorted.get(i));
                }
            }
        }
        return List.copyOf(reap);
    }
}
