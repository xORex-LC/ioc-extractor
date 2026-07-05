package com.iocextractor.application.export;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.RecordingControlEventPublisher;
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
        assertThat(fixture.events.events()).isEmpty();
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
        assertThat(fixture.events.events()).isEmpty();
    }

    @Test
    void completedRecoveryPathsEmitSliceCompleted() {
        List.of(
                new RecoveryCase(ExportRunStatus.STARTED, SliceInspectionState.RECOVERABLE),
                new RecoveryCase(ExportRunStatus.STARTED, SliceInspectionState.STAGED),
                new RecoveryCase(ExportRunStatus.STARTED, SliceInspectionState.AVAILABLE),
                new RecoveryCase(ExportRunStatus.STAGED, SliceInspectionState.STAGED),
                new RecoveryCase(ExportRunStatus.STAGED, SliceInspectionState.AVAILABLE),
                new RecoveryCase(ExportRunStatus.AVAILABLE, SliceInspectionState.AVAILABLE))
                .forEach(this::assertCompletedEvent);
    }

    @Test
    void failedRecoveryPathsDoNotEmitSliceCompleted() {
        List.of(
                new RecoveryCase(ExportRunStatus.STARTED, SliceInspectionState.PARTIAL),
                new RecoveryCase(ExportRunStatus.STARTED, SliceInspectionState.MISSING),
                new RecoveryCase(ExportRunStatus.STARTED, SliceInspectionState.CONFLICT),
                new RecoveryCase(ExportRunStatus.STAGED, SliceInspectionState.CORRUPT))
                .forEach(testCase -> {
                    Fixture fixture = fixture(testCase.status(), testCase.filesystemState());

                    fixture.service.recoverIncomplete();

                    assertThat(fixture.status()).isEqualTo(ExportRunStatus.FAILED);
                    assertThat(fixture.events.events()).isEmpty();
                });
    }

    @Test
    void emptyRecoveryDoesNotEmitSliceCompleted() {
        Fixture fixture = emptyFixture();

        assertThat(fixture.service.recoverIncomplete()).isZero();

        assertThat(fixture.events.events()).isEmpty();
    }

    @Test
    void publisherFailureDoesNotFailRecoveryOrReclassifyCompletedRun() {
        Fixture fixture = fixture(ExportRunStatus.AVAILABLE, SliceInspectionState.AVAILABLE, event -> {
            throw new IllegalStateException("event bus unavailable");
        });

        assertThat(fixture.service.recoverIncomplete()).isEqualTo(1);

        assertThat(fixture.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(fixture.diagnostics).isEmpty();
    }

    private void assertCompletedEvent(RecoveryCase testCase) {
        Fixture fixture = fixture(testCase.status(), testCase.filesystemState());

        fixture.service.recoverIncomplete();

        ExportRun terminal = fixture.ledger.runs.get("run-recovery");
        assertThat(terminal.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(fixture.events.events()).singleElement()
                .isInstanceOfSatisfying(SliceCompleted.class, event -> {
                    assertThat(event.profile()).isEqualTo(terminal.profile());
                    assertThat(event.sliceId()).isEqualTo(terminal.runId());
                    assertThat(event.sliceName()).isEqualTo(terminal.sliceName());
                    assertThat(event.manifestSha256()).isEqualTo(terminal.manifestSha256());
                    assertThat(event.metadata().eventId()).isEqualTo("slice-completed:" + terminal.runId());
                    assertThat(event.metadata().occurredAt()).isEqualTo(terminal.updatedAt());
                    assertThat(event.metadata().correlationId()).isEqualTo(terminal.runId());
                    assertThat(event.metadata().eventType()).isEqualTo(SliceCompleted.EVENT_TYPE);
                    assertThat(event.metadata().eventVersion()).isEqualTo(SliceCompleted.EVENT_VERSION);
                });
    }

    private Fixture fixture(ExportRunStatus status, SliceInspectionState filesystemState) {
        return fixture(status, filesystemState, new RecordingControlEventPublisher());
    }

    private Fixture fixture(ExportRunStatus status,
                            SliceInspectionState filesystemState,
                            ControlEventPublisher eventPublisher) {
        var ledger = new ExportFixtures.FakeLedger();
        ledger.seed(ExportFixtures.run("run-recovery", status));
        var writer = new ExportFixtures.FakeWriter();
        writer.state = filesystemState;
        var observer = new ExportFixtures.RecordingObserver();
        var diagnostics = new ArrayList<Diagnostic>();
        var events = eventPublisher instanceof RecordingControlEventPublisher recording
                ? recording : new RecordingControlEventPublisher();
        var service = new ExportRunRecoveryService(
                ledger, writer, profile -> ledger.progress,
                new ExportChangeDetector(), observer, diagnostics::add,
                new DiagnosticFactory(clock), clock, eventPublisher);
        return new Fixture(ledger, writer, diagnostics, events, service);
    }

    private Fixture emptyFixture() {
        var ledger = new ExportFixtures.FakeLedger();
        var writer = new ExportFixtures.FakeWriter();
        var observer = new ExportFixtures.RecordingObserver();
        var diagnostics = new ArrayList<Diagnostic>();
        var events = new RecordingControlEventPublisher();
        var service = new ExportRunRecoveryService(
                ledger, writer, profile -> ledger.progress,
                new ExportChangeDetector(), observer, diagnostics::add,
                new DiagnosticFactory(clock), clock, events);
        return new Fixture(ledger, writer, diagnostics, events, service);
    }

    private record Fixture(ExportFixtures.FakeLedger ledger,
                           ExportFixtures.FakeWriter writer,
                           ArrayList<Diagnostic> diagnostics,
                           RecordingControlEventPublisher events,
                           ExportRunRecoveryService service) {
        ExportRunStatus status() {
            return ledger.runs.get("run-recovery").status();
        }
    }

    private record RecoveryCase(ExportRunStatus status, SliceInspectionState filesystemState) {
    }
}
