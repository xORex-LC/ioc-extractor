package com.iocextractor.application.tck.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(ledger.markClaimed(unit)).isEqualTo(IngestionLedgerTransition.APPLIED);
        assertRecord(ledger, unit.key(), IngestionStatus.CLAIMED, null, null);

        assertThat(ledger.markSourceArchived(unit.key(), path("done/alpha.html")))
                .isEqualTo(IngestionLedgerTransition.APPLIED);
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
    void archive_reports_missing_record_without_creating_one() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceKey missing = key("missing-transition");

        assertThat(ledger.markSourceArchived(missing, path("done/missing.html")))
                .isEqualTo(IngestionLedgerTransition.MISSING);
        assertThat(ledger.find(missing)).isEmpty();
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

    @Test
    void repeatedAndOppositeTerminalTransitionsAreMonotonic() {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit archived = unit("terminal-archived");
        SourceKey failed = key("terminal-failed");

        assertThat(ledger.markClaimed(archived)).isEqualTo(IngestionLedgerTransition.APPLIED);
        assertThat(ledger.markClaimed(archived)).isEqualTo(IngestionLedgerTransition.ALREADY_APPLIED);
        assertThat(ledger.markSourceArchived(archived.key(), path("done/terminal-archived.html")))
                .isEqualTo(IngestionLedgerTransition.APPLIED);
        assertThat(ledger.markSourceArchived(archived.key(), path("done/other.html")))
                .isEqualTo(IngestionLedgerTransition.ALREADY_APPLIED);
        assertThat(ledger.markFailed(archived.key(), "must not overwrite"))
                .isEqualTo(IngestionLedgerTransition.CONFLICT);

        assertThat(ledger.markFailed(failed, "pre-claim failure"))
                .isEqualTo(IngestionLedgerTransition.APPLIED);
        assertThat(ledger.markFailed(failed, "must preserve first failure"))
                .isEqualTo(IngestionLedgerTransition.ALREADY_APPLIED);
        assertThat(ledger.markClaimed(unit("terminal-failed")))
                .isEqualTo(IngestionLedgerTransition.CONFLICT);

        assertThat(ledger.find(archived.key())).get()
                .extracting(record -> record.status())
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(ledger.find(failed)).get().satisfies(record -> {
            assertThat(record.status()).isEqualTo(IngestionStatus.FAILED);
            assertThat(record.reason()).isEqualTo("pre-claim failure");
        });
    }

    @Test
    void competingTerminalTransitionsHaveExactlyOneWinner() throws Exception {
        IngestionLedger ledger = createLedger(FIXED_CLOCK);
        SourceUnit unit = unit("terminal-race");
        assertThat(ledger.markClaimed(unit)).isEqualTo(IngestionLedgerTransition.APPLIED);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        List<IngestionLedgerTransition> transitions;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var archive = executor.submit(() -> {
                ready.countDown();
                await(start);
                return ledger.markSourceArchived(unit.key(), path("done/terminal-race.html"));
            });
            var fail = executor.submit(() -> {
                ready.countDown();
                await(start);
                return ledger.markFailed(unit.key(), "terminal race");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            transitions = List.of(
                    archive.get(5, TimeUnit.SECONDS),
                    fail.get(5, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }

        assertThat(transitions)
                .containsExactlyInAnyOrder(
                        IngestionLedgerTransition.APPLIED,
                        IngestionLedgerTransition.CONFLICT);
        assertThat(ledger.find(unit.key())).get()
                .extracting(record -> record.status())
                .isIn(IngestionStatus.SOURCE_ARCHIVED, IngestionStatus.FAILED);
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
