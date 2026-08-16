package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleArtifactStatistics;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockSnapshot;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockStatus;
import com.iocextractor.application.artifact.lifecycle.LifecycleControlState;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleState;
import com.iocextractor.application.artifact.lifecycle.LifecycleStatusSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-08-16T02:00:00Z");

    @Test
    void reportsAggregateHealthyStateWithoutIocOrSourceIdentifiers() {
        CanonicalDataAdmissionState admission = admitted();
        var indicator = new LifecycleHealthIndicator(
                () -> status(LifecycleClockStatus.SAFE, 0, Duration.ZERO,
                        LifecycleReconcileCycleState.COMPLETED),
                admission,
                Duration.ofSeconds(5));

        assertThat(indicator.health()).satisfies(health -> {
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("dueRecords", 0L)
                    .containsEntry("historyRecords", 7L)
                    .containsEntry("pendingProjections", 0L);
            assertThat(health.getDetails().toString())
                    .doesNotContain("source")
                    .doesNotContain("rowKey")
                    .doesNotContain("1.2.3.4");
            assertThat(health.getDetails().get("artifacts"))
                    .isEqualTo(Map.of("masks", Map.of(
                            "stored", 12L, "due", 0L, "history", 7L)));
        });
    }

    @Test
    void reportsDegradedForSafeLogicalFilteringWithRecoverableLag() {
        var indicator = new LifecycleHealthIndicator(
                () -> status(LifecycleClockStatus.CLAMPED, 1, Duration.ofSeconds(2),
                        LifecycleReconcileCycleState.COMPLETED),
                admitted(),
                Duration.ofSeconds(5));

        assertThat(indicator.health().getStatus()).isEqualTo(new Status("DEGRADED"));
    }

    @Test
    void reportsDownForUnsafeClockOrAdmissionFailure() {
        var unsafe = new LifecycleHealthIndicator(
                () -> status(LifecycleClockStatus.UNSAFE, 0, Duration.ZERO,
                        LifecycleReconcileCycleState.NEVER_RUN),
                admitted(),
                Duration.ofSeconds(5));
        assertThat(unsafe.health().getStatus()).isEqualTo(Status.DOWN);

        CanonicalDataAdmissionState failed = new CanonicalDataAdmissionState();
        failed.failed(new IllegalStateException("sensitive detail"));
        var failedIndicator = new LifecycleHealthIndicator(
                () -> status(LifecycleClockStatus.SAFE, 0, Duration.ZERO,
                        LifecycleReconcileCycleState.NEVER_RUN),
                failed,
                Duration.ofSeconds(5));
        assertThat(failedIndicator.health()).satisfies(health -> {
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("admissionFailure", "IllegalStateException");
            assertThat(health.getDetails().values()).doesNotContain("sensitive detail");
        });
    }

    private CanonicalDataAdmissionState admitted() {
        CanonicalDataAdmissionState admission = new CanonicalDataAdmissionState();
        admission.admitted(new LifecycleAdmissionResult(
                LifecycleActivationState.ACTIVE, EffectiveTime.at(NOW), 0, 0));
        return admission;
    }

    private LifecycleStatusSnapshot status(LifecycleClockStatus clockStatus,
                                           long pendingProjections,
                                           Duration backlogAge,
                                           LifecycleReconcileCycleState cycleState) {
        EffectiveTime effective = EffectiveTime.at(NOW);
        LifecycleClockSnapshot clock = new LifecycleClockSnapshot(
                clockStatus, NOW, effective, Optional.of(effective),
                clockStatus == LifecycleClockStatus.SAFE ? Duration.ZERO : Duration.ofSeconds(1),
                clockStatus == LifecycleClockStatus.CLAMPED ? Duration.ofSeconds(2) : Duration.ZERO);
        LifecycleControlState control = LifecycleControlState.disabledCompatible()
                .beginActivation("fixed-12h-v1")
                .completeActivation(effective);
        return new LifecycleStatusSnapshot(
                control,
                clock,
                List.of(new LifecycleArtifactStatistics("masks", 12, 0, 7)),
                Optional.of(NOW.plusSeconds(60)),
                backlogAge.isZero() ? Optional.empty() : Optional.of(NOW.minus(backlogAge)),
                pendingProjections,
                cycleState,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                backlogAge);
    }
}
