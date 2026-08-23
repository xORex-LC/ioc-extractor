package com.iocextractor.application.artifact.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordLifecycleTest {

    private static final EffectiveTime FIRST =
            EffectiveTime.at(Instant.parse("2026-08-16T00:00:00Z"));
    private static final FixedRecordValidityPolicy POLICY =
            new FixedRecordValidityPolicy(Duration.ofHours(1));

    @Test
    void uses_a_half_open_active_interval() {
        RecordLifecycle lifecycle = RecordLifecycle.start(
                new LifecycleId(1), FIRST, POLICY.decide(FIRST));

        assertThat(lifecycle.isActiveAt(
                EffectiveTime.at(Instant.parse("2026-08-16T00:59:59.999Z")))).isTrue();
        assertThat(lifecycle.isDueAt(
                EffectiveTime.at(Instant.parse("2026-08-16T01:00:00Z")))).isTrue();
        assertThat(lifecycle.isDueAt(
                EffectiveTime.at(Instant.parse("2026-08-16T01:00:00.001Z")))).isTrue();
    }

    @Test
    void renewal_preserves_identity_and_first_confirmation() {
        RecordLifecycle original = RecordLifecycle.start(
                new LifecycleId(7), FIRST, POLICY.decide(FIRST));
        EffectiveTime renewedAt = EffectiveTime.at(Instant.parse("2026-08-16T00:30:00Z"));

        RecordLifecycle renewed = original.renew(renewedAt, POLICY.decide(renewedAt));

        assertThat(renewed.id()).isEqualTo(original.id());
        assertThat(renewed.firstConfirmedAt()).isEqualTo(FIRST);
        assertThat(renewed.lastConfirmedAt()).isEqualTo(renewedAt);
        assertThat(renewed.deadline().validUntil())
                .isEqualTo(Instant.parse("2026-08-16T01:30:00Z"));
    }

    @Test
    void due_lifecycle_must_be_replaced_instead_of_renewed() {
        RecordLifecycle lifecycle = RecordLifecycle.start(
                new LifecycleId(1), FIRST, POLICY.decide(FIRST));
        EffectiveTime deadline = EffectiveTime.at(lifecycle.deadline().validUntil());

        assertThatThrownBy(() -> lifecycle.renew(deadline, POLICY.decide(deadline)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("due lifecycle");
    }

    @Test
    void rejects_confirmation_time_rollback_and_invalid_deadline() {
        EffectiveTime latest = EffectiveTime.at(Instant.parse("2026-08-16T00:30:00Z"));
        RecordLifecycle lifecycle = new RecordLifecycle(
                new LifecycleId(1), FIRST, latest,
                new LifecycleDeadline(Instant.parse("2026-08-16T01:30:00Z")));
        EffectiveTime earlier = EffectiveTime.at(Instant.parse("2026-08-16T00:20:00Z"));

        assertThatThrownBy(() -> lifecycle.renew(earlier, POLICY.decide(earlier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");

        assertThatThrownBy(() -> new RecordLifecycle(
                new LifecycleId(2), FIRST, latest,
                new LifecycleDeadline(latest.value())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after last confirmation");
    }
}
