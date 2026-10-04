package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
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
import com.iocextractor.application.pipeline.stage.AttributeSourceStage;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStage;
import com.iocextractor.application.pipeline.stage.RefangStage;
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
                settings.observabilityMode());
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
        this.runner = Objects.requireNonNull(runner, "runner");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observabilityMode = Objects.requireNonNull(observabilityMode, "observabilityMode");
    }

    @Override
    public ExtractionResult extract(ExtractionCommand command) {
        var normalizedSource = command.source().toAbsolutePath().normalize();
        var meta = EnvelopeMeta.initial(command.runId(), normalizedSource.toString(), clock)
                .withAttribute(PipelineMetaAttributes.SOURCE_PATH, normalizedSource)
                .withAttribute(PipelineMetaAttributes.DRY_RUN, command.dryRun())
                .withAttribute(PipelineMetaAttributes.MODE, observabilityMode);
        if (command.lifecycleWriteContext() != null) {
            meta = meta.withAttribute(
                    PipelineMetaAttributes.LIFECYCLE_WRITE_CONTEXT, command.lifecycleWriteContext());
        }
        if (command.registration() != null) {
            meta = meta.withAttribute(
                    PipelineMetaAttributes.REGISTERED_OBSERVATION, command.registration());
        }
        var pipelineResult = runner.runWithOutcome(Envelope.of(command, meta), pipeline);
        var output = pipelineResult.envelope();
        var summary = output.payload();
        var diagnosticSummary = pipelineResult.diagnosticSummary();

        return new ExtractionResult(
                output.meta().runId(),
                summary.extracted(),
                summary.retained(),
                new LinkedHashMap<>(summary.writtenPerArtifact()),
                summary.changedArtifacts(),
                CompletionStatus.from(diagnosticSummary),
                output.diagnostics(),
                diagnosticSummary);
    }

    private static Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline(
            Components components, Settings settings, Clock clock) {
        var diagnostics = new DiagnosticFactory(clock);
        var attributed = Pipeline.<ExtractionCommand>start()
                .then(new ReadSourceStage(components.reader(), diagnostics))
                .then(new RefangStage(components.refanger(), settings.decisionTracer()))
                .then(new ExtractIndicatorsStage(components.extractor(), diagnostics,
                        settings.decisionTracer()))
                .then(new AttributeSourceStage(components.attributor(), clock,
                        settings.decisionTracer()));
        var prepared = attributed.then(new PrepareRoutedArtifactsStage(
                Objects.requireNonNull(settings.documentPlan(), "documentPlan"), components.preparers(),
                Objects.requireNonNull(components.identityResolver(), "identityResolver"),
                settings.writePolicies(), settings.deduplicate(), diagnostics));
        return prepared.then(new WriteArtifactsStage(
                components.repository(), components.lifecycleWriter(),
                components.identityResolver(), components.projection(), diagnostics));
    }

    /** Ports that define one extraction pipeline without choosing its routing policy. */
    record Components(SourceReader reader, Refanger refanger, IndicatorExtractor extractor,
                      SourceAttributor attributor,
                      List<ArtifactPreparer> preparers, CanonicalArtifactRepository repository,
                      CanonicalArtifactWriter lifecycleWriter,
                      ArtifactIdentityResolver identityResolver, ArtifactProjection projection) { }

    /** Immutable execution settings selected by the composition root. */
    record Settings(boolean deduplicate, String observabilityMode, PipelineObserver observer,
                    DiagnosticSink diagnosticSink, FailurePolicy failurePolicy,
                    int maxDiagnosticsPerRun, PipelineDecisionTracer decisionTracer,
                    DocumentProcessingPlan documentPlan,
                    Map<String, ArtifactWritePolicy> writePolicies) { }
}
