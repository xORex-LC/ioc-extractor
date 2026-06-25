package com.iocextractor.application.tck.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable behavior contract for {@link IngestionLedger} adapters. Lives in this
 * dedicated TCK module (a normal jar) rather than a {@code test-jar}: adapters add
 * it as a {@code test}-scoped dependency and subclass it, with no package-phase
 * coupling, inherited test toolkit, and a clean "only the contract is exported" boundary.
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
    void persists_claim_archive_chain() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit unit = unit("alpha");

        ledger.markClaimed(unit);
        assertRecord(ledger, unit.key(), IngestionStatus.CLAIMED, null, null);

        ledger.markSourceArchived(unit.key(), path("done/alpha.html"));
        assertRecord(ledger, unit.key(), IngestionStatus.SOURCE_ARCHIVED, path("done/alpha.html"), null);
    }

    @Test
    void lists_only_recoverable_incomplete_records() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit claimed = unit("claimed");
        SourceUnit archived = unit("archived");
        SourceUnit failed = unit("failed");

        ledger.markClaimed(claimed);

        ledger.markClaimed(archived);
        ledger.markSourceArchived(archived.key(), path("done/archived.html"));

        ledger.markClaimed(failed);
        ledger.markFailed(failed.key(), "cannot parse");

        assertThat(ledger.findIncomplete())
                .extracting(record -> record.key().value())
                .containsExactly("claimed");
    }

    @Test
    void archive_requires_existing_record() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceKey missing = key("missing-transition");

        assertThatThrownBy(() -> ledger.markSourceArchived(missing, path("done/missing.html")))
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
        ledger.markFailed(existing.key(), "write failed");

        assertThat(ledger.find(existing.key())).get()
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(IngestionStatus.FAILED);
                    assertThat(record.originalPath()).isEqualTo(existing.originalPath());
                    assertThat(record.processingPath()).isEqualTo(existing.processingPath());
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
                              Path archivedPath,
                              String reason) {
        assertThat(ledger.find(key)).get()
                .satisfies(record -> {
                    assertThat(record.key()).isEqualTo(key);
                    assertThat(record.status()).isEqualTo(status);
                    assertThat(record.originalPath()).isEqualTo(path("inbox/" + key.value() + ".html"));
                    assertThat(record.processingPath()).isEqualTo(path("processing/" + key.value() + ".html"));
                    assertThat(record.archivedPath()).isEqualTo(archivedPath);
                    assertThat(record.detectedAt()).isEqualTo(DETECTED_AT);
                    assertThat(record.updatedAt()).isEqualTo(UPDATED_AT);
                    assertThat(record.reason()).isEqualTo(reason);
                });
    }
}
