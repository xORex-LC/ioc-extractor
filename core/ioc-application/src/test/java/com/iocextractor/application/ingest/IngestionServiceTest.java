package com.iocextractor.application.ingest;

import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.PartitionSinkFactory;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionServiceTest {

    @Test
    void processes_claimed_source_into_partition_and_archives_it() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var partitionPath = Path.of("partitions/masks/2026-06-22/abc123.csv");
        var sink = new CountingSink();
        PartitionSinkFactory partitionFactory = source -> new PartitionSinks(List.of(sink), List.of(partitionPath));
        var service = new IngestionService(ledger, lifecycle, partitionFactory, extractionFactory());

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.partitions()).containsExactly(partitionPath);
        assertThat(sink.written).isEqualTo(1);
        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(lifecycle.events).containsExactly("claim", "archive");
    }

    @Test
    void skips_source_when_same_key_was_already_archived() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                Path.of("old/source.html"), Path.of("processing/source.html"),
                Path.of("done/source.html"), List.of(Path.of("partition.csv")),
                Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-22T00:00:01Z"), null);
        var lifecycle = new MemoryLifecycle();
        var service = new IngestionService(ledger, lifecycle, source -> {
            throw new AssertionError("partition factory must not be called for duplicate");
        }, extractionFactory());

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source-copy.html"), key, Instant.parse("2026-06-22T00:01:00Z")));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(lifecycle.events).containsExactly("archiveDuplicate");
    }

    @Test
    void skips_source_when_same_key_was_already_aggregated() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        ledger.record = new IngestionRecord(key, IngestionStatus.AGGREGATED,
                Path.of("old/source.html"), Path.of("processing/source.html"),
                Path.of("done/source.html"), List.of(Path.of("partition.csv")),
                Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-22T00:00:01Z"), null);
        var lifecycle = new MemoryLifecycle();
        var service = new IngestionService(ledger, lifecycle, source -> {
            throw new AssertionError("partition factory must not be called for duplicate");
        }, extractionFactory());

        var result = service.ingest(new IngestSourceCommand(
                Path.of("inbox/source-copy.html"), key, Instant.parse("2026-06-22T00:01:00Z")));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.status()).isEqualTo(IngestionStatus.AGGREGATED);
        assertThat(lifecycle.events).containsExactly("archiveDuplicate");
    }


    @Test
    void leaves_claimed_source_for_retry_and_rejects_only_after_final_failure() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        var service = new IngestionService(ledger, lifecycle, source -> {
            throw new IllegalStateException("partition unavailable");
        }, extractionFactory());

        assertThatThrownBy(() -> service.ingest(new IngestSourceCommand(
                Path.of("inbox/source.html"), key, Instant.parse("2026-06-22T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.CLAIMED);
        assertThat(lifecycle.events).containsExactly("claim");

        service.reject(key, "partition unavailable");

        assertThat(ledger.find(key)).get()
                .extracting(IngestionRecord::status)
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(lifecycle.events).containsExactly("claim", "failRecovered");
    }

    @Test
    void recovery_marks_processing_orphans_as_failed() {
        var key = new SourceKey("ABC123");
        var ledger = new MemoryLedger();
        var lifecycle = new MemoryLifecycle();
        lifecycle.processingSources = List.of(new ArchivedSourceUnit(
                key, Path.of("processing/abc123-source.html"), Instant.parse("2026-06-22T00:00:00Z")));
        var service = new IngestionService(ledger, lifecycle, source -> new PartitionSinks(List.of(), List.of()),
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
                new LookupRepository() {
                    @Override
                    public boolean contains(Indicator indicator) {
                        return false;
                    }

                    @Override
                    public long maxId() {
                        return 0;
                    }
                },
                false,
                "daemon",
                new NoopPipelineObserver(),
                NoopDiagnosticSink.INSTANCE);
    }

    private static final class CountingSink implements IocSink {
        private int written;

        @Override
        public String name() {
            return "masks";
        }

        @Override
        public int write(List<Indicator> indicators) {
            written += indicators.size();
            return indicators.size();
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

        @Override
        public Optional<IngestionRecord> find(SourceKey key) {
            return Optional.ofNullable(record)
                    .filter(item -> item.key().equals(key));
        }

        @Override
        public void markClaimed(SourceUnit unit) {
            record = new IngestionRecord(unit.key(), IngestionStatus.CLAIMED,
                    unit.originalPath(), unit.processingPath(), null, List.of(),
                    unit.detectedAt(), unit.detectedAt(), null);
        }

        @Override
        public void markPartitionWritten(SourceKey key, List<Path> partitions) {
            record = new IngestionRecord(key, IngestionStatus.PARTITION_WRITTEN,
                    record.originalPath(), record.processingPath(), null, partitions,
                    record.detectedAt(), record.detectedAt(), null);
        }

        @Override
        public void markLedgerRecorded(SourceKey key) {
            record = new IngestionRecord(key, IngestionStatus.LEDGER_RECORDED,
                    record.originalPath(), record.processingPath(), null, record.partitions(),
                    record.detectedAt(), record.detectedAt(), null);
        }

        @Override
        public void markSourceArchived(SourceKey key, Path archivedPath) {
            record = new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                    record.originalPath(), record.processingPath(), archivedPath, record.partitions(),
                    record.detectedAt(), record.detectedAt(), null);
        }

        @Override
        public void markAggregated(SourceKey key) {
            record = new IngestionRecord(key, IngestionStatus.AGGREGATED,
                    record.originalPath(), record.processingPath(), record.archivedPath(), record.partitions(),
                    record.detectedAt(), record.detectedAt(), null);
        }

        @Override
        public void markFailed(SourceKey key, String reason) {
            record = new IngestionRecord(key, IngestionStatus.FAILED,
                    Path.of("unknown"), Path.of("unknown"), null, List.of(),
                    Instant.EPOCH, Instant.EPOCH, reason);
        }

        @Override
        public List<IngestionRecord> findIncomplete() {
            return record == null ? List.of() : List.of(record);
        }

        @Override
        public List<IngestionRecord> findReadyForAggregation() {
            return record != null && record.status() == IngestionStatus.SOURCE_ARCHIVED
                    ? List.of(record)
                    : List.of();
        }
    }
}
