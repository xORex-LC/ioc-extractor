package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.tck.ingest.IngestionLedgerContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FileIngestionLedgerTest extends IngestionLedgerContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected IngestionLedger createLedger(Clock clock) {
        return new FileIngestionLedger(tempDir, clock);
    }

    @Test
    void persists_status_transitions_and_lists_only_claimed_records_as_incomplete() {
        var clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC);
        var ledger = new FileIngestionLedger(tempDir, clock);
        var key = new SourceKey("ABC123");
        var unit = new SourceUnit(key, Path.of("inbox/source.html"),
                Path.of("processing/source.html"), Instant.parse("2026-06-22T00:00:00Z"));

        ledger.markClaimed(unit);

        assertThat(ledger.findIncomplete()).hasSize(1);
        assertThat(ledger.find(key)).get()
                .extracting("status")
                .isEqualTo(IngestionStatus.CLAIMED);

        ledger.markSourceArchived(key, Path.of("done/source.html"));

        assertThat(ledger.find(key)).get()
                .extracting("status")
                .isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(ledger.findIncomplete()).isEmpty();
    }
}
