package com.iocextractor.application.maintenance;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.maintenance.RetentionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatedPartitionRetentionEligibilityTest {

    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");
    private static final RetentionTarget PARTITIONS = new RetentionTarget(
            "partitions",
            Path.of("dataframe/partitions"),
            Duration.ofDays(1),
            0,
            RetentionAction.DELETE,
            null);

    @Test
    void retention_reaps_only_partitions_that_belong_to_aggregated_records() {
        Path aggregated = Path.of("dataframe/partitions/masks/2026-06-20/A.csv");
        Path notAggregated = Path.of("dataframe/partitions/masks/2026-06-20/B.csv");
        var store = new MemoryRetentionStore(List.of(entry(aggregated), entry(notAggregated)));
        var ledger = new MemoryLedger(List.of(record("A", IngestionStatus.AGGREGATED, aggregated),
                record("B", IngestionStatus.SOURCE_ARCHIVED, notAggregated)));
        var service = new RetentionService(
                store,
                List.of(PARTITIONS),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AggregatedPartitionRetentionEligibility(ledger));

        var result = service.run();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.reaped()).isEqualTo(1);
        assertThat(store.deleted).containsExactly(aggregated);
    }

    @Test
    void non_partition_targets_remain_ungated() {
        Path done = Path.of("var/done/A.html");
        var target = new RetentionTarget("done", Path.of("var/done"), Duration.ofDays(1), 0,
                RetentionAction.DELETE, null);
        var store = new MemoryRetentionStore(List.of(entry(done)));
        var service = new RetentionService(
                store,
                List.of(target),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AggregatedPartitionRetentionEligibility(new MemoryLedger(List.of())));

        service.run();

        assertThat(store.deleted).containsExactly(done);
    }

    private RetentionEntry entry(Path path) {
        return new RetentionEntry(path, NOW.minus(Duration.ofDays(10)), Path.of("."));
    }

    private IngestionRecord record(String key, IngestionStatus status, Path partition) {
        return new IngestionRecord(
                new SourceKey(key),
                status,
                Path.of("inbox/" + key + ".html"),
                Path.of("processing/" + key + ".html"),
                Path.of("done/" + key + ".html"),
                List.of(partition),
                NOW,
                NOW,
                null);
    }

    private static final class MemoryRetentionStore implements RetentionStore {
        private final List<RetentionEntry> entries;
        private final List<Path> deleted = new ArrayList<>();

        private MemoryRetentionStore(List<RetentionEntry> entries) {
            this.entries = List.copyOf(entries);
        }

        @Override
        public List<RetentionEntry> list(Path dir) {
            return entries;
        }

        @Override
        public void delete(RetentionEntry entry) {
            deleted.add(entry.path());
        }

        @Override
        public void archive(RetentionEntry entry, Path archiveDir) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MemoryLedger implements IngestionLedger {
        private final List<IngestionRecord> records;

        private MemoryLedger(List<IngestionRecord> records) {
            this.records = List.copyOf(records);
        }

        @Override
        public Optional<IngestionRecord> find(SourceKey key) {
            return records.stream().filter(record -> record.key().equals(key)).findFirst();
        }

        @Override
        public void markClaimed(SourceUnit unit) {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(SourceKey key, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IngestionRecord> findIncomplete() {
            return List.of();
        }

        @Override
        public List<IngestionRecord> findReadyForAggregation() {
            return List.of();
        }

        @Override
        public List<IngestionRecord> findAggregated() {
            return records.stream()
                    .filter(record -> record.status() == IngestionStatus.AGGREGATED)
                    .toList();
        }
    }
}
