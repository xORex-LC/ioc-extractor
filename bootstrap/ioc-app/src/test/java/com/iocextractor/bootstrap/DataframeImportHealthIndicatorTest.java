package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryStatus;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorSnapshot;
import com.iocextractor.platform.concurrent.KeyedWorkSnapshot;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DataframeImportHealthIndicatorTest {

    @Test
    void exposesOnlyAggregateValueFreeRuntimeFacts() {
        DataframeImportRuntimeState state = new DataframeImportRuntimeState();
        state.recovering(Instant.parse("2026-08-24T12:00:00Z"));
        state.running(Instant.parse("2026-08-24T12:00:01Z"));
        var status = new ImportDeliveryStatus(
                Map.of(ImportDeliveryState.FINALIZING, 2L),
                Optional.of(new ImportDeliverySequence(41)),
                Optional.of(ImportDeliveryState.FINALIZING),
                Optional.of(Duration.ofSeconds(9)), 0,
                Optional.empty(), Optional.empty(), true);
        var indicator = new DataframeImportHealthIndicator(
                () -> status, state, executorSnapshot(3, true));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsOnlyKeys(
                "phase", "recoveryComplete", "nonterminal", "queuedWork",
                "runningLanes", "headSequence", "headState", "headAgeSeconds");
        assertThat(health.getDetails())
                .containsEntry("nonterminal", 2L)
                .containsEntry("queuedWork", 3)
                .containsEntry("runningLanes", 1L)
                .containsEntry("headSequence", 41L)
                .containsEntry("headState", "FINALIZING")
                .containsEntry("headAgeSeconds", 9L);
        assertThat(health.getDetails().toString())
                .doesNotContain("filename", "path", "digest", "candidate", "contract");
    }

    @Test
    void consistencyFailureIsDownAndPublishesOnlyStableCode() {
        DataframeImportRuntimeState state = new DataframeImportRuntimeState();
        state.recovering(Instant.parse("2026-08-24T12:00:00Z"));
        state.failed(Instant.parse("2026-08-24T12:00:01Z"), "IMPORT.CONSISTENCY_FAILED");
        var indicator = new DataframeImportHealthIndicator(
                () -> new ImportDeliveryStatus(
                        Map.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                        0, Optional.empty(), Optional.empty(), false),
                state, executorSnapshot(0, false));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("phase", "FAILED")
                .containsEntry("recoveryComplete", false)
                .containsEntry("code", "IMPORT.CONSISTENCY_FAILED");
    }

    @Test
    void retryingHeadIsDegradedAndExposesOnlyBoundedRetryFacts() {
        DataframeImportRuntimeState state = new DataframeImportRuntimeState();
        state.recovering(Instant.parse("2026-08-24T12:00:00Z"));
        state.running(Instant.parse("2026-08-24T12:00:01Z"));
        var status = new ImportDeliveryStatus(
                Map.of(ImportDeliveryState.STAGING, 1L),
                Optional.of(new ImportDeliverySequence(7)),
                Optional.of(ImportDeliveryState.STAGING),
                Optional.of(Duration.ofMinutes(2)), 2,
                Optional.of(Duration.ofSeconds(4)),
                Optional.of("IMPORT.PROCESSING_FAILED"), true);

        var health = new DataframeImportHealthIndicator(
                () -> status, state, executorSnapshot(0, false)).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails())
                .containsEntry("headRetryCount", 2)
                .containsEntry("headRetryDelaySeconds", 4L)
                .containsEntry("code", "IMPORT.PROCESSING_FAILED");
    }

    private KeyedSerialExecutor executorSnapshot(int queued, boolean running) {
        KeyedSerialExecutorSnapshot snapshot = new KeyedSerialExecutorSnapshot(List.of(
                new KeyedWorkSnapshot(new WorkKey("import-global"), queued, running, Duration.ZERO)));
        return new KeyedSerialExecutor() {
            @Override
            public WorkAdmission submit(WorkKey key, Runnable work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public KeyedSerialExecutorSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public void shutdown() {
            }

            @Override
            public boolean awaitTermination(Duration timeout) {
                return true;
            }

            @Override
            public void close() {
            }
        };
    }
}
