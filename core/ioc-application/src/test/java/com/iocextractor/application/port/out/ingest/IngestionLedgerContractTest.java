package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable behavior contract for {@link IngestionLedger} adapters.
 */
public abstract class IngestionLedgerContractTest {

    protected static final Instant DETECTED_AT = Instant.parse("2026-06-22T00:00:00Z");
    protected static final Instant UPDATED_AT = Instant.parse("2026-06-22T00:01:00Z");
    protected static final Clock FIXED_CLOCK = Clock.fixed(UPDATED_AT, ZoneOffset.UTC);

    protected abstract IngestionLedger createLedger(Clock clock);

    @Test
    void returns_empty_for_missing_source_key() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);

        assertThat(ledger.find(key("missing"))).isEmpty();
    }

    @Test
    void persists_full_status_transition_chain() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit unit = unit("alpha");

        ledger.markClaimed(unit);
        assertRecord(ledger, unit.key(), IngestionStatus.CLAIMED, List.of(), null, null);

        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/masks-alpha.csv")));
        assertRecord(ledger, unit.key(), IngestionStatus.PARTITION_WRITTEN,
                List.of(path("partitions/masks-alpha.csv")), null, null);

        ledger.markLedgerRecorded(unit.key());
        assertRecord(ledger, unit.key(), IngestionStatus.LEDGER_RECORDED,
                List.of(path("partitions/masks-alpha.csv")), null, null);

        ledger.markSourceArchived(unit.key(), path("done/alpha.html"));
        assertRecord(ledger, unit.key(), IngestionStatus.SOURCE_ARCHIVED,
                List.of(path("partitions/masks-alpha.csv")), path("done/alpha.html"), null);

        ledger.markAggregated(unit.key());
        assertRecord(ledger, unit.key(), IngestionStatus.AGGREGATED,
                List.of(path("partitions/masks-alpha.csv")), path("done/alpha.html"), null);
    }

    @Test
    void mark_partition_written_replaces_previous_partitions() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit unit = unit("replace");

        ledger.markClaimed(unit);
        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/old.csv")));
        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/new-a.csv"), path("partitions/new-b.csv")));

        assertThat(ledger.find(unit.key())).get()
                .extracting(IngestionRecord::partitions)
                .isEqualTo(List.of(path("partitions/new-a.csv"), path("partitions/new-b.csv")));
    }

    @Test
    void lists_only_recoverable_incomplete_records() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit claimed = unit("claimed");
        SourceUnit partitionWritten = unit("partition-written");
        SourceUnit ledgerRecorded = unit("ledger-recorded");
        SourceUnit archived = unit("archived");
        SourceUnit aggregated = unit("aggregated");
        SourceUnit failed = unit("failed");

        ledger.markClaimed(claimed);

        ledger.markClaimed(partitionWritten);
        ledger.markPartitionWritten(partitionWritten.key(), List.of(path("partitions/partition-written.csv")));

        ledger.markClaimed(ledgerRecorded);
        ledger.markPartitionWritten(ledgerRecorded.key(), List.of(path("partitions/ledger-recorded.csv")));
        ledger.markLedgerRecorded(ledgerRecorded.key());

        ledger.markClaimed(archived);
        ledger.markPartitionWritten(archived.key(), List.of(path("partitions/archived.csv")));
        ledger.markLedgerRecorded(archived.key());
        ledger.markSourceArchived(archived.key(), path("done/archived.html"));

        ledger.markClaimed(aggregated);
        ledger.markPartitionWritten(aggregated.key(), List.of(path("partitions/aggregated.csv")));
        ledger.markLedgerRecorded(aggregated.key());
        ledger.markSourceArchived(aggregated.key(), path("done/aggregated.html"));
        ledger.markAggregated(aggregated.key());

        ledger.markClaimed(failed);
        ledger.markFailed(failed.key(), "cannot parse");

        assertThat(ledger.findIncomplete())
                .extracting(record -> record.key().value())
                .containsExactlyInAnyOrder("claimed", "partition-written", "ledger-recorded");
    }

    @Test
    void lists_only_archived_records_with_partitions_as_ready_for_aggregation() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit ready = unit("ready");
        SourceUnit archivedWithoutPartitions = unit("archived-empty");
        SourceUnit aggregated = unit("already-aggregated");
        SourceUnit failed = unit("failed-ready");

        ledger.markClaimed(ready);
        ledger.markPartitionWritten(ready.key(), List.of(path("partitions/ready.csv")));
        ledger.markLedgerRecorded(ready.key());
        ledger.markSourceArchived(ready.key(), path("done/ready.html"));

        ledger.markClaimed(archivedWithoutPartitions);
        ledger.markLedgerRecorded(archivedWithoutPartitions.key());
        ledger.markSourceArchived(archivedWithoutPartitions.key(), path("done/archived-empty.html"));

        ledger.markClaimed(aggregated);
        ledger.markPartitionWritten(aggregated.key(), List.of(path("partitions/already-aggregated.csv")));
        ledger.markLedgerRecorded(aggregated.key());
        ledger.markSourceArchived(aggregated.key(), path("done/already-aggregated.html"));
        ledger.markAggregated(aggregated.key());

        ledger.markClaimed(failed);
        ledger.markPartitionWritten(failed.key(), List.of(path("partitions/failed-ready.csv")));
        ledger.markLedgerRecorded(failed.key());
        ledger.markSourceArchived(failed.key(), path("done/failed-ready.html"));
        ledger.markFailed(failed.key(), "aggregation disabled");

        assertThat(ledger.findReadyForAggregation())
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.key()).isEqualTo(ready.key());
                    assertThat(record.partitions()).containsExactly(path("partitions/ready.csv"));
                });
    }

    @Test
    void status_transitions_other_than_failure_require_existing_record() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceKey missing = key("missing-transition");

        assertThatThrownBy(() -> ledger.markPartitionWritten(missing, List.of(path("partitions/missing.csv"))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ledger.markLedgerRecorded(missing))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ledger.markSourceArchived(missing, path("done/missing.html")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ledger.markAggregated(missing))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void mark_failed_creates_missing_record_and_preserves_existing_context() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceKey missing = key("missing-failed");
        SourceUnit existing = unit("existing-failed");

        ledger.markFailed(missing, "claim failed");

        assertThat(ledger.find(missing)).get()
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(record.originalPath()).isEqualTo(path("unknown"));
                    assertThat(record.processingPath()).isEqualTo(path("unknown"));
                    assertThat(record.reason()).isEqualTo("claim failed");
                });

        ledger.markClaimed(existing);
        ledger.markPartitionWritten(existing.key(), List.of(path("partitions/existing-failed.csv")));
        ledger.markFailed(existing.key(), "write failed");

        assertThat(ledger.find(existing.key())).get()
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(record.originalPath()).isEqualTo(existing.originalPath());
                    assertThat(record.processingPath()).isEqualTo(existing.processingPath());
                    assertThat(record.partitions()).containsExactly(path("partitions/existing-failed.csv"));
                    assertThat(record.reason()).isEqualTo("write failed");
                });
    }

    protected SourceUnit unit(String name) {
        return new SourceUnit(key(name), path("inbox/" + name + ".html"),
                path("processing/" + name + ".html"), DETECTED_AT);
    }

    protected SourceKey key(String value) {
        return new SourceKey(value);
    }

    protected Path path(String value) {
        return Path.of(value);
    }

    private void assertRecord(IngestionLedger ledger,
                              SourceKey key,
                              IngestionStatus status,
                              List<Path> partitions,
                              Path archivedPath,
                              String reason) {
        assertThat(ledger.find(key)).get()
                .satisfies(record -> {
                    assertThat(record.key()).isEqualTo(key);
                    assertThat(record.status()).isEqualTo(status);
                    assertThat(record.originalPath()).isEqualTo(path("inbox/" + key.value() + ".html"));
                    assertThat(record.processingPath()).isEqualTo(path("processing/" + key.value() + ".html"));
                    assertThat(record.archivedPath()).isEqualTo(archivedPath);
                    assertThat(record.partitions()).isEqualTo(partitions);
                    assertThat(record.detectedAt()).isEqualTo(DETECTED_AT);
                    assertThat(record.updatedAt()).isEqualTo(UPDATED_AT);
                    assertThat(record.reason()).isEqualTo(reason);
                });
    }
}
