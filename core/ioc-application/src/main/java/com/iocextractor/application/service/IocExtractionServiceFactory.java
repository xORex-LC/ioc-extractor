package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.platform.etl.PipelineObserver;

import java.util.List;
import java.util.Objects;

/**
 * Factory for extraction use cases that differ only by output sink set. Daemon
 * ingestion uses this to create source-partitioned pipelines without changing
 * the existing extraction service contract.
 */
public final class IocExtractionServiceFactory {

    private final SourceReader reader;
    private final Refanger refanger;
    private final IndicatorExtractor extractor;
    private final SourceAttributor attributor;
    private final LookupRepository lookup;
    private final boolean deduplicate;
    private final String observabilityMode;
    private final PipelineObserver observer;
    private final DiagnosticSink diagnosticSink;

    public IocExtractionServiceFactory(SourceReader reader,
                                       Refanger refanger,
                                       IndicatorExtractor extractor,
                                       SourceAttributor attributor,
                                       LookupRepository lookup,
                                       boolean deduplicate,
                                       String observabilityMode,
                                       PipelineObserver observer,
                                       DiagnosticSink diagnosticSink) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.attributor = Objects.requireNonNull(attributor, "attributor");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.deduplicate = deduplicate;
        this.observabilityMode = Objects.requireNonNull(observabilityMode, "observabilityMode");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    /**
     * Creates an extraction use case that writes to the provided sinks.
     *
     * @param sinks output sinks for this run
     * @return extraction use case
     */
    public ExtractIocsUseCase create(List<IocSink> sinks) {
        return new IocExtractionService(reader, refanger, extractor, attributor,
                lookup, sinks, deduplicate, observabilityMode, observer, diagnosticSink);
    }
}
