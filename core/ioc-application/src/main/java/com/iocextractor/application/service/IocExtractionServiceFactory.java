package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.platform.etl.PipelineObserver;

import java.util.List;
import java.util.Objects;

/**
 * Factory for extraction use cases that differ by source-scoped artifact
 * preparation and post-commit projection. Daemon ingestion uses it without
 * leaking storage or CSV details into the use case.
 */
public final class IocExtractionServiceFactory {

    private final SourceReader reader;
    private final Refanger refanger;
    private final IndicatorExtractor extractor;
    private final SourceAttributor attributor;
    private final MatchPolicy matchPolicy;
    private final boolean deduplicate;
    private final String observabilityMode;
    private final PipelineObserver observer;
    private final DiagnosticSink diagnosticSink;
    private final FailurePolicy failurePolicy;
    private final int maxDiagnosticsPerRun;
    private final CanonicalArtifactRepository repository;
    private final CanonicalArtifactWriter lifecycleWriter;
    private final ArtifactIdentityResolver identityResolver;
    private final PipelineDecisionTracer decisionTracer;

    /** Creates the factory with explicit extraction policies and canonical storage. */
    public IocExtractionServiceFactory(SourceReader reader,
                                       Refanger refanger,
                                       IndicatorExtractor extractor,
                                       SourceAttributor attributor,
                                       MatchPolicy matchPolicy,
                                       boolean deduplicate,
                                       String observabilityMode,
                                       PipelineObserver observer,
                                       DiagnosticSink diagnosticSink,
                                       FailurePolicy failurePolicy,
                                       int maxDiagnosticsPerRun,
                                       CanonicalArtifactRepository repository,
                                       PipelineDecisionTracer decisionTracer) {
        this(reader, refanger, extractor, attributor, matchPolicy, deduplicate,
                observabilityMode, observer, diagnosticSink, failurePolicy,
                maxDiagnosticsPerRun, repository, null, null, decisionTracer);
    }

    /** Creates a factory that supports both compatibility and fixed-validity writes. */
    public IocExtractionServiceFactory(SourceReader reader,
                                       Refanger refanger,
                                       IndicatorExtractor extractor,
                                       SourceAttributor attributor,
                                       MatchPolicy matchPolicy,
                                       boolean deduplicate,
                                       String observabilityMode,
                                       PipelineObserver observer,
                                       DiagnosticSink diagnosticSink,
                                       FailurePolicy failurePolicy,
                                       int maxDiagnosticsPerRun,
                                       CanonicalArtifactRepository repository,
                                       CanonicalArtifactWriter lifecycleWriter,
                                       ArtifactIdentityResolver identityResolver,
                                       PipelineDecisionTracer decisionTracer) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.attributor = Objects.requireNonNull(attributor, "attributor");
        this.matchPolicy = Objects.requireNonNull(matchPolicy, "matchPolicy");
        this.deduplicate = deduplicate;
        this.observabilityMode = Objects.requireNonNull(observabilityMode, "observabilityMode");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        if (maxDiagnosticsPerRun < 1) {
            throw new IllegalArgumentException("maxDiagnosticsPerRun must be positive");
        }
        this.maxDiagnosticsPerRun = maxDiagnosticsPerRun;
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycleWriter = lifecycleWriter;
        this.identityResolver = identityResolver;
        this.decisionTracer = Objects.requireNonNull(decisionTracer, "decisionTracer");
    }

    /**
     * Creates an extraction use case for the provided preparers and projection.
     *
     * @param preparers side-effect-free artifact preparers for this run
     * @param projection projection invoked after each successful canonical commit
     * @return extraction use case
     */
    public ExtractIocsUseCase create(List<ArtifactPreparer> preparers, ArtifactProjection projection) {
        return new IocExtractionService(reader, refanger, extractor, attributor, matchPolicy,
                preparers, repository, lifecycleWriter, identityResolver, projection,
                deduplicate, observabilityMode, observer, diagnosticSink,
                failurePolicy, maxDiagnosticsPerRun, decisionTracer);
    }
}
