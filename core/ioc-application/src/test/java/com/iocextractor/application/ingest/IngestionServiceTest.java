package com.iocextractor.application.ingest;

import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.events.RecordingControlEventPublisher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        var sink = new CountingSink();
        var runLedger = new MemoryRunLedger();
        var projection = new CollectingProjection();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of(sink)),
                extractionFactory(), runLedger, projection, events, clock);

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(result.duplicate()).isFalse();
        assertThat(sink.written).isEqualTo(1);
        assertThat(projection.artifacts).containsExactly("masks");
        assertThat(runLedger.status).isEqualTo(IngestRunStatus.COMPLETED);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(lifecycle.events).containsExactly("claim", "archive");
        assertArtifactsChanged(events, "run-1", List.of("masks"));
    }

    @Test
    void leaves_run_recoverable_when_projection_fails_after_db_write() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of(new CountingSink())),
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

        service.reject(key, "source sink unavailable");

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
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of(new CountingSink())),
                extractionFactory(), new MemoryRunLedger(), new CollectingProjection(), events, clock);

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ledger unavailable");

        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(lifecycle.events).containsExactly("claim", "fail");
        assertThat(events.events()).isEmpty();
    }

    @Test
    void extraction_failure_marks_run_failed_and_does_not_emit_event() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var runLedger = new MemoryRunLedger();
        var events = new RecordingControlEventPublisher();
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of(new CountingSink())),
                failingExtractionFactory(), runLedger, new CollectingProjection(), events, clock);

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("read failed");

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
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of(new CountingSink("hashes"))),
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
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of(new CountingSink())),
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
        var service = new IngestionService(ledger, lifecycle, source -> new SourceSinks(List.of()),
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

    private IocExtractionServiceFactory extractionFactory() {
        return new IocExtractionServiceFactory(
                source -> "example.com",
                text -> text,
                text -> List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)),
                (text, indicators) -> List.of(new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext(null, null))),
                false,
                "daemon",
                new NoopPipelineObserver(),
                NoopDiagnosticSink.INSTANCE);
    }

    private IocExtractionServiceFactory failingExtractionFactory() {
        return new IocExtractionServiceFactory(
                source -> {
                    throw new IllegalStateException("read failed");
                },
                text -> text,
                text -> List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)),
                (text, indicators) -> List.of(new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext(null, null))),
                false,
                "daemon",
                new NoopPipelineObserver(),
                NoopDiagnosticSink.INSTANCE);
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

    private static final class CountingSink implements IocSink {
        private final String name;
        private int written;

        private CountingSink() {
            this("masks");
        }

        private CountingSink(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int write(List<Indicator> indicators) {
            written += indicators.size();
            return indicators.size();
        }
    }

    private static final class CollectingProjection implements ArtifactProjection {
        private final List<String> artifacts = new ArrayList<>();

        @Override
        public void project(String artifactName) {
            artifacts.add(artifactName);
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

        @Override
        public SourceUnit claim(Path source, SourceKey key, Instant detectedAt) {
            events.add("claim");
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
            return Path.of("failed/" + source.processingPath().getFileName());
        }

        @Override
        public List<ArchivedSourceUnit> findProcessingSources() {
            return processingSources;
        }
    }

    private static final class MemoryLedger implements IngestionLedger {
        private IngestionRecord record;
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
            return record == null ? List.of() : List.of(record);
        }
    }
}
