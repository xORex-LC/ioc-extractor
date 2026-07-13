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
import com.iocextractor.application.pipeline.stage.ClassifyIndicatorsStage;
import com.iocextractor.application.pipeline.stage.DeduplicateIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStage;
import com.iocextractor.application.pipeline.stage.RefangStage;
import com.iocextractor.application.pipeline.stage.PrepareArtifactsStage;
import com.iocextractor.application.pipeline.stage.WriteArtifactsStage;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application core: the ETL pipeline expressed against ports only.
 *
 * <pre>
 *   read → refang → extract → attribute → deduplicate → classify NETWORK → prepare → commit
 * </pre>
 *
 * Framework-free by design; wired in the composition root (bootstrap).
 */
public final class IocExtractionService implements ExtractIocsUseCase {

    private final PipelineRunner runner;
    private final Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline;
    private final Clock clock;
    private final String observabilityMode;

    /** Creates the production extraction use case with explicit policies and ports. */
    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                MatchPolicy matchPolicy,
                                List<ArtifactPreparer> preparers,
                                CanonicalArtifactRepository repository,
                                ArtifactProjection projection,
                                boolean deduplicate,
                                String observabilityMode,
                                PipelineObserver observer,
                                DiagnosticSink diagnosticSink,
                                FailurePolicy failurePolicy,
                                int maxDiagnosticsPerRun,
                                PipelineDecisionTracer decisionTracer) {
        this(
                new PipelineRunner(failurePolicy, observer, diagnosticSink,
                        new DiagnosticFactory(Clock.systemUTC()), maxDiagnosticsPerRun),
                pipeline(reader, refanger, extractor, attributor, matchPolicy, preparers,
                        repository, projection, deduplicate, Clock.systemUTC(), decisionTracer),
                Clock.systemUTC(),
                observabilityMode);
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
        var meta = EnvelopeMeta.initial(UUID.randomUUID().toString(), normalizedSource.toString(), clock)
                .withAttribute(PipelineMetaAttributes.SOURCE_PATH, normalizedSource)
                .withAttribute(PipelineMetaAttributes.DRY_RUN, command.dryRun())
                .withAttribute(PipelineMetaAttributes.MODE, observabilityMode);
        var pipelineResult = runner.runWithOutcome(Envelope.of(command, meta), pipeline);
        var output = pipelineResult.envelope();
        var summary = output.payload();
        var diagnosticSummary = pipelineResult.diagnosticSummary();

        return new ExtractionResult(
                summary.extracted(),
                summary.retained(),
                new LinkedHashMap<>(summary.writtenPerArtifact()),
                CompletionStatus.from(diagnosticSummary),
                output.diagnostics(),
                diagnosticSummary);
    }

    private static Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline(SourceReader reader,
                                                                              Refanger refanger,
                                                                              IndicatorExtractor extractor,
                                                                              SourceAttributor attributor,
                                                                              MatchPolicy matchPolicy,
                                                                              List<ArtifactPreparer> preparers,
                                                                              CanonicalArtifactRepository repository,
                                                                              ArtifactProjection projection,
                                                                              boolean deduplicate,
                                                                              Clock clock,
                                                                              PipelineDecisionTracer decisionTracer) {
        var diagnostics = new DiagnosticFactory(clock);
        return Pipeline.<ExtractionCommand>start()
                .then(new ReadSourceStage(reader, diagnostics))
                .then(new RefangStage(refanger, decisionTracer))
                .then(new ExtractIndicatorsStage(extractor, diagnostics, decisionTracer))
                .then(new AttributeSourceStage(attributor, clock, decisionTracer))
                .then(new DeduplicateIndicatorsStage(deduplicate, diagnostics, decisionTracer))
                .then(new ClassifyIndicatorsStage(matchPolicy, diagnostics, decisionTracer))
                .then(new PrepareArtifactsStage(preparers))
                .then(new WriteArtifactsStage(repository, projection, diagnostics));
    }
}
