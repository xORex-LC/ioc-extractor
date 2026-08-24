package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportConsistencyException;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class DataframeImportLaneObserverTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);
    private static final WorkKey KEY = new WorkKey("import-global");

    @Test
    void rejectedLatencyHintIsRecoverableCoalescingPressure() {
        DataframeImportRuntimeState state = runningState();
        List<Diagnostic> diagnostics = new ArrayList<>();
        DataframeImportLaneObserver observer = new DataframeImportLaneObserver(
                state, diagnostics::add, CLOCK);

        observer.rejected(WorkAdmission.rejected(KEY, 1));

        assertThat(state.snapshot().phase()).isEqualTo(DataframeImportRuntimeState.Phase.RUNNING);
        assertThat(state.snapshot().code()).isNull();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void workerDispatchFailureDegradesWithoutExposingExecutorFailure() {
        DataframeImportRuntimeState state = runningState();
        List<Diagnostic> diagnostics = new ArrayList<>();
        DataframeImportLaneObserver observer = new DataframeImportLaneObserver(
                state, diagnostics::add, CLOCK);

        observer.dispatchRejected(KEY, 2, new RejectedExecutionException("private detail"));

        assertThat(state.snapshot().phase()).isEqualTo(DataframeImportRuntimeState.Phase.DEGRADED);
        assertThat(state.snapshot().code()).isEqualTo("IMPORT.PROCESSING_FAILED");
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().id()).isEqualTo("IMPORT.PROCESSING_FAILED");
            assertThat(diagnostic.context().toString()).doesNotContain("private detail");
        });
    }

    @Test
    void durableContradictionMakesRuntimeFailClosed() {
        DataframeImportRuntimeState state = runningState();
        List<Diagnostic> diagnostics = new ArrayList<>();
        DataframeImportLaneObserver observer = new DataframeImportLaneObserver(
                state, diagnostics::add, CLOCK);

        observer.failed(KEY, new DataframeImportConsistencyException("unsafe private detail"));

        assertThat(state.snapshot().phase()).isEqualTo(DataframeImportRuntimeState.Phase.FAILED);
        assertThat(state.snapshot().code()).isEqualTo("IMPORT.CONSISTENCY_FAILED");
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().id()).isEqualTo("IMPORT.CONSISTENCY_FAILED");
            assertThat(diagnostic.context().toString()).doesNotContain("unsafe private detail");
        });
    }

    private DataframeImportRuntimeState runningState() {
        DataframeImportRuntimeState state = new DataframeImportRuntimeState();
        state.recovering(CLOCK.instant());
        state.running(CLOCK.instant());
        return state;
    }
}
