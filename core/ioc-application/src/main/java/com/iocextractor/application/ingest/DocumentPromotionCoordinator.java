package com.iocextractor.application.ingest;

import com.iocextractor.application.artifact.CanonicalArtifactsChanged;
import com.iocextractor.application.artifact.DocumentArtifactCommit;
import com.iocextractor.application.artifact.NoopArtifactProjection;
import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.DocumentRun;
import com.iocextractor.application.artifact.DocumentRun.Plan;
import com.iocextractor.application.artifact.DocumentWriteIdentity;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptSnapshot;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.PreparedExtraction;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.PrepareIngestionUseCase;
import com.iocextractor.application.port.in.ingest.PreparedIngestion;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
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
import com.iocextractor.platform.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.concurrent.KeyedExecutionGuard;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Advances ranked daemon documents using dataframe commit proof and a service-side pinned plan. */
public final class DocumentPromotionCoordinator implements PrepareIngestionUseCase {

    private final Journals journals;
    private final Canonical canonical;
    private final Processing processing;
    private final Duration retention;

    public DocumentPromotionCoordinator(Journals journals, Canonical canonical,
                                        Processing processing, Duration retention) {
        this.journals = journals;
        this.canonical = canonical;
        this.processing = processing;
        this.retention = retention;
    }

    @Override
    public boolean hasPromotionPlan(ObservationId id) {
        return journals.runs().findDocument(id).flatMap(DocumentRun::plan).isPresent();
    }

    @Override
    public void rejectedBeforePromotion(ObservationId id) {
        var current = journals.runs().findDocument(id);
        if (current.isEmpty()) {
            return;
        }
        var run = current.orElseThrow();
        if (run.plan().isPresent()) {
            throw new IllegalStateException("Pinned document plan requires canonical recovery");
        }
        journals.runs().markFailed(run.run().runId(), "DOCUMENT_PREPARATION_FAILED");
        processing.extraction().discardUnlinkedPlan(id, run.run().runId());
        canonical.receipts().release(id);
    }

    @Override
    public PreparedIngestion prepare(IngestSourceCommand command) {
        var unit = command.claimedSourceOptional().orElseThrow();
        var registration = journals.admissions().resume(unit.observationId());
        if (!registration.equals(command.registration())) {
            throw new IllegalStateException("Document registration changed");
        }
        var identity = new DocumentRun.Identity(registration, unit.key(), processing.recoveryPolicy(),
                processing.lifecycle() != null);
        if (!hasPromotionPlan(unit.observationId()) && journals.sources().find(unit.observationId()).isPresent()) {
            throw new IllegalStateException("Previous document claim has no pinned plan");
        }
        var run = journals.runs().beginDocument(identity);
        if (run.plan().isPresent()) {
            return new Attempt(command, run, null, null);
        }
        if (run.run().status() != IngestRunStatus.STARTED) {
            throw new IllegalStateException("Document run already finished without promotion");
        }
        var preparers = processing.preparers().createFor(unit);
        var receipt = pinReceipt(unit, identity);
        var extraction = receipt.isPresent() ? null : extraction(preparers)
                .prepare(extractionCommand(command, run, preparers.artifactNames().size()));
        return new Attempt(command, run, extraction, receipt.map(DocumentPromotionCoordinator::receiptPlan).orElse(null));
    }

    private Optional<ConfirmationReceiptSnapshot> pinReceipt(SourceUnit unit, DocumentRun.Identity identity) {
        return identity.lifecycleActive() ? canonical.receipts().pin(unit.observationId(), unit.key().value(),
                processing.policy(), canonical.time().now()) : Optional.empty();
    }

    private static Plan receiptPlan(ConfirmationReceiptSnapshot receipt) {
        return new Plan(Plan.Kind.RECEIPT, receipt.id().value(),
                receipt.artifacts().stream().map(artifact -> artifact.artifactName()).toList());
    }

    private IocExtractionService extraction(SourcePreparers preparers) {
        return processing.extraction().create(preparers.preparers(), NoopArtifactProjection.INSTANCE);
    }

    private ExtractionCommand extractionCommand(IngestSourceCommand command, DocumentRun run, int artifacts) {
        var unit = command.claimedSource();
        return new ExtractionCommand(run.run().runId(), unit.processingPath(), false,
                lifecycleContext(unit, artifacts), command.registration());
    }

    private LifecycleWriteContext lifecycleContext(SourceUnit unit, int artifacts) {
        return processing.lifecycle() == null || artifacts == 0 ? null : processing.lifecycle().context(unit, artifacts);
    }

    private final class Attempt implements PreparedIngestion {
        private final IngestSourceCommand command;
        private final DocumentRun run;
        private final PreparedExtraction prepared;
        private final Plan receipt;
        private boolean consumed;

        private Attempt(IngestSourceCommand command, DocumentRun run, PreparedExtraction prepared, Plan receipt) {
            this.command = command;
            this.run = run;
            this.prepared = prepared;
            this.receipt = receipt;
        }

        @Override
        public IngestSourceResult promote() {
            if (consumed) {
                throw new IllegalStateException("Document attempt is already consumed");
            }
            consumed = true;
            return processing.guard().execute(WorkKey.of(command.key().value()),
                    () -> recoverOrPromote(command, run, prepared, receipt));
        }

        @Override
        public void close() {
            consumed = true;
            if (prepared != null) {
                prepared.close();
            }
        }
    }

    private IngestSourceResult recoverOrPromote(IngestSourceCommand command, DocumentRun selected,
                                                 PreparedExtraction prepared, Plan receipt) {
        SourceUnit unit = command.claimedSource();
        ObservationId id = unit.observationId();
        DocumentRun run = journals.runs().findDocument(id).orElseThrow();
        if (!run.identity().equals(selected.identity()) || !run.run().runId().equals(selected.run().runId())) {
            throw new IllegalStateException("Document run changed before ordered promotion");
        }
        if (run.plan().isEmpty()) {
            run = journals.runs().pinPlan(run, receipt == null ? workspacePlan(prepared.seal()) : receipt);
        }
        Plan plan = run.plan().orElseThrow();
        IngestionRecord source = ensureSourceClaim(unit, run.run().status());
        ExtractionResult result = null;
        if (run.run().status() == IngestRunStatus.COMPLETED) {
            finishArchive(unit, source);
        } else {
            result = completePromotion(command, run, plan, source, prepared);
        }
        journals.admissions().complete(id, DocumentTerminalOutcome.SUCCEEDED, () -> {
            releasePlan(command, plan, prepared);
            canonical.observations().markTerminal(id, canonical.time().now(), retention);
        });
        return new IngestSourceResult(unit.key(), IngestionStatus.SOURCE_ARCHIVED, plan.kind() == Plan.Kind.RECEIPT, result);
    }

    private ExtractionResult completePromotion(IngestSourceCommand command, DocumentRun run, Plan plan,
                                               IngestionRecord source, PreparedExtraction prepared) {
        var unit = command.claimedSource();
        String runId = run.run().runId();
        ExtractionResult result = null;
        var identity = new DocumentWriteIdentity(run.identity().registration(), unit.key().value(),
                runId + ":" + plan.kind().name() + ":" + plan.token(), run.identity().lifecycleActive());
        var committed = proofs(identity, plan);
        Set<String> missing = missing(plan, committed);
        if (!missing.isEmpty()) {
            if (run.run().status() != IngestRunStatus.STARTED || source.status() != IngestionStatus.CLAIMED) {
                throw new IllegalStateException("Document advanced without the full canonical proof");
            }
            result = writeMissing(command, run, identity, plan, missing, prepared);
            committed = proofs(identity, plan);
            if (committed.size() != plan.artifacts().size()) {
                throw new IllegalStateException("Canonical document proof is incomplete");
            }
        }
        journals.runs().markDbCommitted(runId);
        if (run.run().status() != IngestRunStatus.PROJECTION_COMPLETED) {
            project(runId, plan, committed);
            journals.runs().markProjectionCompleted(runId);
        }
        finishArchive(unit, source);
        journals.runs().markCompleted(runId);
        publish(runId, committed.entrySet().stream()
                .filter(entry -> entry.getValue().publicRowsChanged() > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList());
        return result;
    }

    private static Plan workspacePlan(DocumentPreparationSeal seal) {
        return new Plan(Plan.Kind.WORKSPACE, seal.checksum(), seal.artifacts());
    }

    private Map<String, DocumentArtifactCommit> proofs(DocumentWriteIdentity identity, Plan plan) {
        var committed = canonical.commits().find(identity);
        if (!plan.artifacts().containsAll(committed.keySet())) {
            throw new IllegalStateException("Canonical commit belongs to an unexpected artifact");
        }
        return committed;
    }

    private static Set<String> missing(Plan plan, Map<String, DocumentArtifactCommit> committed) {
        var missing = new LinkedHashSet<>(plan.artifacts());
        missing.removeAll(committed.keySet());
        return missing;
    }

    private ExtractionResult writeMissing(IngestSourceCommand command, DocumentRun run, DocumentWriteIdentity identity,
                                          Plan plan, Set<String> missing, PreparedExtraction prepared) {
        if (plan.kind() == Plan.Kind.RECEIPT) {
            replayPinned(command, identity, plan, missing);
            return null;
        }
        if (prepared != null) {
            return prepared.promote(identity, missing);
        }
        var preparers = processing.preparers().createFor(command.claimedSource());
        try (var reopened = extraction(preparers).resume(extractionCommand(command, run, plan.artifacts().size()),
                new DocumentPreparationSeal(plan.token(), plan.artifacts()))) {
            return reopened.promote(identity, missing);
        }
    }

    private void replayPinned(IngestSourceCommand command, DocumentWriteIdentity identity, Plan plan,
                              Set<String> missing) {
        var receipt = canonical.receipts().pinned(command.observationId(), command.key().value(), processing.policy())
                .orElseThrow(() -> new IllegalStateException("Pinned document receipt is missing"));
        if (!receiptPlan(receipt).equals(plan)) {
            throw new IllegalStateException("Pinned document receipt does not match the run plan");
        }
        var context = Objects.requireNonNull(lifecycleContext(command.claimedSource(), plan.artifacts().size()));
        for (var artifact : receipt.artifacts()) {
            if (missing.contains(artifact.artifactName())) {
                canonical.writer().confirm(new CanonicalArtifactConfirmation(context.observationId(), context.sourceKey(),
                        context.receipt(), artifact.artifactName(), artifact.header(), artifact.records(),
                        command.registration(), identity));
            }
        }
    }

    private void project(String runId, Plan plan, Map<String, DocumentArtifactCommit> committed) {
        for (String name : plan.artifacts()) {
            var needed = committed.get(name).generation();
            if (canonical.projectionWork().load(name).projectedGeneration().compareTo(needed) >= 0) {
                continue;
            }
            canonical.projection().project(new ArtifactProjectionCommand(runId, name)).diagnostics()
                    .forEach(processing.diagnostics()::emit);
            if (canonical.projectionWork().load(name).projectedGeneration().compareTo(needed) < 0) {
                throw new IllegalStateException("Document projection has insufficient generation coverage");
            }
        }
    }

    private IngestionRecord ensureSourceClaim(SourceUnit unit, IngestRunStatus status) {
        var existing = journals.sources().find(unit.observationId());
        if (status == IngestRunStatus.STARTED
                && existing.map(IngestionRecord::status).orElse(IngestionStatus.CLAIMED) == IngestionStatus.CLAIMED) {
            requireCompleted(journals.sources().markClaimed(unit));
            existing = journals.sources().find(unit.observationId());
        }
        var source = existing.orElseThrow(() -> new IllegalStateException("Document source claim is missing"));
        if (source.status() == IngestionStatus.FAILED || !source.key().equals(unit.key())
                || !source.originalPath().equals(unit.originalPath())
                || !source.processingPath().equals(unit.processingPath())) {
            throw new IllegalStateException("Document source claim conflicts with the accepted plan");
        }
        return source;
    }

    private void finishArchive(SourceUnit unit, IngestionRecord record) {
        var admission = journals.admissions().find(unit.observationId()).orElseThrow();
        var expected = journals.files().archivePath(unit);
        if (record.status() == IngestionStatus.SOURCE_ARCHIVED && !expected.equals(record.archivedPath())) {
            throw new IllegalStateException("Document archived path differs from the owned location");
        }
        var actual = journals.files().archive(unit, admission.claimedEvidence().orElseThrow());
        requireCompleted(journals.sources().markSourceArchived(unit.observationId(), actual));
    }

    private void releasePlan(IngestSourceCommand command, Plan plan, PreparedExtraction prepared) {
        if (plan.kind() == Plan.Kind.RECEIPT) {
            canonical.receipts().release(command.observationId());
        } else {
            if (prepared != null) {
                prepared.close();
            }
            processing.extraction().discardPlan(command.observationId(),
                    new DocumentPreparationSeal(plan.token(), plan.artifacts()));
        }
    }

    private void publish(String runId, List<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        try {
            processing.events().publish(CanonicalArtifactsChanged.from(runId, changed, processing.clock().instant()));
        } catch (RuntimeException ignored) {
            // Periodic export reconciliation owns durability.
        }
    }

    private static void requireCompleted(IngestionLedgerTransition transition) {
        if (!transition.completed()) {
            throw new IllegalStateException("Document source ledger transition conflict");
        }
    }

    public record Journals(DocumentRunLedger runs, DocumentAdmissionService admissions,
                           IngestionLedger sources, SourceLifecycle files) { }

    public record Canonical(CanonicalDocumentCommits commits, CanonicalArtifactWriter writer,
                            DocumentReceiptPlans receipts, CanonicalObservationStore observations,
                            LifecycleTimeSource time, ArtifactProjectionWorkStore projectionWork,
                            ArtifactProjection projection) { }

    public record Processing(SourcePreparerFactory preparers, IocExtractionServiceFactory extraction,
                             KeyedExecutionGuard guard, String policy, String recoveryPolicy,
                             IngestionLifecycleSupport lifecycle, ControlEventPublisher events,
                             DiagnosticSink diagnostics, Clock clock) { }
}
