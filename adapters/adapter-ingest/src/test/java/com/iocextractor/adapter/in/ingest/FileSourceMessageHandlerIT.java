package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestionRejectionResult;
import com.iocextractor.application.port.in.ingest.PreparedIngestion;
import com.iocextractor.application.port.in.ingest.PrepareIngestionUseCase;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real private files and durable journal, with controlled preparation/promotion. */
@IntegrationTest
@Timeout(20)
class FileSourceMessageHandlerIT {
    @TempDir Path directory;

    @Test
    void terminalObservationPreservesCompletionAndDuplicateFields() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DocumentCompletionObserver.class);
        var previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        logger.setAdditive(false);
        var key = new com.iocextractor.application.ingest.SourceKey("digest");
        try {
            for (var status : com.iocextractor.application.pipeline.CompletionStatus.values()) {
                var extraction = new com.iocextractor.application.port.in.ExtractionResult("run-17", 1, 1,
                        Map.of("masks", 1), status, List.of(), com.iocextractor.diagnostics.result.DiagnosticSummary.empty());
                DocumentCompletionObserver.completed(new IngestSourceResult(key, IngestionStatus.SOURCE_ARCHIVED,
                        false, extraction), directory.resolve("source.html"), key);
                var event = appender.list.getLast();
                Map<String, Object> fields = new java.util.HashMap<>();
                event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
                assertThat(fields).containsEntry("ioc.run.id", "run-17")
                        .containsEntry(com.iocextractor.observability.LogField.IOC_COMPLETION_STATUS.key(), status.toString())
                        .containsEntry(com.iocextractor.observability.LogField.EVENT_OUTCOME.key(),
                                status == com.iocextractor.application.pipeline.CompletionStatus.COMPLETED_WITH_ERRORS
                                        ? "failure" : "success");
            }
            DocumentCompletionObserver.completed(new IngestSourceResult(key, IngestionStatus.SOURCE_ARCHIVED,
                    true, null), directory.resolve("duplicate.html"), key);
            Map<String, Object> fields = new java.util.HashMap<>();
            appender.list.getLast().getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
            assertThat(fields).containsEntry(com.iocextractor.observability.LogField.IOC_INGEST_DISPOSITION.key(), "duplicate")
                    .doesNotContainKeys(com.iocextractor.observability.LogField.IOC_RUN_ID.key(),
                            com.iocextractor.observability.LogField.IOC_COMPLETION_STATUS.key());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void newerPreparationCannotOvertakeCanonicalPromotionAndFloodedHintsCoalesce() throws Exception {
        var release = new CountDownLatch(1);
        var newerReady = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var order = java.util.Collections.synchronizedList(new ArrayList<String>());
        var ranks = java.util.Collections.synchronizedList(new ArrayList<Long>());
        Fixture fixture = new Fixture(4, 100);
        Path first = fixture.file("first.html", "old");
        Path second = fixture.file("second.html", "new");
        try (var dispatcher = fixture.dispatcher(command -> {
            if (command.source().equals(first)) { await(release); }
            else { newerReady.countDown(); }
            return fixture.prepared(command, () -> {
                order.add(command.source().getFileName().toString());
                ranks.add(command.registration().admissionOrder().value());
                done.countDown();
            });
        })) {
            dispatcher.start();
            dispatcher.handle(first.toFile());
            dispatcher.handle(second.toFile());
            for (int i = 0; i < 1000; i++) { dispatcher.nudge(); }
            assertThat(newerReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(order).isEmpty();
            assertThat(dispatcher.snapshot().pending()).isEqualTo(2);
            assertThat(dispatcher.snapshot().preparing() + dispatcher.snapshot().ready()).isLessThanOrEqualTo(4);
            release.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(order).containsExactly("first.html", "second.html");
        assertThat(ranks).containsExactly(1L, 2L);
        assertThat(fixture.handler.pending(10)).isEmpty();
    }

    @Test
    void countAndByteSaturationLeaveUnclaimedInputDiscoverableAndBacklogDrains() throws Exception {
        var release = new CountDownLatch(1);
        var prepared = new CountDownLatch(2);
        var done = new CountDownLatch(3);
        Fixture fixture = new Fixture(2, 6);
        Path first = fixture.file("first.html", "one");
        Path second = fixture.file("second.html", "two");
        Path third = fixture.file("third.html", "tri");
        try (var dispatcher = fixture.dispatcher(command -> {
            prepared.countDown();
            if (command.source().equals(first)) { await(release); }
            return fixture.prepared(command, done::countDown);
        })) {
            dispatcher.start();
            dispatcher.handle(first.toFile());
            dispatcher.handle(second.toFile());
            assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) { dispatcher.handle(third.toFile()); dispatcher.nudge(); }
            assertThat(third).exists();
            assertThat(dispatcher.snapshot().pending()).isEqualTo(2);
            assertThat(dispatcher.snapshot().sourceBytes()).isEqualTo(6);
            assertThat(dispatcher.snapshot().saturationCount()).isEqualTo(100);
            release.countDown();
            // Terminal CAS happens before this preparation's completion notification.
            awaitCount(done, 1);
            dispatcher.handle(third.toFile());
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(fixture.handler.pending(10)).isEmpty();
        assertThat(third).doesNotExist();
    }

    @Test
    void periodicJournalScanFindsClaimPersistedBeforeAnyEnqueueOrHint() throws Exception {
        Fixture fixture = new Fixture(4, 100);
        Path source = fixture.file("recover.html", "ioc");
        var id = new ObservationId("persisted-before-enqueue");
        fixture.handler.claim(source, id, fixture.clock.instant());
        var done = new CountDownLatch(1);
        try (var dispatcher = fixture.dispatcher(command -> {
            assertThat(command.observationId()).isEqualTo(id);
            assertThat(command.claimedSourceOptional()).isPresent();
            return fixture.prepared(command, done::countDown);
        })) {
            assertThat(fixture.handler.pending(10)).hasSize(1);
            dispatcher.start();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(fixture.registrations.next.get()).isEqualTo(1);
        assertThat(fixture.handler.pending(10)).isEmpty();
    }

    @Test
    void retriesReuseDurableIdentityAndRankAndPersistAttemptCount() throws Exception {
        Fixture fixture = new Fixture(4, 100);
        var attempts = new AtomicInteger();
        var ids = java.util.Collections.synchronizedList(new ArrayList<ObservationId>());
        var done = new CountDownLatch(1);
        try (var dispatcher = fixture.dispatcher(command -> {
            ids.add(command.observationId());
            if (attempts.incrementAndGet() == 1) { throw new IllegalStateException("transient preparation"); }
            return fixture.prepared(command, done::countDown);
        })) {
            dispatcher.start();
            dispatcher.handle(fixture.file("retry.html", "ioc").toFile());
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(ids).hasSize(2).allMatch(ids.getFirst()::equals);
        assertThat(fixture.registrations.next.get()).isEqualTo(1);
        var reopened = new FileDocumentAdmissionJournal(fixture.journalPath);
        assertThat(reopened.find(ids.getFirst()).orElseThrow().execution().attempts()).isEqualTo(2);
    }

    @Test
    void oversizeInputDoesNotAcquireOwnershipOrAllocateRank() throws Exception {
        Fixture fixture = new Fixture(2, 6);
        var calls = new AtomicInteger();
        Path source = fixture.file("large.html", "too many bytes");
        try (var dispatcher = fixture.dispatcher(command -> {
            calls.incrementAndGet();
            throw new AssertionError("oversize source reached preparation");
        })) {
            dispatcher.start();
            dispatcher.handle(source.toFile());
            assertThat(dispatcher.snapshot().pending()).isZero();
            assertThat(dispatcher.snapshot().saturationCount()).isEqualTo(1);
        }
        assertThat(source).exists();
        assertThat(calls).hasValue(0);
        assertThat(fixture.registrations.next).hasValue(0);
    }

    @Test
    void prehashFailureIsDurablyBlockedWithOwnedFileInsteadOfInventingContentIdentity() throws Exception {
        Fixture fixture = new Fixture(4, 100);
        Path source = fixture.file("changing.html", "ioc");
        var id = new ObservationId("changed-after-claim");
        var admitted = fixture.handler.claim(source, id, fixture.clock.instant());
        Files.writeString(admitted.claimPath(), "changed-size");
        try (var dispatcher = fixture.dispatcher(command -> {
            throw new AssertionError("unstable snapshot reached preparation");
        })) {
            dispatcher.start();
            awaitBlocked(dispatcher);
            assertThat(dispatcher.snapshot().blocked()).isEqualTo(1);
            assertThat(dispatcher.snapshot().pending()).isEqualTo(1);
        }
        var durable = new FileDocumentAdmissionJournal(fixture.journalPath).find(id).orElseThrow();
        assertThat(durable.sourceKey()).isEmpty();
        assertThat(durable.execution().attempts()).isEqualTo(2);
        assertThat(durable.execution().failure()).contains("size changed");
        assertThat(Files.exists(admitted.claimPath()) || Files.exists(Path.of(admitted.claimPath() + ".sealed"))).isTrue();
        assertThat(fixture.diagnostics.diagnostics()).hasSize(2);
    }

    @Test
    void exhaustedPreparationRejectsOneDurableOccurrenceAndUnblocksTheNext() throws Exception {
        Fixture fixture = new Fixture(4, 100);
        var rejected = new AtomicInteger();
        var rejectionCompleted = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        Path bad = fixture.file("bad.html", "bad");
        Path good = fixture.file("good.html", "good");
        try (var dispatcher = new DurableDocumentDispatcher(fixture.handler, command -> {
            if (command.source().equals(bad)) { throw new IllegalStateException("invalid document"); }
            return fixture.prepared(command, done::countDown);
        }, (key, reason) -> {
            rejected.incrementAndGet();
            rejectionCompleted.countDown();
            return IngestionRejectionResult.REJECTED;
        }, fixture.properties, fixture.clock, fixture.diagnostics)) {
            dispatcher.start();
            dispatcher.handle(bad.toFile());
            dispatcher.handle(good.toFile());
            boolean disposed = rejectionCompleted.await(5, TimeUnit.SECONDS);
            assertThat(disposed).as("rejection: admissions=%s; diagnostics=%s",
                    fixture.handler.inspectPending(10), fixture.diagnostics.diagnostics()).isTrue();
            boolean drained = done.await(5, TimeUnit.SECONDS);
            assertThat(drained).as("admissions=%s; capacity=%s; diagnostics=%s",
                    fixture.handler.inspectPending(10), dispatcher.snapshot(), fixture.diagnostics.diagnostics()).isTrue();
            assertThat(rejected).hasValue(1);
            assertThat(dispatcher.snapshot().completedDocuments()).isLessThanOrEqualTo(1);
        }
        assertThat(fixture.handler.pending(10)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void gracefulStopClosesReadyPreparationAndDurableOwnershipSurvivesRestart(boolean cleanupFailure) throws Exception {
        Fixture fixture = new Fixture(4, 100);
        var olderEntered = new CountDownLatch(1);
        var releaseOlder = new CountDownLatch(1);
        var newerReady = new CountDownLatch(1);
        var closed = new AtomicInteger();
        Path older = fixture.file("older.html", "old");
        Path newer = fixture.file("newer.html", "new");
        var dispatcher = fixture.dispatcher(command -> {
            if (command.source().equals(older)) { olderEntered.countDown(); await(releaseOlder); }
            else { newerReady.countDown(); }
            return new PreparedIngestion() {
                public IngestSourceResult promote() { throw new AssertionError("stopped preparation promoted"); }
                public void close() {
                    closed.incrementAndGet();
                    if (cleanupFailure) { throw new IllegalStateException(command.source().getFileName().toString()); }
                }
            };
        });
        var shutdown = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            dispatcher.start(); dispatcher.handle(older.toFile()); dispatcher.handle(newer.toFile());
            assertThat(olderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(newerReady.await(5, TimeUnit.SECONDS)).isTrue();
            var stopped = shutdown.submit(dispatcher::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dispatcher.snapshot().running() && System.nanoTime() < deadline) {
                new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
            }
            assertThat(dispatcher.snapshot().running()).isFalse();
            releaseOlder.countDown();
            if (cleanupFailure) {
                assertThatThrownBy(() -> stopped.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class)
                        .satisfies(failure -> {
                            assertThat(failure.getCause()).hasMessage("older.html");
                            assertThat(failure.getCause().getSuppressed()).singleElement()
                                    .satisfies(cleanup -> assertThat(cleanup).hasMessage("newer.html"));
                        });
            } else { stopped.get(5, TimeUnit.SECONDS); }
            assertThat(closed).hasValue(2);
            assertThat(fixture.handler.pending(10)).hasSize(2);
            var done = new CountDownLatch(2);
            try (var restarted = fixture.dispatcher(command -> fixture.prepared(command, done::countDown))) {
                restarted.start();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(fixture.registrations.next).hasValue(2);
            assertThat(fixture.handler.pending(10)).isEmpty();
        } finally {
            releaseOlder.countDown(); dispatcher.close(); shutdown.shutdownNow();
            assertThat(shutdown.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("test coordination timeout"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }

    private static void awaitCount(CountDownLatch latch, long count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (latch.getCount() > count && System.nanoTime() < deadline) {
            new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
        assertThat(latch.getCount()).isEqualTo(count);
    }

    private static void awaitBlocked(DurableDocumentDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.snapshot().blocked() == 0 && System.nanoTime() < deadline) {
            new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
    }

    private final class Fixture {
        // Admission/retry policy time is controlled; worker termination uses timed latches.
        private final Clock clock = Clock.fixed(java.time.Instant.parse("2026-10-06T00:00:00Z"),
                java.time.ZoneOffset.UTC);
        private final Path journalPath = directory.resolve("admission");
        private final MemoryRegistrations registrations = new MemoryRegistrations();
        private final FileSystemSourceLifecycle sources = new FileSystemSourceLifecycle(
                directory.resolve("processing"), directory.resolve("done"), directory.resolve("failed"));
        private final DocumentAdmissionService service = new DocumentAdmissionService(
                new FileDocumentAdmissionJournal(journalPath), registrations, clock);
        private final OrderedDocumentAdmissionHandler handler = new OrderedDocumentAdmissionHandler(
                service, sources, new FileDocumentCandidateEvidenceReader(), new FileSourceHasher());
        private final CollectingDiagnosticSink diagnostics = new CollectingDiagnosticSink();
        private final IngestAdapterProperties properties;
        private Fixture(int pending, long bytes) {
            properties = new IngestAdapterProperties(null, null, null, null,
                    new IngestAdapterProperties.Retry(2, Duration.ZERO), null, 1,
                    new IngestAdapterProperties.Execution(2, Math.min(4, pending), pending, bytes, bytes));
        }
        private Path file(String name, String content) throws Exception {
            return Files.writeString(directory.resolve(name), content).toAbsolutePath();
        }
        private DurableDocumentDispatcher dispatcher(PrepareIngestionUseCase prepare) {
            return new DurableDocumentDispatcher(handler, prepare,
                    (key, reason) -> IngestionRejectionResult.REJECTED, properties, clock, diagnostics);
        }
        private PreparedIngestion prepared(IngestSourceCommand command, Runnable callback) {
            return new PreparedIngestion() {
                @Override public IngestSourceResult promote() {
                    sources.archive(command.claimedSourceOptional().orElseThrow());
                    service.complete(command.observationId(), DocumentTerminalOutcome.SUCCEEDED);
                    callback.run();
                    return new IngestSourceResult(command.key(), IngestionStatus.SOURCE_ARCHIVED, false, null);
                }
                @Override public void close() { }
            };
        }
    }

    private static final class MemoryRegistrations implements ObservationRegistrationStore {
        private static final String NAMESPACE = "0123456789abcdef0123456789abcdef";
        private final Map<ObservationId, RegisteredObservation> values = new ConcurrentHashMap<>();
        private final AtomicInteger next = new AtomicInteger();
        @Override public RegisteredObservation registerNew(ObservationId id, ObservationOrigin origin) {
            return values.computeIfAbsent(id, ignored -> new RegisteredObservation(id, NAMESPACE,
                    new ObservationOrder(next.incrementAndGet()), origin));
        }
        @Override public RegisteredObservation resume(ObservationId id, String namespace) {
            assertThat(namespace).isEqualTo(NAMESPACE);
            return java.util.Objects.requireNonNull(values.get(id));
        }
        @Override public void markTerminal(ObservationId id, String namespace) { resume(id, namespace); }
        @Override public boolean purgeTerminal(RegisteredObservation registration) {
            return values.remove(registration.observationId(), registration);
        }
    }
}
