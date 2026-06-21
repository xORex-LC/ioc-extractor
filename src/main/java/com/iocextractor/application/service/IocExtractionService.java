package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.EnvelopeMeta;
import com.iocextractor.application.pipeline.Pipeline;
import com.iocextractor.application.pipeline.PipelineRunner;
import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.stage.AttributeSourceStage;
import com.iocextractor.application.pipeline.stage.DeduplicateIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ExtractIndicatorsStage;
import com.iocextractor.application.pipeline.stage.ReadSourceStage;
import com.iocextractor.application.pipeline.stage.RefangStage;
import com.iocextractor.application.pipeline.stage.WriteArtifactsStage;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.diagnostics.result.FailurePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(IocExtractionService.class);

    private final PipelineRunner runner;
    private final Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline;
    private final Clock clock;

    public IocExtractionService(SourceReader reader,
                                Refanger refanger,
                                IndicatorExtractor extractor,
                                SourceAttributor attributor,
                                LookupRepository lookup,
                                List<IocSink> sinks,
                                boolean deduplicate) {
        this(
                new PipelineRunner(FailurePolicy.failFast()),
                pipeline(reader, refanger, extractor, attributor, lookup, sinks, deduplicate),
                Clock.systemUTC());
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
        this.runner = Objects.requireNonNull(runner, "runner");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExtractionResult extract(ExtractionCommand command) {
        log.info("Extracting IOCs from {}", command.source());

        var meta = EnvelopeMeta.initial(UUID.randomUUID().toString(), command.source(), command.dryRun(), clock);
        var output = runner.run(Envelope.of(command, meta), pipeline);
        var summary = output.payload();

        log.info("Extracted {} indicators, {} retained after de-dup", summary.extracted(), summary.retained());

        if (command.dryRun()) {
            log.info("Dry-run: no artifacts written");
        } else {
            summary.writtenPerArtifact()
                    .forEach((artifact, rows) -> log.info("Artifact '{}' <- {} rows", artifact, rows));
        }

        return new ExtractionResult(
                summary.extracted(),
                summary.retained(),
                new LinkedHashMap<>(summary.writtenPerArtifact()));
    }

    private static Pipeline<ExtractionCommand, ArtifactWriteSummary> pipeline(SourceReader reader,
                                                                              Refanger refanger,
                                                                              IndicatorExtractor extractor,
                                                                              SourceAttributor attributor,
                                                                              LookupRepository lookup,
                                                                              List<IocSink> sinks,
                                                                              boolean deduplicate) {
        return Pipeline.<ExtractionCommand>start()
                .then(new ReadSourceStage(reader))
                .then(new RefangStage(refanger))
                .then(new ExtractIndicatorsStage(extractor))
                .then(new AttributeSourceStage(attributor))
                .then(new DeduplicateIndicatorsStage(lookup, deduplicate))
                .then(new WriteArtifactsStage(sinks));
    }
}
