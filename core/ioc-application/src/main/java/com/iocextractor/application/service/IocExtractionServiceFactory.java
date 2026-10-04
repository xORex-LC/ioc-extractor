package com.iocextractor.application.service;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlanFactory;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.platform.etl.PipelineObserver;

import java.util.List;
import java.util.Map;
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
    private final DocumentProcessingPlanFactory documentPlanFactory;
    private final Map<String, ArtifactWritePolicy> routedWritePolicies;

    /** Creates a factory that requires an admitted route for each document run. */
    public IocExtractionServiceFactory(SourceReader reader,
                                       Refanger refanger,
                                       IndicatorExtractor extractor,
                                       SourceAttributor attributor,
                                       boolean deduplicate,
                                       String observabilityMode,
                                       PipelineObserver observer,
                                       DiagnosticSink diagnosticSink,
                                       FailurePolicy failurePolicy,
                                       int maxDiagnosticsPerRun,
                                       CanonicalArtifactRepository repository,
                                       CanonicalArtifactWriter lifecycleWriter,
                                       ArtifactIdentityResolver identityResolver,
                                       PipelineDecisionTracer decisionTracer,
                                       DocumentProcessingPlanFactory documentPlanFactory,
                                       Map<String, ArtifactWritePolicy> routedWritePolicies) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.attributor = Objects.requireNonNull(attributor, "attributor");
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
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.decisionTracer = Objects.requireNonNull(decisionTracer, "decisionTracer");
        this.documentPlanFactory = Objects.requireNonNull(documentPlanFactory, "documentPlanFactory");
        this.routedWritePolicies = Map.copyOf(routedWritePolicies);
    }

    /**
     * Creates an extraction use case for the provided preparers and projection.
     *
     * @param preparers side-effect-free artifact preparers for this run
     * @param projection projection invoked after each successful canonical commit
     * @return extraction use case
     */
    public ExtractIocsUseCase create(List<ArtifactPreparer> preparers, ArtifactProjection projection) {
        return create(preparers, projection, documentPlanFactory.create(preparers), routedWritePolicies);
    }

    /** Creates a document use case that resolves candidates after routing on final fields. */
    public ExtractIocsUseCase create(List<ArtifactPreparer> preparers, ArtifactProjection projection,
                                     DocumentProcessingPlan documentPlan,
                                     Map<String, ArtifactWritePolicy> writePolicies) {
        var components = new IocExtractionService.Components(reader, refanger, extractor,
                attributor, preparers, repository, lifecycleWriter,
                identityResolver, projection);
        var settings = new IocExtractionService.Settings(deduplicate, observabilityMode,
                observer, diagnosticSink, failurePolicy, maxDiagnosticsPerRun, decisionTracer,
                Objects.requireNonNull(documentPlan, "documentPlan"), Map.copyOf(writePolicies));
        return new IocExtractionService(components, settings);
    }
}
