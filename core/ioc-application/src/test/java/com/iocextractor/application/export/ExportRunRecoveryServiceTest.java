package com.iocextractor.application.export;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.iocextractor.application.export.ExportFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportRunRecoveryServiceTest {

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void startedPartialIsDiscardedAndFailed() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.PARTIAL);

        assertThat(fixture.service.recoverIncomplete()).isEqualTo(1);

        assertThat(fixture.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(fixture.writer.discards).isEqualTo(1);
        assertThat(fixture.diagnostics).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.RECOVERY_FAILED);
    }

    @Test
    void startedMissingIsDiscardedAndFailed() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.MISSING);

        fixture.service.recoverIncomplete();

        assertThat(fixture.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(fixture.writer.discards).isEqualTo(1);
    }

    @Test
    void startedManifestWithoutMarkerIsRecoveredForwardWithoutSnapshotRead() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.RECOVERABLE);

        fixture.service.recoverIncomplete();

        assertThat(fixture.writer.recoveries).isEqualTo(1);
        assertThat(fixture.writer.publications).isEqualTo(1);
        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(fixture.ledger.progress).singleElement()
                .extracting(ExportProgress::lastRevision)
                .isEqualTo(1L);
    }

    @Test
    void markerWrittenBeforeLedgerUpdateAdvancesStartedToCompletion() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.STAGED);

        fixture.service.recoverIncomplete();

        assertThat(fixture.writer.recoveries).isZero();
        assertThat(fixture.writer.publications).isEqualTo(1);
        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
    }

    @Test
    void finalRenameObservedBeforeStartedCheckpointRecoversForward() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.AVAILABLE);

        fixture.service.recoverIncomplete();

        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(fixture.writer.publications).isEqualTo(1);
    }

    @Test
    void completedStagingAfterCrashRepeatsPostHashAndSkipsIdenticalCandidate() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.STAGED);
        fixture.ledger.progress = List.of(ExportFixtures.progress(
                0, ExportFixtures.CONTENT, "slice-old", ExportFixtures.plan().planHash()));

        fixture.service.recoverIncomplete();

        assertThat(fixture.status()).isEqualTo(ExportRunStatus.SKIPPED);
        assertThat(fixture.writer.discards).isEqualTo(1);
        assertThat(fixture.writer.publications).isZero();
        assertThat(fixture.ledger.progress).singleElement().satisfies(progress -> {
            assertThat(progress.lastRevision()).isEqualTo(1);
            assertThat(progress.lastSliceId()).isEqualTo("slice-old");
        });
    }

    @Test
    void stagedCheckpointPublishesExistingStaging() {
        Fixture fixture = fixture(ExportRunStatus.STAGED, SliceInspectionState.STAGED);

        fixture.service.recoverIncomplete();

        assertThat(fixture.writer.publications).isEqualTo(1);
        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
    }

    @Test
    void renameBeforeAvailableLedgerUpdateIsDetectedFromFinalSlice() {
        Fixture fixture = fixture(ExportRunStatus.STAGED, SliceInspectionState.AVAILABLE);

        fixture.service.recoverIncomplete();

        assertThat(fixture.writer.publications).isEqualTo(1);
        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
    }

    @Test
    void availableBeforeProgressIsFinishedFromManifestCoverage() {
        Fixture fixture = fixture(ExportRunStatus.AVAILABLE, SliceInspectionState.AVAILABLE);

        fixture.service.recoverIncomplete();

        assertThat(fixture.writer.publications).isZero();
        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(fixture.ledger.progress).singleElement()
                .extracting(ExportProgress::lastSliceId)
                .isEqualTo("run-recovery");
    }

    @Test
    void corruptOrConflictingEvidenceIsNeverOverwritten() {
        Fixture corrupt = fixture(ExportRunStatus.STAGED, SliceInspectionState.CORRUPT);
        Fixture conflict = fixture(ExportRunStatus.STARTED, SliceInspectionState.CONFLICT);

        corrupt.service.recoverIncomplete();
        conflict.service.recoverIncomplete();

        assertThat(corrupt.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(conflict.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(corrupt.writer.discards).isZero();
        assertThat(conflict.writer.discards).isZero();
        assertThat(corrupt.writer.publications).isZero();
        assertThat(conflict.writer.publications).isZero();
    }

    @Test
    void unexpectedAdapterFailureIsReportedWithoutReclassifyingDurableState() {
        Fixture fixture = fixture(ExportRunStatus.STARTED, SliceInspectionState.STAGED);
        fixture.writer.inspectFailure = new IllegalStateException("inspection unavailable");

        assertThatThrownBy(fixture.service::recoverIncomplete)
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.RECOVERY_FAILED.id());

        assertThat(fixture.status()).isEqualTo(ExportRunStatus.STARTED);
        assertThat(fixture.diagnostics).singleElement()
                .extracting(Diagnostic::code)
                .isEqualTo(ExportDiagnosticCodes.RECOVERY_FAILED);
    }

    private Fixture fixture(ExportRunStatus status, SliceInspectionState filesystemState) {
        var ledger = new ExportFixtures.FakeLedger();
        ledger.seed(ExportFixtures.run("run-recovery", status));
        var writer = new ExportFixtures.FakeWriter();
        writer.state = filesystemState;
        var observer = new ExportFixtures.RecordingObserver();
        var diagnostics = new ArrayList<Diagnostic>();
        var service = new ExportRunRecoveryService(
                ledger, writer, profile -> ledger.progress,
                new ExportChangeDetector(), observer, diagnostics::add,
                new DiagnosticFactory(clock), clock);
        return new Fixture(ledger, writer, diagnostics, service);
    }

    private record Fixture(ExportFixtures.FakeLedger ledger,
                           ExportFixtures.FakeWriter writer,
                           ArrayList<Diagnostic> diagnostics,
                           ExportRunRecoveryService service) {
        ExportRunStatus status() {
            return ledger.runs.get("run-recovery").status();
        }
    }
}
