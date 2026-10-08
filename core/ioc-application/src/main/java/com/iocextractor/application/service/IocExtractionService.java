package com.iocextractor.application.service;

import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspaceFactory;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace;
import java.nio.file.Path;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.PreparedExtraction;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineObserver;
import com.iocextractor.platform.etl.PipelineRunner;
import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.stage.AttributeSourceStreamStage;
import com.iocextractor.application.pipeline.stage.ExtractSourceStreamStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStreamStage;
import com.iocextractor.application.pipeline.stage.RefangSourceStreamStage;
import com.iocextractor.application.pipeline.stage.PrepareRoutedArtifactsStage;
import com.iocextractor.application.pipeline.stage.WriteArtifactsStage;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Application core: the ETL pipeline expressed against ports only.
 *
 * <pre>
 *   read → refang → extract → attribute → route and prepare → commit
 * </pre>
 *
 * Framework-free by design; wired in the composition root (bootstrap).
 */
public final class IocExtractionService implements ExtractIocsUseCase {

    private final DocumentPreparationWorkspaceFactory workspaces;
    private final Map<String, ArtifactWritePolicy> writePolicies;
    private final PipelineRunner runner;
    private final Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline;
    private final Clock clock;
    private final String observabilityMode;

    /** Builds the occurrence-preserving document path for an admitted processing plan. */
    IocExtractionService(Components components, Settings settings) {
        this(
                new PipelineRunner(settings.failurePolicy(), settings.observer(),
                        settings.diagnosticSink(), new DiagnosticFactory(Clock.systemUTC()),
                        settings.maxDiagnosticsPerRun()),
                pipeline(components, settings, Clock.systemUTC()),
                Clock.systemUTC(),
                settings.observabilityMode(), components.workspaces(), settings.writePolicies());
    }

    /**
     * Creates the use case with an explicit runner, pipeline, clock and
     * observability mode.
     *
     * @param runner pipeline runner
     * @param pipeline extraction pipeline
     * @param clock metadata clock
     * @param observabilityMode logging mode value
     */
    public IocExtractionService(PipelineRunner runner,
                                Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline,
                                Clock clock,
                                String observabilityMode) {
        this(runner, pipeline, clock, observabilityMode, null, Map.of());
    }

    private IocExtractionService(PipelineRunner runner,
                                Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline,
                                Clock clock, String observabilityMode,
                                DocumentPreparationWorkspaceFactory workspaces,
                                Map<String, ArtifactWritePolicy> writePolicies) {
        this.workspaces = workspaces;
        this.writePolicies = Map.copyOf(writePolicies);
        this.runner = Objects.requireNonNull(runner, "runner");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observabilityMode = Objects.requireNonNull(observabilityMode, "observabilityMode");
    }

    @Override
    public ExtractionResult extract(ExtractionCommand command) {
        if (workspaces == null) { return extractOwned(command, null); }
        var workspace = workspaces.open(command, writePolicies);
        Throwable primary = null;
        try {
            var pinned = new ExtractionCommand(command.runId(), workspace.source(), command.dryRun(),
                    command.lifecycleWriteContext(), command.registration());
            try {
                var result = extractOwned(pinned, workspace, command.source());
                workspace.discard();
                return result;
            } catch (RuntimeException | Error failure) {
                primary = failure;
                // Rejection is precommit. Storage/projection failures retain a sealed promotion pin.
                if (!workspace.promotionStarted()) {
                    try { workspace.discard(); }
                    catch (RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                }
                throw failure;
            }
        } finally { closeWorkspace(workspace, primary); }
    }

    /** Prepares a private sealed workspace without holding canonical source/writer ownership. */
    public PreparedExtraction prepare(ExtractionCommand command) {
        if (workspaces == null) { throw new IllegalStateException("Preparation requires a workspace"); }
        var workspace = workspaces.open(command, writePolicies);
        var pinned = new ExtractionCommand(command.runId(), workspace.source(), command.dryRun(),
                command.lifecycleWriteContext(), command.registration());
        int writeIndex = pipeline.stages().size() - 1;
        Pipeline<ExtractionCommand, PreparedArtifacts> preparation =
                new Pipeline<>(pipeline.stages().subList(0, writeIndex));
        Pipeline<PreparedArtifacts, ArtifactWriteSummary> promotion =
                new Pipeline<>(pipeline.stages().subList(writeIndex, writeIndex + 1));
        try {
            var result = runner.runPreparation(initialEnvelope(pinned, workspace, command.source()), preparation);
            return new OwnedPreparation(workspace, result.envelope(), promotion);
        } catch (RuntimeException | Error failure) {
            discardAndCloseWorkspace(workspace, failure);
            throw failure;
        }
    }

    private static void discardAndCloseWorkspace(DocumentPreparationWorkspace workspace, Throwable primary) {
        Throwable discarded = null;
        try { workspace.discard(); }
        catch (RuntimeException | Error cleanup) { discarded = cleanup; }
        closeWorkspace(workspace, discarded == null ? primary : discarded);
        if (discarded != null && discarded != primary) { primary.addSuppressed(discarded); }
    }

    private static void closeWorkspace(DocumentPreparationWorkspace workspace, Throwable primary) {
        try { workspace.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) { throw cleanup; }
            if (cleanup != primary) { primary.addSuppressed(cleanup); }
        }
    }

    private final class OwnedPreparation implements PreparedExtraction {
        private final DocumentPreparationWorkspace workspace;
        private final Envelope<PreparedArtifacts> prepared;
        private final Pipeline<PreparedArtifacts, ArtifactWriteSummary> promotion;
        private boolean attempted;
        private boolean closed;

        private OwnedPreparation(DocumentPreparationWorkspace workspace, Envelope<PreparedArtifacts> prepared,
                                 Pipeline<PreparedArtifacts, ArtifactWriteSummary> promotion) {
            this.workspace = workspace;
            this.prepared = prepared;
            this.promotion = promotion;
        }

        @Override
        public ExtractionResult promote() {
            if (attempted || closed) { throw new IllegalStateException("Preparation is already consumed"); }
            attempted = true;
            var result = runner.runWithOutcome(prepared, promotion);
            workspace.discard();
            return extractionResult(result);
        }

        @Override
        public void close() {
            if (closed) { return; }
            closed = true;
            try { if (!attempted) { runner.finishPreparation(prepared); } }
            finally {
                try { if (!workspace.promotionStarted()) { workspace.discard(); } }
                finally { workspace.close(); }
            }
        }
    }

    private ExtractionResult extractOwned(ExtractionCommand command,
            DocumentPreparationWorkspace workspace) {
        return extractOwned(command, workspace, command.source());
    }

    private ExtractionResult extractOwned(ExtractionCommand command,
            DocumentPreparationWorkspace workspace, Path originalSource) {
        return extractionResult(runner.runWithOutcome(initialEnvelope(command, workspace, originalSource), pipeline));
    }

    private Envelope<ExtractionCommand> initialEnvelope(ExtractionCommand command,
            DocumentPreparationWorkspace workspace, Path originalSource) {
        var normalizedSource = originalSource.toAbsolutePath().normalize();
        var meta = EnvelopeMeta.initial(command.runId(), normalizedSource.toString(), clock)
                .withAttribute(PipelineMetaAttributes.SOURCE_PATH, normalizedSource)
                .withAttribute(PipelineMetaAttributes.DRY_RUN, command.dryRun())
                .withAttribute(PipelineMetaAttributes.MODE, observabilityMode);
        if (command.lifecycleWriteContext() != null) {
            meta = meta.withAttribute(PipelineMetaAttributes.LIFECYCLE_WRITE_CONTEXT, command.lifecycleWriteContext());
        }
        if (command.registration() != null) {
            meta = meta.withAttribute(PipelineMetaAttributes.REGISTERED_OBSERVATION, command.registration());
        }
        if (workspace != null) { meta = meta.withAttribute(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE, workspace); }
        return Envelope.of(command, meta);
    }

    private ExtractionResult extractionResult(com.iocextractor.platform.etl.PipelineRunResult<ArtifactWriteSummary> result) {
        var output = result.envelope();
        var summary = output.payload();
        return new ExtractionResult(output.meta().runId(), summary.extracted(), summary.retained(),
                new LinkedHashMap<>(summary.writtenPerArtifact()), summary.changedArtifacts(),
                CompletionStatus.from(result.diagnosticSummary()), output.diagnostics(), result.diagnosticSummary());
    }

    private static Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline(
            Components components, Settings settings, Clock clock) {
        var diagnostics = new DiagnosticFactory(clock);
        var attributed = Pipeline.<ExtractionCommand>start()
                .then(new ReadSourceStreamStage(components.reader(), diagnostics))
                .then(new RefangSourceStreamStage(components.refanger(), settings.decisionTracer()))
                .then(new ExtractSourceStreamStage(components.extractor(), diagnostics,
                        settings.decisionTracer(), settings.maxDiagnosticsPerRun()))
                .then(new AttributeSourceStreamStage(components.attributor(), clock,
                        settings.decisionTracer()));
        var prepared = attributed.then(new PrepareRoutedArtifactsStage(
                Objects.requireNonNull(settings.documentPlan(), "documentPlan"), components.preparers(),
                settings.deduplicate(), diagnostics, settings.maxDiagnosticsPerRun()));
        return prepared.then(new WriteArtifactsStage(
                components.repository(), components.lifecycleWriter(),
                components.identityResolver(), components.projection(), diagnostics));
    }

    /** Ports that define one extraction pipeline without choosing its routing policy. */
    record Components(SourceReader reader, Refanger refanger, IndicatorExtractor extractor,
                      SourceAttributor attributor,
                      List<ArtifactPreparer> preparers, CanonicalArtifactRepository repository,
                      CanonicalArtifactWriter lifecycleWriter,
                      ArtifactIdentityResolver identityResolver, ArtifactProjection projection,
                      DocumentPreparationWorkspaceFactory workspaces) { }

    /** Immutable execution settings selected by the composition root. */
    record Settings(boolean deduplicate, String observabilityMode, PipelineObserver observer,
                    DiagnosticSink diagnosticSink, FailurePolicy failurePolicy,
                    int maxDiagnosticsPerRun, PipelineDecisionTracer decisionTracer,
                    DocumentProcessingPlan documentPlan,
                    Map<String, ArtifactWritePolicy> writePolicies) { }
}
