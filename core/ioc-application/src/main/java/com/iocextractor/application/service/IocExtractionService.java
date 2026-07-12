package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineObserver;
import com.iocextractor.platform.etl.PipelineRunner;
import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.stage.AttributeSourceStage;
import com.iocextractor.application.pipeline.stage.DeduplicateIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStage;
import com.iocextractor.application.pipeline.stage.RefangStage;
import com.iocextractor.application.pipeline.stage.WriteArtifactsStage;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application core: the ETL pipeline expressed against ports only.
 *
 * <pre>
 *   read → refang → extract → attribute → deduplicate → sink(s)
 * </pre>
 *
 * Framework-free by design; wired in the composition root (bootstrap).
 */
public final class IocExtractionService implements ExtractIocsUseCase {

    private static final String DEFAULT_OBSERVABILITY_MODE = "oneshot";

    private final PipelineRunner runner;
    private final Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline;
    private final Clock clock;
    private final String observabilityMode;

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                List<IocSink> sinks,
                                boolean deduplicate) {
        this(reader, refanger, extractor, attributor, sinks, deduplicate, DEFAULT_OBSERVABILITY_MODE);
    }

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                List<IocSink> sinks,
                                boolean deduplicate,
                                String observabilityMode) {
        this(reader, refanger, extractor, attributor, sinks, deduplicate,
                observabilityMode, new NoopPipelineObserver());
    }

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                List<IocSink> sinks,
                                boolean deduplicate,
                                String observabilityMode,
                                PipelineObserver observer) {
        this(reader, refanger, extractor, attributor, sinks, deduplicate,
                observabilityMode, observer, NoopDiagnosticSink.INSTANCE);
    }

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                List<IocSink> sinks,
                                boolean deduplicate,
                                String observabilityMode,
                                PipelineObserver observer,
                                DiagnosticSink diagnosticSink) {
        this(reader, refanger, extractor, attributor, sinks, deduplicate, observabilityMode,
                observer, diagnosticSink, FailurePolicy.failFast(), 10_000);
    }

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                List<IocSink> sinks,
                                boolean deduplicate,
                                String observabilityMode,
                                PipelineObserver observer,
                                DiagnosticSink diagnosticSink,
                                FailurePolicy failurePolicy,
                                int maxDiagnosticsPerRun) {
        this(
                new PipelineRunner(failurePolicy, observer, diagnosticSink,
                        new DiagnosticFactory(Clock.systemUTC()), maxDiagnosticsPerRun),
                pipeline(reader, refanger, extractor, attributor, sinks, deduplicate, Clock.systemUTC()),
                Clock.systemUTC(),
                observabilityMode);
    }

    /**
     * Creates the use case with an explicit runner, pipeline and clock. This
     * constructor is useful for focused pipeline tests.
     *
     * @param runner pipeline runner
     * @param pipeline extraction pipeline
     * @param clock metadata clock
     */
    public IocExtractionService(PipelineRunner runner,
                                Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline,
                                Clock clock) {
        this(runner, pipeline, clock, DEFAULT_OBSERVABILITY_MODE);
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
        var output = runner.run(Envelope.of(command, meta), pipeline);
        var summary = output.payload();
        var diagnosticSummary = DiagnosticSummary.from(output.diagnostics());

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
                                                                              List<IocSink> sinks,
                                                                              boolean deduplicate,
                                                                              Clock clock) {
        return Pipeline.<ExtractionCommand>start()
                .then(new ReadSourceStage(reader))
                .then(new RefangStage(refanger))
                .then(new ExtractIndicatorsStage(extractor))
                .then(new AttributeSourceStage(attributor, clock))
                .then(new DeduplicateIndicatorsStage(deduplicate))
                .then(new WriteArtifactsStage(sinks));
    }
}
