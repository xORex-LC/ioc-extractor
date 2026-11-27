package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.DocumentArtifactCommit;
import com.iocextractor.application.artifact.DocumentRun;
import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.DocumentWriteIdentity;
import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptArtifact;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionState;
import com.iocextractor.application.ingest.DocumentPromotionCoordinator;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import com.iocextractor.application.ingest.IngestionLifecycleSupport;
import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourcePreparers;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.in.PreparedExtraction;
import com.iocextractor.application.port.in.artifact.lifecycle.ReplayConfirmationReceiptUseCase;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.CanonicalDocumentCommits;
import com.iocextractor.application.port.out.artifact.DocumentRunLedger;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalObservationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.DocumentReceiptPlans;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.port.out.ingest.SourcePreparerFactory;
import com.iocextractor.application.service.IocExtractionService;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.diagnostics.sink.NoopDiagnosticSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentPromotionCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final ObservationId ID = new ObservationId("observed-document");
    private static final SourceKey KEY = new SourceKey("content-key");
    private static final Path INPUT = Path.of("inbox/document");
    private static final Path CLAIM = Path.of("processing/document");
    private static final Path DONE = Path.of("done/document");
    private static final SourceUnit UNIT = new SourceUnit(ID, KEY, INPUT, CLAIM, NOW);
    private static final RegisteredObservation REGISTRATION =
            new RegisteredObservation(ID, "namespace", new ObservationOrder(7), ObservationOrigin.DOCUMENT);
    private static final IngestSourceCommand COMMAND = new IngestSourceCommand(UNIT, REGISTRATION);
    private static final ProjectionGeneration GEN = new ProjectionGeneration(1);
    private static final DocumentArtifactCommit COMMIT = new DocumentArtifactCommit(1, 0, 0, 1, GEN);
    private static final DocumentRun.Plan WORKSPACE = new DocumentRun.Plan(
            DocumentRun.Plan.Kind.WORKSPACE, "a".repeat(64), List.of("masks"));

    @Test
    void rejectsOnlyWhenNoAcceptedPlanCanHaveWritten() {
        var fixture = new Fixture(false);
        fixture.coordinator.rejectedBeforePromotion(ID);
        verifyNoInteractions(fixture.extraction, fixture.receipts);
        fixture.stored("run", IngestRunStatus.STARTED, WORKSPACE);
        assertThatThrownBy(() -> fixture.coordinator.rejectedBeforePromotion(ID))
                .hasMessageContaining("requires canonical recovery");
        verify(fixture.runs, never()).markFailed(any(), any());

        fixture.stored("run", IngestRunStatus.STARTED, null);
        fixture.coordinator.rejectedBeforePromotion(ID);
        verify(fixture.runs).markFailed("run", "DOCUMENT_PREPARATION_FAILED");
        verify(fixture.extraction).discardUnlinkedPlan(ID, "run");
        verify(fixture.receipts).release(ID);
    }

    @Test
    void holdsAnOlderClaimWithoutAPlanAndAnAlreadyFailedRun() {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.STARTED, null);
        assertThatThrownBy(() -> fixture.coordinator.prepare(COMMAND))
                .hasMessageContaining("Previous document claim has no pinned plan");

        when(fixture.sources.find(ID)).thenReturn(Optional.empty());
        fixture.stored("run", IngestRunStatus.FAILED, null);
        assertThatThrownBy(() -> fixture.coordinator.prepare(COMMAND))
                .hasMessageContaining("already finished");
        verifyNoInteractions(fixture.extraction, fixture.commits);
    }

    @Test
    void rejectsAChangedRegistrationAndDiscardsWorkForAReplacedRun() {
        var fixture = new Fixture(false);
        var other = new RegisteredObservation(ID, "namespace", new ObservationOrder(8), ObservationOrigin.DOCUMENT);
        assertThatThrownBy(() -> fixture.coordinator.prepare(new IngestSourceCommand(UNIT, other)))
                .hasMessageContaining("registration changed");

        fixture.stored("run", IngestRunStatus.STARTED, WORKSPACE);
        try (var attempt = fixture.coordinator.prepare(COMMAND)) {
            fixture.stored("another-run", IngestRunStatus.STARTED, WORKSPACE);
            assertThatThrownBy(attempt::promote).hasMessageContaining("run changed");
            assertThatThrownBy(attempt::promote).hasMessageContaining("already consumed");
        }
        try (var attempt = fixture.coordinator.prepare(COMMAND)) {
            fixture.stored("another-run", IngestRunStatus.STARTED, WORKSPACE,
                    new DocumentRun.Identity(REGISTRATION, KEY, "other policy", false));
            assertThatThrownBy(attempt::promote).hasMessageContaining("run changed");
        }
        verifyNoInteractions(fixture.commits, fixture.extraction);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "KEY", "ORIGINAL", "PROCESSING", "MISSING"})
    void holdsWhenSourceLedgerCannotProveTheClaim(String contradiction) {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.DB_COMMITTED, WORKSPACE);
        when(fixture.sources.find(ID)).thenReturn("MISSING".equals(contradiction) ? Optional.empty()
                : Optional.of(new IngestionRecord(ID,
                        "KEY".equals(contradiction) ? new SourceKey("another") : KEY,
                        "FAILED".equals(contradiction) ? IngestionStatus.FAILED : IngestionStatus.CLAIMED,
                        "ORIGINAL".equals(contradiction) ? Path.of("unrelated-inbox") : INPUT,
                        "PROCESSING".equals(contradiction) ? Path.of("unrelated-claim") : CLAIM,
                        null, NOW, NOW, null)));
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("Document source claim");
        verifyNoInteractions(fixture.commits, fixture.files);
    }

    @Test
    void refusesCanonicalWritesIfClaimUpdateConflicts() {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.STARTED, WORKSPACE);
        when(fixture.sources.markClaimed(UNIT)).thenReturn(IngestionLedgerTransition.CONFLICT);
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("ledger transition conflict");
        verifyNoInteractions(fixture.commits, fixture.files);
    }

    @Test
    void failsClosedForUnexpectedCanonicalArtifactOrIncompleteProofAfterWriterReturns() {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.STARTED, WORKSPACE);
        when(fixture.commits.find(any())).thenReturn(Map.of("unplanned", COMMIT));
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("unexpected artifact");
        verifyNoInteractions(fixture.extraction);

        var reopened = mock(PreparedExtraction.class);
        when(fixture.reader.resume(any(), any())).thenReturn(reopened);
        when(fixture.commits.find(any())).thenReturn(Map.of());
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("proof is incomplete");
        verify(reopened).close();
        verify(fixture.runs, never()).markDbCommitted(any());
        verifyNoInteractions(fixture.files);
    }

    @Test
    void refusesToWriteMissingEvidenceAfterTheServiceRunOrTheSourceAdvanced() {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.DB_COMMITTED, WORKSPACE);
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("advanced without the full canonical proof");

        fixture.stored("run", IngestRunStatus.STARTED, WORKSPACE);
        when(fixture.sources.find(ID)).thenReturn(Optional.of(archived(DONE)));
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("advanced without the full canonical proof");
        verifyNoInteractions(fixture.extraction, fixture.files);
    }

    @Test
    void retainsTheClaimWhenProjectionFailsToAcknowledgeItsGeneration() {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.STARTED, WORKSPACE);
        when(fixture.commits.find(any())).thenReturn(Map.of("masks", COMMIT));
        when(fixture.projectionWork.load("masks"))
                .thenReturn(new ArtifactProjectionState("masks", GEN, new ProjectionGeneration(0)));
        when(fixture.projection.project(any())).thenReturn(ArtifactProjectionResult.clean(1, GEN));
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("insufficient generation coverage");
        verify(fixture.runs).markDbCommitted("run");
        verify(fixture.runs, never()).markProjectionCompleted(any());
        verifyNoInteractions(fixture.files);
    }

    @Test
    void rejectsAnArchivedPathOutsideThePersistedClaim() {
        var fixture = new Fixture(false);
        fixture.stored("run", IngestRunStatus.COMPLETED, WORKSPACE);
        when(fixture.sources.find(ID)).thenReturn(Optional.of(archived(Path.of("done/other"))));
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("archived path differs");
        verifyNoInteractions(fixture.commits, fixture.extraction, fixture.observations);
    }

    @Test
    void anAcceptedEmptyPlanFinishesWithoutWritingOrOpeningWorkspaceRows() {
        var fixture = new Fixture(true);
        var emptyPlan = new DocumentRun.Plan(DocumentRun.Plan.Kind.WORKSPACE, WORKSPACE.token(), List.of());
        var claim = fixture.sources.find(ID);
        when(fixture.sources.find(ID)).thenReturn(Optional.empty()).thenReturn(Optional.empty()).thenReturn(claim);
        var sealed = mock(PreparedExtraction.class);
        when(fixture.reader.prepare(any())).thenReturn(sealed);
        when(sealed.seal()).thenReturn(new DocumentPreparationSeal(WORKSPACE.token(), List.of()));
        var accepted = fixture.documentRun("run", IngestRunStatus.STARTED, emptyPlan, fixture.identity);
        fixture.stored("run", IngestRunStatus.STARTED, null);
        when(fixture.runs.pinPlan(any(), eq(emptyPlan))).thenReturn(accepted);
        assertThat(fixture.promote().status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        verify(fixture.runs).markCompleted("run");
        verify(fixture.sources).markSourceArchived(ID, DONE);
        verify(fixture.observations).markTerminal(ID, EffectiveTime.at(NOW), RETENTION);
        verify(fixture.extraction).discardPlan(eq(ID), any());
        verify(fixture.reader).prepare(argThat(command -> command.lifecycleWriteContext() == null));
        verify(fixture.reader, never()).resume(any(), any());
        verify(sealed, never()).promote(any(), any());
        verifyNoInteractions(fixture.writer, fixture.projection);
    }

    @Test
    void absentOrDifferentPinnedReceiptDoesNotCauseAnotherSelectionOrCanonicalMutation() {
        var fixture = new Fixture(true);
        var receiptPlan = new DocumentRun.Plan(DocumentRun.Plan.Kind.RECEIPT, "receipt:pinned", List.of("masks"));
        fixture.stored("run", IngestRunStatus.STARTED, receiptPlan);
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("Pinned document receipt is missing");

        var different = new ConfirmationReceiptSnapshot(new ConfirmationReceiptId("receipt:different"), KEY.value(),
                "policy", List.of(new ConfirmationReceiptArtifact("masks", List.of("mask"), List.of())));
        when(fixture.receipts.pinned(ID, KEY.value(), "policy")).thenReturn(Optional.of(different));
        assertThatThrownBy(() -> fixture.promote()).hasMessageContaining("does not match the run plan");
        verify(fixture.receipts, never()).pin(any(), any(), any(), any());
        verifyNoInteractions(fixture.writer, fixture.extraction, fixture.files);
    }

    @Test
    void durablePlanAndRunRejectAmbiguousIdentities() {
        assertThatThrownBy(() -> new DocumentRun.Plan(WORKSPACE.kind(), "", List.of("masks")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentRun.Plan(WORKSPACE.kind(), "token", List.of("masks", "masks")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentRun.Plan(WORKSPACE.kind(), "token", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentRun.Plan(WORKSPACE.kind(), "token", List.of("two\nlines")))
                .isInstanceOf(IllegalArgumentException.class);
        var identity = new DocumentRun.Identity(REGISTRATION, KEY, "policy", false);
        var otherSource = new IngestRun("run", "another", IngestRunStatus.STARTED, List.of("masks"), NOW, NOW, null);
        var anotherArtifact = new IngestRun("run", KEY.value(), IngestRunStatus.STARTED, List.of("ip"), NOW, NOW, null);
        assertThatThrownBy(() -> new DocumentRun(otherSource, identity, Optional.of(WORKSPACE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentRun(anotherArtifact, identity, Optional.of(WORKSPACE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentProofRequiresAValidSealAndRegistrationOrigin() {
        assertThatThrownBy(() -> new DocumentPreparationSeal("not-a-checksum", List.of("masks")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentPreparationSeal(WORKSPACE.token(), List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentPreparationSeal(WORKSPACE.token(), List.of("masks", "masks")))
                .isInstanceOf(IllegalArgumentException.class);

        var oneshot = new RegisteredObservation(ID, "namespace", new ObservationOrder(7), ObservationOrigin.ONESHOT);
        assertThatThrownBy(() -> new DocumentWriteIdentity(oneshot, KEY.value(), "plan", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentWriteIdentity(REGISTRATION, null, "plan", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentWriteIdentity(REGISTRATION, " ", "plan", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentWriteIdentity(REGISTRATION, KEY.value(), null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentWriteIdentity(REGISTRATION, KEY.value(), " ", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IngestionRecord archived(Path path) {
        return new IngestionRecord(ID, KEY, IngestionStatus.SOURCE_ARCHIVED, INPUT, CLAIM, path, NOW, NOW, null);
    }

    private static final class Fixture {
        final DocumentRunLedger runs = mock(DocumentRunLedger.class);
        final DocumentAdmissionService admissions = mock(DocumentAdmissionService.class);
        final IngestionLedger sources = mock(IngestionLedger.class);
        final SourceLifecycle files = mock(SourceLifecycle.class);
        final CanonicalDocumentCommits commits = mock(CanonicalDocumentCommits.class);
        final CanonicalArtifactWriter writer = mock(CanonicalArtifactWriter.class);
        final DocumentReceiptPlans receipts = mock(DocumentReceiptPlans.class);
        final CanonicalObservationStore observations = mock(CanonicalObservationStore.class);
        final ArtifactProjectionWorkStore projectionWork = mock(ArtifactProjectionWorkStore.class);
        final ArtifactProjection projection = mock(ArtifactProjection.class);
        final IocExtractionServiceFactory extraction = mock(IocExtractionServiceFactory.class);
        final IocExtractionService reader = mock(IocExtractionService.class);
        final DocumentPromotionCoordinator coordinator;
        final DocumentRun.Identity identity;

        Fixture(boolean fixed) {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var support = fixed ? new IngestionLifecycleSupport(mock(ReplayConfirmationReceiptUseCase.class),
                    observations, () -> EffectiveTime.at(NOW), "policy", RETENTION) : null;
            identity = new DocumentRun.Identity(REGISTRATION, KEY, "policy", fixed);
            SourcePreparerFactory preparers = unit -> new SourcePreparers(List.of());
            coordinator = new DocumentPromotionCoordinator(
                    new DocumentPromotionCoordinator.Journals(runs, admissions, sources, files),
                    new DocumentPromotionCoordinator.Canonical(commits, writer, receipts, observations,
                            () -> EffectiveTime.at(NOW), projectionWork, projection),
                    new DocumentPromotionCoordinator.Processing(preparers, extraction,
                            new SynchronousKeyedExecutionGuard(), "policy", "policy", support,
                            event -> { }, NoopDiagnosticSink.INSTANCE, clock), RETENTION);
            when(admissions.resume(ID)).thenReturn(REGISTRATION);
            when(sources.find(ID)).thenReturn(Optional.of(
                    new IngestionRecord(ID, KEY, IngestionStatus.CLAIMED, INPUT, CLAIM, null, NOW, NOW, null)));
            when(sources.markClaimed(UNIT)).thenReturn(IngestionLedgerTransition.ALREADY_APPLIED);
            when(sources.markSourceArchived(ID, DONE)).thenReturn(IngestionLedgerTransition.APPLIED);
            when(extraction.create(any(), any())).thenReturn(reader);
            var admission = mock(DocumentAdmission.class);
            var evidence = mock(DocumentCandidateEvidence.class);
            when(admission.claimedEvidence()).thenReturn(Optional.of(evidence));
            when(admissions.find(ID)).thenReturn(Optional.of(admission));
            when(files.archivePath(UNIT)).thenReturn(DONE);
            when(files.archive(UNIT, evidence)).thenReturn(DONE);
            doAnswer(call -> {
                call.getArgument(2, Runnable.class).run();
                return admission;
            }).when(admissions).complete(eq(ID), eq(DocumentTerminalOutcome.SUCCEEDED), any());
        }

        void stored(String runId, IngestRunStatus status, DocumentRun.Plan plan) {
            stored(runId, status, plan, identity);
        }

        void stored(String runId, IngestRunStatus status, DocumentRun.Plan plan, DocumentRun.Identity durableIdentity) {
            var run = documentRun(runId, status, plan, durableIdentity);
            when(runs.findDocument(ID)).thenReturn(Optional.of(run));
            when(runs.beginDocument(identity)).thenReturn(run);
        }

        DocumentRun documentRun(String runId, IngestRunStatus status, DocumentRun.Plan plan,
                                DocumentRun.Identity durableIdentity) {
            return new DocumentRun(new IngestRun(runId, KEY.value(), status,
                    plan == null ? List.of() : plan.artifacts(), NOW, NOW, null),
                    durableIdentity, Optional.ofNullable(plan));
        }

        IngestSourceResult promote() {
            try (var attempt = coordinator.prepare(COMMAND)) {
                return attempt.promote();
            }
        }
    }
}
