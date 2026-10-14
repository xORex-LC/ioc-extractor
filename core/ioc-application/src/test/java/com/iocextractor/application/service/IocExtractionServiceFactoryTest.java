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
import org.junit.jupiter.params.ParameterizedTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IocExtractionServiceFactoryTest {
    @Test
    void explicitPipelinePreservesOriginalSourceMetadataAndReturnedCounts() {
        Path source = Path.of("relative/source.html");
        var stage = new com.iocextractor.platform.etl.Stage<ExtractionCommand,
                com.iocextractor.application.pipeline.payload.ArtifactWriteSummary>() {
            public com.iocextractor.platform.etl.StageId name() { return new com.iocextractor.platform.etl.StageId("CUSTOM"); }
            public com.iocextractor.platform.etl.Envelope<com.iocextractor.application.pipeline.payload.ArtifactWriteSummary> process(
                    com.iocextractor.platform.etl.Envelope<ExtractionCommand> input) {
                assertThat(input.meta().attributes()).containsEntry(
                        com.iocextractor.application.pipeline.PipelineMetaAttributes.SOURCE_PATH, source.toAbsolutePath().normalize());
                return input.withPayload(new com.iocextractor.application.pipeline.payload.ArtifactWriteSummary(5, 3, Map.of("masks", 2)));
            }
        };
        var service = new IocExtractionService(new com.iocextractor.platform.etl.PipelineRunner(FailurePolicy.failFast()),
                com.iocextractor.platform.etl.Pipeline.<ExtractionCommand>start().then(stage), java.time.Clock.systemUTC(), "custom");
        var result = service.extract(new ExtractionCommand("custom-run", source, true));
        assertThat(result.runId()).isEqualTo("custom-run");
        assertThat(result.extracted()).isEqualTo(5);
        assertThat(result.retained()).isEqualTo(3);
        assertThat(result.writtenPerArtifact()).containsEntry("masks", 2);
    }

    @Test
    void factoryRejectsAZeroDiagnosticBudgetBeforeOpeningAnyWorkspace() {
        assertThatThrownBy(() -> new IocExtractionServiceFactory(
                source -> "", new com.iocextractor.domain.refang.ReplacementRefanger(List.of()),
                text -> new ExtractionOutcome(List.of(), List.of()),
                (text, indicators) -> new AttributionOutcome(List.of(), List.of()),
                false, "oneshot", new NoopPipelineObserver(), NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 0, new NoWriteRepository(), null,
                new CanonicalArtifactIdentityResolver(List.of()), NoopPipelineDecisionTracer.INSTANCE,
                preparers -> occurrence -> Result.success(List.of()), Map.of(),
                (command, policies) -> { throw new AssertionError("invalid budget must not admit work"); }))
                .hasMessage("maxDiagnosticsPerRun must be positive");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false,false,false", "true,false,false,false,false",
            "false,true,false,false,false", "false,true,true,false,false", "false,false,false,true,false",
            "false,true,true,true,false", "false,false,true,true,false", "false,false,true,true,true", "false,true,true,true,true"})
    void sourceFailureClosesOwnershipAndPreservesOnlyAPreviousPromotionPin(
            boolean promoting, boolean prepare, boolean cleanupFailure, boolean fatal, boolean sharedCleanup) {
        Path snapshot = Path.of("pinned-source.html");
        var discarded = new AtomicInteger();
        var closed = new AtomicInteger();
        var sourceFailure = new AssertionError("source failure");
        var workspace = new com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace() {
            public Path source() { return snapshot; }
            public com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace sourceWorkspace() {
                return new com.iocextractor.application.TestDocumentSourceWorkspace();
            }
            public boolean promotionStarted() { return promoting; }
            public void discard() {
                discarded.incrementAndGet();
                if (sharedCleanup) { throw sourceFailure; }
                if (cleanupFailure) { throw new IllegalStateException("discard failure"); }
            }
            public void close() {
                closed.incrementAndGet();
                if (sharedCleanup) { throw sourceFailure; }
                if (cleanupFailure) { throw new IllegalStateException("close failure"); }
            }
            public void beginPromotion() { throw new AssertionError("source failure must not promote"); }
            public boolean firstOriginal(String key) { throw new AssertionError("source failure must not route"); }
            public void append(RoutedArtifactCandidate candidate, boolean eligible, boolean retained) {
                throw new AssertionError("source failure must not prepare");
            }
            public List<com.iocextractor.application.artifact.ArtifactWritePlan> seal(
                    List<com.iocextractor.application.artifact.ArtifactWritePlan> descriptors,
                    com.iocextractor.application.artifact.DocumentPreparationSummary summary) {
                throw new AssertionError("source failure must not seal");
            }
        };
        var factory = new IocExtractionServiceFactory(
                com.iocextractor.application.TestDocumentSourceWorkspace.reader(source -> {
                    assertThat(source).isEqualTo(snapshot);
                    if (fatal) { throw sourceFailure; }
                    throw new IllegalStateException("source failure");
                }),
                new com.iocextractor.domain.refang.ReplacementRefanger(List.of()),
                text -> new ExtractionOutcome(List.of(), List.of()),
                (text, indicators) -> new AttributionOutcome(List.of(), List.of()),
                false, "oneshot", new NoopPipelineObserver(), NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 100, new NoWriteRepository(), null,
                new CanonicalArtifactIdentityResolver(List.of()), NoopPipelineDecisionTracer.INSTANCE,
                preparers -> occurrence -> Result.success(List.<RoutedArtifactCandidate>of()),
                Map.of(), (command, policies) -> workspace);
        var service = factory.create(List.of(), request -> {
            throw new AssertionError("source failure must not project");
        });
        var command = new ExtractionCommand("failure", Path.of("original.html"), false);
        assertThatThrownBy(() -> {
            if (prepare) { service.prepare(command); } else { service.extract(command); }
        }).satisfies(failure -> {
            if (fatal) { assertThat(failure).isInstanceOf(AssertionError.class).hasMessage("source failure"); }
            else { assertThat(failure).isInstanceOf(com.iocextractor.diagnostics.DiagnosticException.class)
                    .hasRootCauseMessage("source failure"); }
            if (sharedCleanup) {
                assertThat(failure).isSameAs(sourceFailure);
                assertThat(failure.getSuppressed()).isEmpty();
            } else if (cleanupFailure) {
                if (prepare) {
                    assertThat(failure.getSuppressed()).singleElement().satisfies(discard -> {
                        assertThat(discard).hasMessage("discard failure");
                        assertThat(discard.getSuppressed()).singleElement()
                                .satisfies(close -> assertThat(close).hasMessage("close failure"));
                    });
                } else { assertThat(failure.getSuppressed()).extracting(Throwable::getMessage)
                        .containsExactly("discard failure", "close failure"); }
            } else { assertThat(failure.getSuppressed()).isEmpty(); }
        });
        assertThat(discarded).hasValue(promoting ? 0 : 1);
        assertThat(closed).hasValue(1);
    }

    @Test
    void selectedDocumentPlanReceivesEachOccurrenceDuringExtraction() {
        var routed = new AtomicInteger();
        var factory = new IocExtractionServiceFactory(
                com.iocextractor.application.TestDocumentSourceWorkspace.reader(source -> "example.com"),
                new com.iocextractor.domain.refang.ReplacementRefanger(List.of()),
                com.iocextractor.application.TestDocumentSourceWorkspace.extractor(text -> new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of())),
                com.iocextractor.application.TestDocumentSourceWorkspace.noMarkers(),
                false, "oneshot", new NoopPipelineObserver(), NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 100, new NoWriteRepository(), null,
                new CanonicalArtifactIdentityResolver(List.of()), NoopPipelineDecisionTracer.INSTANCE,
                preparers -> occurrence -> {
                    routed.incrementAndGet();
                    return Result.success(List.<RoutedArtifactCandidate>of());
                }, Map.of(), com.iocextractor.application.TestDocumentWorkspace.factory((artifact, row) -> Optional.empty()));

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
