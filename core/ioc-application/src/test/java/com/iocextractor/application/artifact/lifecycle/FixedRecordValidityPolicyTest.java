package com.iocextractor.application.artifact.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedRecordValidityPolicyTest {

    private static final EffectiveTime CONFIRMED_AT =
            EffectiveTime.at(Instant.parse("2026-08-16T00:00:00Z"));

    @Test
    void calculates_an_absolute_deadline_from_confirmation_time() {
        var policy = new FixedRecordValidityPolicy(Duration.ofHours(12));

        ValidityDecision decision = policy.decide(CONFIRMED_AT);

        assertThat(policy.ttl()).isEqualTo(Duration.ofHours(12));
        assertThat(decision.deadline().validUntil())
                .isEqualTo(Instant.parse("2026-08-16T12:00:00Z"));
    }

    @ParameterizedTest
    @MethodSource("nonPositiveDurations")
    void rejects_non_positive_fixed_ttl(Duration invalidTtl) {
        assertThatThrownBy(() -> new FixedRecordValidityPolicy(invalidTtl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejects_missing_ttl_and_confirmation_time() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FixedRecordValidityPolicy(null));

        var policy = new FixedRecordValidityPolicy(Duration.ofHours(1));
        assertThatNullPointerException()
                .isThrownBy(() -> policy.decide(null));
    }

    private static Stream<Duration> nonPositiveDurations() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofDays(-30));
    }
}
