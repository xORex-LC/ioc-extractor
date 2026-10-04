package com.iocextractor.application.service;

import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IocExtractionServiceFactoryTest {
    @Test
    void selectedDocumentPlanReceivesEachOccurrenceDuringExtraction() {
        var routed = new AtomicInteger();
        var factory = new IocExtractionServiceFactory(
                source -> "example.com",
                text -> new RefangOutcome(text, List.of()),
                text -> new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of()),
                (text, indicators) -> new AttributionOutcome(List.of(),
                        List.of(new AttributionDecision(indicators.getFirst(), Optional.empty()))),
                false, "oneshot", new NoopPipelineObserver(), NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 100, new NoWriteRepository(), null,
                new CanonicalArtifactIdentityResolver(List.of()), NoopPipelineDecisionTracer.INSTANCE,
                preparers -> occurrence -> {
                    routed.incrementAndGet();
                    return Result.success(List.<RoutedArtifactCandidate>of());
                }, Map.of());

        var result = factory.create(List.of(), request -> {
            throw new AssertionError("dry-run must not project");
        }).extract(new ExtractionCommand("selected-route", Path.of("unused.docx"), true));

        assertThat(result.completionStatus()).isEqualTo(CompletionStatus.COMPLETED_WITH_WARNINGS);
        assertThat(result.extracted()).isOne();
        assertThat(routed).hasValue(1);
    }

    private static final class NoWriteRepository implements CanonicalArtifactRepository {
        @Override public CanonicalArtifact load(String artifactName) {
            throw new AssertionError("dry-run must not load canonical storage");
        }

        @Override public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            throw new AssertionError("dry-run must not write canonical storage");
        }
    }
}
