package com.iocextractor.application.maintenance;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-06-22T00:00:00Z");

    private static RetentionEntry entry(String name, Duration age) {
        return new RetentionEntry(Path.of(name), NOW.minus(age), Path.of("."));
    }

    @Test
    void age_only_reaps_entries_older_than_max_age() {
        List<RetentionEntry> entries = List.of(
                entry("fresh", Duration.ofDays(1)),
                entry("stale", Duration.ofDays(10)));

        List<RetentionEntry> reaped = RetentionPolicy.select(entries, NOW, Duration.ofDays(5), 0);

        assertThat(reaped).extracting(e -> e.path().toString()).containsExactly("stale");
    }

    @Test
    void count_only_keeps_the_newest_n() {
        List<RetentionEntry> entries = List.of(
                entry("newest", Duration.ofHours(1)),
                entry("middle", Duration.ofHours(2)),
                entry("oldest", Duration.ofHours(3)));

        List<RetentionEntry> reaped = RetentionPolicy.select(entries, NOW, null, 2);

        assertThat(reaped).extracting(e -> e.path().toString()).containsExactly("oldest");
    }

    @Test
    void age_and_count_are_unioned() {
        List<RetentionEntry> entries = List.of(
                entry("newest", Duration.ofDays(1)),
                entry("middle", Duration.ofDays(2)),
                entry("ancient", Duration.ofDays(40)));

        // count keeps 2 newest (drops "ancient"); age (>30d) also drops "ancient" -> union = {ancient}
        List<RetentionEntry> byCount = RetentionPolicy.select(entries, NOW, Duration.ofDays(30), 2);
        assertThat(byCount).extracting(e -> e.path().toString()).containsExactly("ancient");

        // tighter age also catches "middle"; union with count(keep 1) -> {middle, ancient}
        List<RetentionEntry> union = RetentionPolicy.select(entries, NOW, Duration.ofDays(1), 1);
        assertThat(union).extracting(e -> e.path().toString())
                .containsExactlyInAnyOrder("middle", "ancient");
    }

    @Test
    void disabled_criteria_reap_nothing() {
        List<RetentionEntry> entries = List.of(entry("a", Duration.ofDays(999)));

        assertThat(RetentionPolicy.select(entries, NOW, null, 0)).isEmpty();
        assertThat(RetentionPolicy.select(entries, NOW, Duration.ZERO, 0)).isEmpty();
        assertThat(RetentionPolicy.select(List.of(), NOW, Duration.ofDays(1), 5)).isEmpty();
    }
}
