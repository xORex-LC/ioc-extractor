package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportDetectionCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportDetectionService;
import com.iocextractor.application.dataframeimport.DataframeImportDrainCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportRecoveryCoordinator;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedDataframeImportRuntimeTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void startFailureRemainsPrimaryWhenLaneShutdownAlsoFails() {
        FailingShutdownExecutor lanes = new FailingShutdownExecutor();
        DataframeImportDetectionService detector = new DataframeImportDetectionService(
                new EmptySourceLifecycle(),
                command -> {
                    throw new AssertionError("empty source catalog must not admit candidates");
                },
                CLOCK,
                () -> new ImportDeliveryId("unused"));
        DataframeImportDetectionCoordinator detection =
                new DataframeImportDetectionCoordinator(List.of(), detector, lanes);
        DataframeImportDrainCoordinator drain = new DataframeImportDrainCoordinator(
                () -> new ProcessNextDataframeImportResult(false, Optional.empty()), lanes, 1);
        DataframeImportRecoveryCoordinator recoveryCoordinator =
                new DataframeImportRecoveryCoordinator(
                        limit -> new RecoverDataframeImportsResult(0, 0, 0), lanes, 1);
        DataframeImportRuntimeState state = new DataframeImportRuntimeState();
        ImportChangeSignalSource failingSignal = new ImportChangeSignalSource() {
            @Override
            public void start(Consumer<ImportSourceId> signalConsumer) {
                throw new IllegalStateException("change signal start failed");
            }

            @Override
            public void close() {
                // No partially acquired resource in this fixture.
            }
        };
        ManagedDataframeImportRuntime runtime = new ManagedDataframeImportRuntime(
                limit -> new RecoverDataframeImportsResult(0, 0, 0),
                detection,
                drain,
                recoveryCoordinator,
                limit -> 0,
                List.of(failingSignal),
                lanes,
                state,
                NoopDiagnosticSink.INSTANCE,
                CLOCK,
                1,
                1,
                Duration.ofDays(1),
                Duration.ofDays(1),
                Duration.ofMillis(10));

        assertThatThrownBy(runtime::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("change signal start failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("lane shutdown failed"));

        assertThat(state.snapshot().phase()).isEqualTo(DataframeImportRuntimeState.Phase.FAILED);
    }

    private static final class EmptySourceLifecycle implements ManagedImportSourceLifecycle {
        @Override
        public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
            return List.of();
        }

        @Override
        public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disposition(DispositionImportSourceCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void purgeSnapshot(ImportDeliveryId deliveryId, ImportSourceId sourceId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FailingShutdownExecutor implements KeyedSerialExecutor {
        @Override
        public WorkAdmission submit(WorkKey key, Runnable work) {
            return WorkAdmission.accepted(key, 0);
        }

        @Override
        public void shutdown() {
            throw new IllegalStateException("lane shutdown failed");
        }

        @Override
        public boolean awaitTermination(Duration timeout) {
            return true;
        }

        @Override
        public void close() {
            // Runtime owns shutdown explicitly.
        }
    }
}
