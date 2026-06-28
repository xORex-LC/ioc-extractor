package com.iocextractor.application.cadence;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CadenceSourceTest {

    private static final Instant START = Instant.parse("2026-06-28T00:00:00Z");

    @Test
    void intervalFiresAtConfiguredProcessingTimeAndRestartsFromConstruction() {
        MutableClock clock = new MutableClock(START);
        var firstProcess = new IntervalCadenceSource(Duration.ofMinutes(5), clock);

        assertThat(firstProcess.isDue(null, null)).isFalse();
        clock.advance(Duration.ofMinutes(5));
        assertThat(firstProcess.isDue(null, null)).isTrue();
        firstProcess.completed();
        assertThat(firstProcess.isDue(null, null)).isFalse();

        clock.advance(Duration.ofMinutes(2));
        var restarted = new IntervalCadenceSource(Duration.ofMinutes(5), clock);
        clock.advance(Duration.ofMinutes(4));
        assertThat(restarted.isDue(null, START)).isFalse();
        clock.advance(Duration.ofMinutes(1));
        assertThat(restarted.isDue(null, START)).isTrue();
    }

    @Test
    void quietPeriodDebouncesNewActivityButDuplicateDoesNotResetIt() {
        MutableClock clock = new MutableClock(START.plusSeconds(10));
        var source = new QuietPeriodCadenceSource(
                Duration.ofSeconds(10), Duration.ofMinutes(1), clock);
        Instant activity = START.plusSeconds(5);

        assertThat(source.isDue(activity, START)).isFalse();
        clock.advance(Duration.ofSeconds(5));
        assertThat(source.isDue(activity, START)).isTrue();

        source.completed();
        Instant newer = clock.instant().plusSeconds(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(source.isDue(newer, START)).isFalse();
        clock.advance(Duration.ofSeconds(10));
        assertThat(source.isDue(newer, START)).isTrue();
    }

    @Test
    void maxCapPreventsStarvationUnderContinuousActivity() {
        MutableClock clock = new MutableClock(START);
        var source = new QuietPeriodCadenceSource(
                Duration.ofSeconds(10), Duration.ofSeconds(25), clock);

        assertThat(source.isDue(START.plusSeconds(1), null)).isFalse();
        for (int seconds = 5; seconds < 25; seconds += 5) {
            clock.advance(Duration.ofSeconds(5));
            assertThat(source.isDue(clock.instant(), null)).isFalse();
        }
        clock.advance(Duration.ofSeconds(5));
        assertThat(source.isDue(clock.instant(), null)).isTrue();
    }

    @Test
    void registryRejectsUnknownOrIncompletePolicies() {
        MutableClock clock = new MutableClock(START);

        assertThatThrownBy(() -> CadenceSources.create(
                "quiet-period", Duration.ofMinutes(1), Duration.ofSeconds(5), null, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("maxCap");
        assertThatThrownBy(() -> CadenceSources.create(
                "on-new-rows", Duration.ofMinutes(1), null, null, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported cadence");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
