package com.iocextractor.application.service;

import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.DocumentPreparationSeal;
import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.application.artifact.DocumentWriteIdentity;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.TestDocumentSourceWorkspace;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace;
import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspaceFactory;
import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.Result;
import com.iocextractor.platform.diagnostics.result.DiagnosticSummary;
import com.iocextractor.platform.diagnostics.sink.NoopDiagnosticSink;
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
import java.util.Set;
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
        var command = new ExtractionCommand("no-workspace", source, false);
        assertThatThrownBy(() -> service.prepare(command)).hasMessage("Preparation requires a workspace");
        assertThatThrownBy(() -> service.resume(command, RecoveryWorkspace.SEAL)).hasMessage("Recovery requires a workspace");
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

    @Test
    void preservesThePinnedWorkspaceIfTheCheckpointCannotBeUsed() {
        var workspace = new RecoveryWorkspace();
        workspace.pinned = true;
        workspace.restoreFailure = new IllegalStateException("invalid sealed plan");
        var service = extractionWithWorkspace(workspace);
        var command = new ExtractionCommand("existing-run", workspace.source(), false);

        assertThatThrownBy(() -> service.extract(command)).hasMessage("Pinned preparation requires managed promotion");
        assertThatThrownBy(() -> service.prepare(command)).isSameAs(workspace.restoreFailure);
        assertThatThrownBy(() -> service.resume(command, RecoveryWorkspace.SEAL)).isSameAs(workspace.restoreFailure);
        assertThat(workspace.closed).isEqualTo(3);
        assertThat(workspace.discarded).isZero();
    }

    @Test
    void managedPromotionAcceptsOnlyASealedUnconsumedHandleAndThePlannedArtifacts() {
        var workspace = new RecoveryWorkspace();
        var service = extractionWithWorkspace(workspace);
        var registration = new RegisteredObservation(new ObservationId("observation"), "namespace",
                new ObservationOrder(17), ObservationOrigin.DOCUMENT);
        var command = new ExtractionCommand("new-run", workspace.source(), false, null, registration);
        var write = new DocumentWriteIdentity(registration, "content", "plan", false);
        try (var prepared = service.prepare(command)) {
            assertThatThrownBy(() -> prepared.promote(write, Set.of())).hasMessageContaining("requires a pinned plan");
            assertThat(prepared.seal()).isEqualTo(RecoveryWorkspace.SEAL);
            assertThat(prepared.seal()).isEqualTo(RecoveryWorkspace.SEAL);
            assertThat(workspace.pins).isOne();
            assertThatThrownBy(prepared::promote).hasMessageContaining("requires managed promotion");
            assertThatThrownBy(() -> prepared.promote(write, Set.of("unplanned"))).hasMessageContaining("pending artifacts");
            assertThat(prepared.promote(write, Set.of()).writtenPerArtifact()).isEmpty();
            assertThatThrownBy(() -> prepared.promote(write, Set.of())).hasMessageContaining("already consumed");
            assertThatThrownBy(prepared::seal).hasMessageContaining("already consumed");
        }

        workspace.pinned = false;
        var closed = service.prepare(command);
        closed.close();
        closed.close();
        assertThatThrownBy(closed::promote).hasMessageContaining("already consumed");
        assertThatThrownBy(closed::seal).hasMessageContaining("already consumed");
        assertThat(workspace.closed).isEqualTo(2);
    }

    @Test
    void surfacesTheWorkspaceCloseFailureWhenExtractionSucceeded() {
        var workspace = new RecoveryWorkspace();
        workspace.closeFailure = new IllegalStateException("workspace close failed");
        var extraction = extractionWithWorkspace(workspace);
        assertThatThrownBy(() -> extraction.extract(new ExtractionCommand("completed", workspace.source(), true)))
                .isSameAs(workspace.closeFailure);
        assertThat(workspace.discarded).isOne();
    }

    private static IocExtractionService extractionWithWorkspace(RecoveryWorkspace workspace) {
        var workspaces = new DocumentPreparationWorkspaceFactory() {
            @Override
            public DocumentPreparationWorkspace open(ExtractionCommand command, Map<String, ArtifactWritePolicy> policies) {
                return workspace;
            }

            @Override
            public DocumentPreparationWorkspace reopen(ExtractionCommand command, Map<String, ArtifactWritePolicy> policies) {
                return workspace;
            }
        };
        var factory = new IocExtractionServiceFactory(
                TestDocumentSourceWorkspace.reader(source -> ""),
                new com.iocextractor.domain.refang.ReplacementRefanger(List.of()),
                TestDocumentSourceWorkspace.extractor(text -> new ExtractionOutcome(List.of(), List.of())),
                TestDocumentSourceWorkspace.noMarkers(),
                false, "daemon", new NoopPipelineObserver(), NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 100, new NoWriteRepository(), null,
                new CanonicalArtifactIdentityResolver(List.of()), NoopPipelineDecisionTracer.INSTANCE,
                preparers -> occurrence -> Result.success(List.of()), Map.of(), workspaces);
        return factory.create(List.of(), request -> {
            throw new AssertionError("An empty plan cannot project an artifact");
        });
    }

    private static final class RecoveryWorkspace implements DocumentPreparationWorkspace {
        static final DocumentPreparationSeal SEAL = new DocumentPreparationSeal("a".repeat(64), List.of());
        boolean pinned;
        int pins;
        int discarded;
        int closed;
        RuntimeException restoreFailure;
        RuntimeException closeFailure;

        @Override public Path source() { return Path.of("workspace/document.html"); }
        @Override public DocumentSourceWorkspace sourceWorkspace() { return new TestDocumentSourceWorkspace(); }
        @Override public void discard() { discarded++; }
        @Override public void beginPromotion() { }
        @Override public boolean promotionStarted() { return false; }
        @Override public boolean recoveryPinned() { return pinned; }
        @Override public boolean firstOriginal(String key) { return true; }

        @Override
        public void append(RoutedArtifactCandidate candidate, boolean eligible, boolean retainObservations) {
            throw new AssertionError("The fixture contains no rows");
        }

        @Override
        public List<ArtifactWritePlan> seal(List<ArtifactWritePlan> descriptors, DocumentPreparationSummary summary) {
            return descriptors;
        }

        @Override
        public DocumentPreparationSeal pinForRecovery(String runId, List<ArtifactWritePlan> plans,
                                                      DocumentPreparationSummary summary) {
            pins++;
            pinned = true;
            return SEAL;
        }

        @Override
        public DocumentPreparationSeal recoverySeal(List<ArtifactWritePlan> descriptors) { return SEAL; }

        @Override
        public RestoredPreparationResult restore(String runId, DocumentPreparationSeal seal,
                                                  List<ArtifactWritePlan> descriptors) {
            if (restoreFailure != null) {
                throw restoreFailure;
            }
            return new RestoredPreparationResult(descriptors, new DocumentPreparationSummary(0, 0, DiagnosticSummary.empty()));
        }

        @Override
        public void close() {
            closed++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
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
