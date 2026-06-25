package com.iocextractor.application.aggregation;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.port.in.aggregation.AggregationCommand;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.aggregation.PartitionArtifactRepository;
import com.iocextractor.application.port.out.aggregation.StableIdIndex;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregationServiceTest {

    @Test
    void appends_new_rows_with_stable_ids_and_marks_sources_after_write() {
        var ledger = new MemoryLedger(sourceRecord("A", Instant.parse("2026-06-22T00:00:00Z")));
        var canonical = new MemoryCanonicalRepository();
        var ids = new MemoryStableIdIndex();
        var service = service(ledger, partitionRepository(row("mask", "example.com")), canonical, ids);

        var result = service.aggregate(AggregationCommand.allArtifacts());

        assertThat(result.sourcesProcessed()).isEqualTo(1);
        assertThat(result.newStableIds()).isEqualTo(1);
        assertThat(canonical.written.rows()).singleElement()
                .extracting(row -> row.value("id"))
                .isEqualTo("1");
        assertThat(ledger.records.get(0).status()).isEqualTo(IngestionStatus.AGGREGATED);
    }

    @Test
    void keeps_existing_row_and_does_not_allocate_new_id_for_duplicate_key() {
        var ledger = new MemoryLedger(sourceRecord("A", Instant.parse("2026-06-22T00:00:00Z")));
        var canonical = new MemoryCanonicalRepository();
        canonical.current = new CanonicalArtifact("masks", List.of("id", "mask"),
                List.of(row("id", "42", "mask", "example.com")));
        var ids = new MemoryStableIdIndex();
        var service = service(ledger, partitionRepository(row("mask", "example.com")), canonical, ids);

        var result = service.aggregate(AggregationCommand.allArtifacts());

        assertThat(result.newStableIds()).isZero();
        assertThat(result.unchangedRows()).isEqualTo(1);
        assertThat(canonical.written.rows()).singleElement()
                .extracting(row -> row.value("id"))
                .isEqualTo("42");
    }

    @Test
    void does_not_mark_sources_when_canonical_write_fails() {
        var ledger = new MemoryLedger(sourceRecord("A", Instant.parse("2026-06-22T00:00:00Z")));
        var canonical = new MemoryCanonicalRepository();
        canonical.failWrite = true;
        var service = service(ledger, partitionRepository(row("mask", "example.com")), canonical, new MemoryStableIdIndex());

        assertThatThrownBy(() -> service.aggregate(AggregationCommand.allArtifacts()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.records.get(0).status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
    }

    private AggregationService service(IngestionLedger ledger,
                                       PartitionArtifactRepository partitionRepository,
                                       CanonicalArtifactRepository canonicalRepository,
                                       StableIdIndex stableIdIndex) {
        ArtifactIdentityResolver resolver = (artifactName, row) -> Optional.ofNullable(row.value("mask"))
                .map(ArtifactRowKey::new);
        return new AggregationService(ledger, partitionRepository, canonicalRepository, resolver,
                stableIdIndex, new KeepFirstMergePolicy(), List.of("masks"));
    }

    private PartitionArtifactRepository partitionRepository(ArtifactRow row) {
        return records -> records.stream()
                .map(record -> new PartitionArtifact(record.key(), "masks", Path.of("partitions/masks/" + record.key().value() + ".csv"),
                        List.of(row)))
                .toList();
    }

    private IngestionRecord sourceRecord(String key, Instant detectedAt) {
        return new IngestionRecord(new SourceKey(key), IngestionStatus.SOURCE_ARCHIVED,
                Path.of("inbox/" + key + ".html"), Path.of("processing/" + key + ".html"),
                Path.of("done/" + key + ".html"), List.of(Path.of("partitions/masks/" + key + ".csv")),
                detectedAt, detectedAt, null);
    }

    private ArtifactRow row(String key, String value) {
        return row(Map.of(key, value));
    }

    private ArtifactRow row(String key1, String value1, String key2, String value2) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return ArtifactRow.ordered(values);
    }

    private ArtifactRow row(Map<String, String> values) {
        return ArtifactRow.ordered(values);
    }

    private static final class MemoryLedger implements IngestionLedger {
        private final List<IngestionRecord> records;

        private MemoryLedger(IngestionRecord... records) {
            this.records = new java.util.ArrayList<>(List.of(records));
        }

        @Override
        public Optional<IngestionRecord> find(SourceKey key) {
            return records.stream().filter(record -> record.key().equals(key)).findFirst();
        }

        @Override
        public void markClaimed(com.iocextractor.application.ingest.SourceUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markPartitionWritten(SourceKey key, List<Path> partitions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markLedgerRecorded(SourceKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSourceArchived(SourceKey key, Path archivedPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAggregated(SourceKey key) {
            replace(key, IngestionStatus.AGGREGATED);
        }

        @Override
        public void markFailed(SourceKey key, String reason) {
            replace(key, IngestionStatus.FAILED);
        }

        @Override
        public List<IngestionRecord> findIncomplete() {
            return List.of();
        }

        @Override
        public List<IngestionRecord> findReadyForAggregation() {
            return records.stream()
                    .filter(record -> record.status() == IngestionStatus.SOURCE_ARCHIVED)
                    .toList();
        }

        @Override
        public List<IngestionRecord> findAggregated() {
            return records.stream()
                    .filter(record -> record.status() == IngestionStatus.AGGREGATED)
                    .toList();
        }

        private void replace(SourceKey key, IngestionStatus status) {
            for (int i = 0; i < records.size(); i++) {
                IngestionRecord record = records.get(i);
                if (record.key().equals(key)) {
                    records.set(i, new IngestionRecord(record.key(), status, record.originalPath(),
                            record.processingPath(), record.archivedPath(), record.partitions(),
                            record.detectedAt(), record.updatedAt(), record.reason()));
                }
            }
        }
    }

    private static final class MemoryCanonicalRepository implements CanonicalArtifactRepository {
        private CanonicalArtifact current = new CanonicalArtifact("masks", List.of("id", "mask"), List.of());
        private CanonicalArtifact written;
        private boolean failWrite;

        @Override
        public CanonicalArtifact load(String artifactName) {
            return current;
        }

        @Override
        public void write(String artifactName, CanonicalArtifact artifact) {
            if (failWrite) {
                throw new IllegalStateException("write failed");
            }
            written = artifact;
        }
    }

    private static final class MemoryStableIdIndex implements StableIdIndex {
        private long nextId = 1L;

        @Override
        public StableArtifactId getOrCreate(String artifactName, ArtifactRowKey key) {
            return new StableArtifactId(nextId++, true);
        }

        @Override
        public void save() {
        }
    }
}
