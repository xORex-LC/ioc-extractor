package com.iocextractor.application.ingest;

import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestionRejectionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.events.RecordingControlEventPublisher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionServiceTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-06-22T00:00:05Z");

    private final Clock clock = Clock.fixed(EVENT_TIME, ZoneOffset.UTC);

    @Test
    void processes_claimed_source_into_canonical_storage_projects_and_archives_it() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var sink = new CountingPreparer();
        var runLedger = new MemoryRunLedger();
        var projection = new CollectingProjection();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of(sink)),
                extractionFactory(), runLedger, projection, events, clock);

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.extractionResultOptional()).get()
                .extracting(extraction -> extraction.runId())
                .isEqualTo("run-1");
        assertThat(sink.written).isEqualTo(1);
        assertThat(projection.requests).singleElement().satisfies(request -> {
            assertThat(request.runId()).isEqualTo("run-1");
            assertThat(request.artifactName()).isEqualTo("masks");
        });
        assertThat(runLedger.status).isEqualTo(IngestRunStatus.COMPLETED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(lifecycle.events).containsExactly("claim", "archive");
        assertArtifactsChanged(events, "run-1", List.of("masks"));
    }

    @Test
    void emits_projection_diagnostic_once_and_merges_it_into_daemon_completion() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var diagnostic = new DiagnosticFactory(clock).create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "run-1")
                .with("reason", "lossy projection")
                .build();
        var projection = new CollectingProjection(new ArtifactProjectionResult(1, List.of(diagnostic)));
        var diagnosticSink = new CollectingDiagnosticSink();
        var service = new IngestionService(
                ledger,
                lifecycle,
                source -> new SourcePreparers(List.of(new CountingPreparer())),
                extractionFactory(),
                runLedger,
                projection,
                new RecordingControlEventPublisher(),
                clock,
                diagnosticSink);

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z")));

        assertThat(diagnosticSink.diagnostics()).containsExactly(diagnostic);
        assertThat(result.extractionResultOptional()).get().satisfies(extraction -> {
            assertThat(extraction.completionStatus()).isEqualTo(CompletionStatus.COMPLETED_WITH_WARNINGS);
            assertThat(extraction.diagnostics())
                    .extracting(entry -> entry.code().id())
                    .containsExactly("SOURCE.MARKERS_UNMATCHED", "INGEST.SOURCE_UNREADABLE");
            assertThat(extraction.diagnosticSummary().total()).isEqualTo(2);
            assertThat(extraction.diagnosticSummary().suppressed()).isZero();
            assertThat(extraction.diagnosticSummary().count(DiagnosticSeverity.WARN)).isEqualTo(2);
        });
    }

    @Test
    void leaves_run_recoverable_when_projection_fails_after_db_write() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of(new CountingPreparer())),
                extractionFactory(), runLedger, artifactName -> {
                    throw new IllegalStateException("projection failed");
                }, events, clock);

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");

        assertThat(runLedger.status).isEqualTo(IngestRunStatus.DB_COMMITTED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.CLAIMED);
        assertThat(lifecycle.events).containsExactly("claim");
        assertThat(events.events()).isEmpty();
    }

    @Test
    void does_not_create_sinks_for_a_duplicate_source() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                Path.of("old/source.html"), Path.of("processing/source.html"),
                Path.of("done/source.html"),
                Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-22T00:00:01Z"), null);
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, new MemoryLifecycle(), source -> {
            throw new AssertionError("source sink factory must not be called for duplicate");
        }, extractionFactory(), new MemoryRunLedger(), new CollectingProjection(), events, clock);

        service.ingest(new IngestSourceCommand(
                Path.of("inbox/source-copy.html"), key, Instant.parse("2026-06-22T00:01:00Z")));

        assertThat(events.events()).isEmpty();
    }

    @Test
    void skips_source_when_same_key_was_already_archived() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                Path.of("old/source.html"), Path.of("processing/source.html"),
                Path.of("done/source.html"),
                Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-22T00:00:01Z"), null);
        var lifecycle = new MemoryLifecycle();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> {
            throw new AssertionError("source sink factory must not be called for duplicate");
        }, extractionFactory(), new MemoryRunLedger(), new CollectingProjection(), events, clock);

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source-copy.html"), key, Instant.parse("2026-06-22T00:01:00Z")));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(lifecycle.events).containsExactly("archiveDuplicate");
        assertThat(events.events()).isEmpty();
    }

    @Test
    void existing_failed_source_does_not_emit_event() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.FAILED,
                Path.of("old/source.html"), Path.of("processing/source.html"), null,
                Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-22T00:00:01Z"), "failed");
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, new MemoryLifecycle(), source -> {
            throw new AssertionError("source sink factory must not be called for failed record");
        }, extractionFactory(), new MemoryRunLedger(), new CollectingProjection(), events, clock);

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source-copy.html"), key, Instant.parse("2026-06-22T00:01:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(events.events()).isEmpty();
    }

    @Test
    void leaves_claimed_source_for_retry_and_rejects_only_after_final_failure() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> {
            throw new IllegalStateException("source sink unavailable");
        }, extractionFactory(), new MemoryRunLedger(), new CollectingProjection(), events, clock);

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.CLAIMED);
        assertThat(lifecycle.events).containsExactly("claim");

        IngestionRejectionResult rejection = service.reject(key, "source sink unavailable");

        assertThat(rejection).isEqualTo(IngestionRejectionResult.REJECTED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(lifecycle.events).containsExactly("claim", "failRecovered");
        assertThat(events.events()).isEmpty();
    }

    @Test
    void claim_failure_does_not_emit_event() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.claimFailure = new IllegalStateException("ledger unavailable");
        var lifecycle = new MemoryLifecycle();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of(new CountingPreparer())),
                extractionFactory(), new MemoryRunLedger(), new CollectingProjection(), events, clock);

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOfSatisfying(DiagnosticException.class, failure -> {
                    assertThat(failure.diagnostic().code()).isEqualTo(IngestDiagnosticCodes.LEDGER_WRITE_FAILED);
                    assertThat(failure).hasRootCauseMessage("ledger unavailable");
                });

        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(lifecycle.events).containsExactly("claim", "fail");
        assertThat(events.events()).isEmpty();
    }

    @Test
    void physicalClaimFailureCarriesExactIngestDiagnosticForFinalRetryBoundary() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        lifecycle.claimFailure = new IllegalStateException("processing move failed");
        var service = new IngestionService(
                ledger, lifecycle, source -> new SourcePreparers(List.of()), extractionFactory());

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOfSatisfying(DiagnosticException.class, failure -> {
                    assertThat(failure.diagnostic().code()).isEqualTo(IngestDiagnosticCodes.CLAIM_FAILED);
                    assertThat(failure.diagnostic().context())
                            .containsEntry("source", Path.of("inbox/source.html"));
                    assertThat(failure).hasRootCauseMessage("processing move failed");
                });
    }

    @Test
    void recoveryFailureEmitsAfterRecoveryBoundaryAndRemainsTyped() {
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        lifecycle.findProcessingFailure = new IllegalStateException("processing scan failed");
        var diagnostics = new CollectingDiagnosticSink();
        var service = new IngestionService(
                ledger, lifecycle, source -> new SourcePreparers(List.of()), extractionFactory(),
                new MemoryRunLedger(), new CollectingProjection(),
                new RecordingControlEventPublisher(), clock, diagnostics);

        assertThatThrownBy(service::recoverIncomplete)
                .isInstanceOfSatisfying(DiagnosticException.class, failure ->
                        assertThat(failure.diagnostic().code()).isEqualTo(IngestDiagnosticCodes.RECOVERY_FAILED));

        assertThat(diagnostics.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(IngestDiagnosticCodes.RECOVERY_FAILED);
            assertThat(diagnostic.context()).containsEntry("source", "recovery-scan");
        });
    }

    @Test
    void deadLetterFailureCarriesExactDiagnosticWithoutPretendingLedgerTransitionSucceeded() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.CLAIMED,
                Path.of("inbox/source.html"), Path.of("processing/source.html"), null,
                Instant.EPOCH, Instant.EPOCH, null);
        var lifecycle = new MemoryLifecycle();
        lifecycle.failRecoveredFailure = new IllegalStateException("failed move unavailable");
        var service = new IngestionService(
                ledger, lifecycle, source -> new SourcePreparers(List.of()), extractionFactory());

        assertThatThrownBy(() -> service.reject(key, "pipeline failed"))
                .isInstanceOfSatisfying(DiagnosticException.class, failure -> {
                    assertThat(failure.diagnostic().code()).isEqualTo(IngestDiagnosticCodes.DEAD_LETTER_FAILED);
                    assertThat(failure).hasRootCauseMessage("failed move unavailable");
                });

        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.CLAIMED);
    }

    @Test
    void repeatedTerminalRejectionIsAnIdempotentNoOp() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.markFailed(key, "unreadable source");
        var lifecycle = new MemoryLifecycle();
        var service = new IngestionService(
                ledger, lifecycle, source -> new SourcePreparers(List.of()), extractionFactory());

        IngestionRejectionResult result = service.reject(key, "unreadable source again");

        assertThat(result).isEqualTo(IngestionRejectionResult.ALREADY_REJECTED);
        assertThat(lifecycle.events).isEmpty();
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::reason)
                .isEqualTo("unreadable source");
    }

    @Test
    void extraction_failure_marks_run_failed_and_does_not_emit_event() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of(new CountingPreparer())),
                failingExtractionFactory(), runLedger, new CollectingProjection(), events, clock);

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOfSatisfying(DiagnosticException.class, failure -> {
                    assertThat(failure.diagnostic().code()).isEqualTo(PipelineDiagnosticCodes.STAGE_FAILED);
                    assertThat(failure).hasCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("read failed");
                });

        assertThat(runLedger.status).isEqualTo(IngestRunStatus.FAILED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.CLAIMED);
        assertThat(events.events()).isEmpty();
    }

    @Test
    void claimed_recovery_emits_event_through_process_claimed_path() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.CLAIMED,
                Path.of("inbox/source.html"), Path.of("processing/source.html"), null,
                Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-22T00:00:01Z"), null);
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of(new CountingPreparer("hashes"))),
                extractionFactory(), runLedger, new CollectingProjection(), events, clock);

        var results = service.recoverIncomplete();

        assertThat(results).singleElement()
                .extracting(IngestSourceResult::status)
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(runLedger.status).isEqualTo(IngestRunStatus.COMPLETED);
        assertArtifactsChanged(events, "run-1", List.of("hashes"));
    }

    @Test
    void publisher_failure_does_not_change_successful_ingest_result() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of(new CountingPreparer())),
                extractionFactory(), runLedger, new CollectingProjection(), event -> {
                    throw new IllegalStateException("event bus unavailable");
                }, clock);

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(runLedger.status).isEqualTo(IngestRunStatus.COMPLETED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
    }

    @Test
    void recovery_marks_processing_orphans_as_failed() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        lifecycle.processingSources = List.of(new ArchivedSourceUnit(
                key, Path.of("processing/abc123-source.html"), Instant.parse("2026-06-22T00:00:00Z")));
        var service = new IngestionService(ledger, lifecycle, source -> new SourcePreparers(List.of()),
                extractionFactory());

        var results = service.recoverIncomplete();

        assertThat(results).singleElement()
                .extracting(IngestSourceResult::status)
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(lifecycle.events).containsExactly("failRecovered");
    }

    @Test
    void recoveryReReadsLedgerStateAfterScanningIncompleteRecords() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var claimedSnapshot = new IngestionRecord(key, IngestionStatus.CLAIMED,
                Path.of("inbox/source.html"), Path.of("processing/source.html"), null,
                Instant.EPOCH, Instant.EPOCH, null);
        ledger.incompleteRecords = List.of(claimedSnapshot);
        ledger.record = new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                claimedSnapshot.originalPath(), claimedSnapshot.processingPath(), Path.of("done/source.html"),
                claimedSnapshot.detectedAt(), claimedSnapshot.detectedAt(), null);
        var lifecycle = new MemoryLifecycle();
        var service = new IngestionService(ledger, lifecycle, source -> {
            throw new AssertionError("a stale CLAIMED snapshot must not restart extraction");
        }, extractionFactory());

        var results = service.recoverIncomplete();

        assertThat(results).singleElement()
                .extracting(IngestSourceResult::status)
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(lifecycle.events).isEmpty();
    }

    @Test
    void serializesConcurrentEntryPointsForTheSameSourceKey() throws Exception {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var guard = new SynchronousKeyedExecutionGuard();
        var factoryEntered = new CountDownLatch(1);
        var releaseFactory = new CountDownLatch(1);
        var factoryCalls = new AtomicInteger();
        var service = new IngestionService(
                ledger,
                lifecycle,
                source -> {
                    factoryCalls.incrementAndGet();
                    factoryEntered.countDown();
                    await(releaseFactory);
                    return new SourcePreparers(List.of(new CountingPreparer()));
                },
                extractionFactory(),
                new MemoryRunLedger(),
                new CollectingProjection(),
                new RecordingControlEventPublisher(),
                clock,
                NoopDiagnosticSink.INSTANCE,
                guard);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.ingest(new IngestSourceCommand(
                    Path.of("inbox/source.html"), key, Instant.EPOCH)));
            assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> service.ingest(new IngestSourceCommand(
                    Path.of("inbox/source-copy.html"), key, Instant.EPOCH)));
            awaitWaitingCaller(guard);

            assertThat(factoryCalls).hasValue(1);
            releaseFactory.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).duplicate()).isFalse();
            assertThat(second.get(5, TimeUnit.SECONDS).duplicate()).isTrue();
        } finally {
            releaseFactory.countDown();
        }

        assertThat(factoryCalls).hasValue(1);
        assertThat(lifecycle.events).containsExactly("claim", "archive", "archiveDuplicate");
    }

    private static void awaitWaitingCaller(SynchronousKeyedExecutionGuard guard) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (guard.snapshot().waiting() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(guard.snapshot().waiting()).isOne();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination interrupted", failure);
        }
    }

    private IocExtractionServiceFactory extractionFactory() {
        return new IocExtractionServiceFactory(
                source -> "example.com",
                text -> new RefangOutcome(text, List.of()),
                text -> new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of()),
                (text, indicators) -> new AttributionOutcome(List.of(),
                        List.of(new AttributionDecision(indicators.getFirst(), Optional.empty()))),
                indicator -> classificationDecision(indicator),
                false,
                "daemon",
                new NoopPipelineObserver(),
                NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 10_000, new MemoryRepository(),
                NoopPipelineDecisionTracer.INSTANCE);
    }

    private IocExtractionServiceFactory failingExtractionFactory() {
        return new IocExtractionServiceFactory(
                source -> {
                    throw new IllegalStateException("read failed");
                },
                text -> new RefangOutcome(text, List.of()),
                text -> new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of()),
                (text, indicators) -> new AttributionOutcome(List.of(),
                        List.of(new AttributionDecision(indicators.getFirst(), Optional.empty()))),
                indicator -> classificationDecision(indicator),
                false,
                "daemon",
                new NoopPipelineObserver(),
                NoopDiagnosticSink.INSTANCE,
                FailurePolicy.failFast(), 10_000, new MemoryRepository(),
                NoopPipelineDecisionTracer.INSTANCE);
    }

    private ClassificationDecision classificationDecision(Indicator indicator) {
        return new ClassificationDecision(
                new IndicatorFeatures(indicator.value(), indicator.value(), false, false, false,
                        HostKind.REGISTRABLE),
                0, List.of(), new MaskMatch("u:hAS", "h:dAS"));
    }

    private void assertArtifactsChanged(RecordingControlEventPublisher events,
                                        String runId,
                                        List<String> artifactNames) {
        assertThat(events.events()).singleElement()
                .isInstanceOfSatisfying(CanonicalArtifactsChanged.class, event -> {
                    assertThat(event.runId()).isEqualTo(runId);
                    assertThat(event.artifactNames()).containsExactlyElementsOf(artifactNames);
                    assertThat(event.metadata().eventId()).isEqualTo("canonical-artifacts-changed:" + runId);
                    assertThat(event.metadata().eventType()).isEqualTo(CanonicalArtifactsChanged.EVENT_TYPE);
                    assertThat(event.metadata().eventVersion()).isEqualTo(CanonicalArtifactsChanged.EVENT_VERSION);
                    assertThat(event.metadata().occurredAt()).isEqualTo(EVENT_TIME);
                    assertThat(event.metadata().correlationId()).isEqualTo(runId);
                    assertThat(event.metadata().causationId()).isNull();
                });
    }

    private static final class CountingPreparer implements ArtifactPreparer {
        private final String name;
        private int written;

        private CountingPreparer() {
            this("masks");
        }

        private CountingPreparer(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> indicators) {
            written += indicators.size();
            var rows = indicators.stream()
                    .map(indicator -> new PreparedArtifactRow(ArtifactRow.ordered(
                            java.util.Map.of("value", indicator.indicator().value())), Optional.empty()))
                    .toList();
            return Result.success(new ArtifactWritePlan(name, List.of("value"), rows,
                    new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1)));
        }
    }

    private static final class MemoryRepository implements CanonicalArtifactRepository {
        @Override
        public CanonicalArtifact load(String artifactName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            return new CanonicalWriteResult(artifact.rows().size(), artifact.rows().isEmpty() ? 0 : 1);
        }
    }

    private static final class CollectingProjection implements ArtifactProjection {
        private final List<ArtifactProjectionCommand> requests = new ArrayList<>();
        private final ArtifactProjectionResult outcome;

        private CollectingProjection() {
            this(ArtifactProjectionResult.clean(0));
        }

        private CollectingProjection(ArtifactProjectionResult outcome) {
            this.outcome = outcome;
        }

        @Override
        public ArtifactProjectionResult project(ArtifactProjectionCommand request) {
            requests.add(request);
            return outcome;
        }
    }

    private static final class MemoryRunLedger implements RunLedger {
        private IngestRunStatus status;

        @Override
        public IngestRun startIngest(String sourceKey, List<String> artifacts) {
            status = IngestRunStatus.STARTED;
            return new IngestRun("run-1", sourceKey, status, artifacts, Instant.EPOCH, Instant.EPOCH, null);
        }

        @Override
        public void markDbCommitted(String runId) {
            status = IngestRunStatus.DB_COMMITTED;
        }

        @Override
        public void markProjectionCompleted(String runId) {
            status = IngestRunStatus.PROJECTION_COMPLETED;
        }

        @Override
        public void markCompleted(String runId) {
            status = IngestRunStatus.COMPLETED;
        }

        @Override
        public void markFailed(String runId, String reason) {
            status = IngestRunStatus.FAILED;
        }

        @Override
        public List<IngestRun> findIncompleteIngestRuns() {
            return List.of();
        }
    }

    private static final class MemoryLifecycle implements SourceLifecycle {
        private final List<String> events = new ArrayList<>();
        private List<ArchivedSourceUnit> processingSources = List.of();
        private RuntimeException claimFailure;
        private RuntimeException findProcessingFailure;
        private RuntimeException failRecoveredFailure;

        @Override
        public SourceUnit claim(Path source, SourceKey key, Instant detectedAt) {
            events.add("claim");
            if (claimFailure != null) {
                throw claimFailure;
            }
            return new SourceUnit(key, source, Path.of("processing/" + source.getFileName()), detectedAt);
        }

        @Override
        public Path archive(SourceUnit unit) {
            events.add("archive");
            return Path.of("done/" + unit.processingPath().getFileName());
        }

        @Override
        public Path archive(ArchivedSourceUnit source) {
            events.add("archiveRecovered");
            return Path.of("done/" + source.processingPath().getFileName());
        }

        @Override
        public Path archiveDuplicate(Path source, SourceKey key) {
            events.add("archiveDuplicate");
            return Path.of("done/" + source.getFileName());
        }

        @Override
        public Path fail(SourceUnit unit, String reason) {
            events.add("fail");
            return Path.of("failed/" + unit.processingPath().getFileName());
        }

        @Override
        public Path fail(ArchivedSourceUnit source, String reason) {
            events.add("failRecovered");
            if (failRecoveredFailure != null) {
                throw failRecoveredFailure;
            }
            return Path.of("failed/" + source.processingPath().getFileName());
        }

        @Override
        public List<ArchivedSourceUnit> findProcessingSources() {
            if (findProcessingFailure != null) {
                throw findProcessingFailure;
            }
            return processingSources;
        }
    }

    private static final class MemoryLedger implements IngestionLedger {
        private IngestionRecord record;
        private List<IngestionRecord> incompleteRecords;
        private RuntimeException claimFailure;

        @Override
        public Optional<IngestionRecord> find(SourceKey key) {
            return Optional.ofNullable(record)
                    .filter(item -> item.key().equals(key));
        }

        @Override
        public void markClaimed(SourceUnit unit) {
            if (claimFailure != null) {
                throw claimFailure;
            }
            record = new IngestionRecord(unit.key(), IngestionStatus.CLAIMED,
                    unit.originalPath(), unit.processingPath(), null,
                    unit.detectedAt(), unit.detectedAt(), null);
        }

        @Override
        public void markSourceArchived(SourceKey key, Path archivedPath) {
            record = new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                    record.originalPath(), record.processingPath(), archivedPath,
                    record.detectedAt(), record.detectedAt(), null);
        }

        @Override
        public void markFailed(SourceKey key, String reason) {
            record = new IngestionRecord(key, IngestionStatus.FAILED,
                    Path.of("unknown"), Path.of("unknown"), null,
                    Instant.EPOCH, Instant.EPOCH, reason);
        }

        @Override
        public List<IngestionRecord> findIncomplete() {
            if (incompleteRecords != null) {
                return incompleteRecords;
            }
            return record == null ? List.of() : List.of(record);
        }
    }
}
