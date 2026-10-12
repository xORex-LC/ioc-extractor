package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Finite file-backed retention load, separate from the Spring whole-service performance comparison. */
public final class ReceiptPayloadQualification {
    private ReceiptPayloadQualification() { }

    /**
     * Creates large receipts, retires their roots and drains bounded physical debt while retaining active provenance.
     * @param args existing private root, VERSIONED/IMMUTABLE layout, rows per receipt and cycle count
     * @throws Exception if any storage, consumer oracle or cleanup operation fails
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var layout = JdbcReceiptPayloadLayout.valueOf(args[1]);
        int count = Integer.parseInt(args[2]);
        int cycles = Integer.parseInt(args[3]);
        if (count < 1 || cycles < 1 || count > 10_000 || cycles > 5) { throw new IllegalArgumentException("Unbounded receipt probe"); }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        System.out.println("cycle,layout,rows,read_ns,retire_ns,retire_allocated_bytes,retire_effects,cleanup_ns,cleanup_pages,removed,peak_payload_rows,remaining_payload_rows,wal_pinned_bytes,wal_drained_bytes,db_bytes,active_rows");
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, layout)) {
            for (int cycle = 0; cycle < cycles; cycle++) {
                String key = "cycle-" + cycle;
                f.backend.confirmations().confirm(ImmutableReceiptPayloadIT.command(f, key, key, "masks", 1, count));
                long started = System.nanoTime();
                var receipt = f.backend.receipts(Duration.ofDays(30)).findComplete(key, "receipt-policy",
                        EffectiveTime.at(StagedPublicationFixture.NOW)).orElseThrow();
                try (var rows = receipt.artifacts().getFirst().records().open()) {
                    int ordinal = 0;
                    while (rows.next()) {
                        assertThat(rows.value().preparedRow().orderedFieldPositions()).containsEntry("source",
                                new com.iocextractor.application.observation.OccurrencePosition(ordinal));
                        assertThat(rows.value().preparedRow().template().value("mask")).isEqualTo(key + "-" + ordinal++);
                    }
                    assertThat(ordinal).isEqualTo(count);
                }
                long read = System.nanoTime() - started;
                long effectsBefore = effects(f);
                long retire;
                long retirementEffects;
                long allocations;
                long peak;
                long cleanup;
                long walPinned;
                int pages = 0;
                int removed = 0;
                try (var pinned = receipt.artifacts().getFirst().records().open()) {
                    assertThat(pinned.next()).isTrue();
                    long bytesBefore = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    started = System.nanoTime();
                    assertThat(f.backend.receipts(Duration.ofDays(30)).purgeExpired(
                            EffectiveTime.at(StagedPublicationFixture.NOW.plus(Duration.ofDays(31))), 1).purged()).isOne();
                    retire = System.nanoTime() - started;
                    allocations = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytesBefore;
                    retirementEffects = effects(f) - effectsBefore;
                    assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isEqualTo(cycle);
                    peak = payload(f);
                    // Continue publication after retirement, before reclaiming its older payload.
                    f.backend.confirmations().confirm(f.document("z-live-" + cycle, "live-" + cycle + ".example", "live"));
                    started = System.nanoTime();
                    for (String family : List.of("masks_receipt_rows", "confirmation_receipt_field_position",
                            "confirmation_receipt_artifact", "confirmation_receipt")) {
                        boolean wrapped = false;
                        for (int scan = 0; scan < count / 32 + 20; scan++) {
                            var page = f.backend.cleanup().scanVersions(family, 128);
                            assertThat(page.scanned()).isLessThanOrEqualTo(128);
                            assertThat(page.removed()).isLessThanOrEqualTo(page.scanned());
                            removed += page.removed(); pages++;
                            if (wrapped && page.wrapped()) { break; }
                            wrapped = page.wrapped();
                        }
                    }
                    cleanup = System.nanoTime() - started;
                    checkpoint(f, "PASSIVE");
                    walPinned = size(root.resolve("dataframe.db-wal"));
                    int readPinned = 1;
                    while (pinned.next()) { readPinned++; }
                    assertThat(readPinned).isEqualTo(count);
                }
                assertThat(payload(f)).isEqualTo(cycle + 1);
                assertThat(f.number("SELECT COUNT(*) FROM confirmation_receipt")).isEqualTo(cycle + 1);
                assertThat(f.number("SELECT COUNT(*) FROM masks_sources")).isEqualTo((long) (count + 1) * (cycle + 1));
                checkpoint(f, "TRUNCATE");
                System.out.printf(java.util.Locale.ROOT, "%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        cycle, layout, count, read, retire, allocations, retirementEffects, cleanup, pages, removed, peak, payload(f),
                        walPinned, size(root.resolve("dataframe.db-wal")), size(root.resolve("dataframe.db")),
                        f.number("SELECT COUNT(*) FROM masks"));
            }
            long catchupAt = System.nanoTime();
            for (int i = 0; i < cycles; i++) {
                assertThat(f.backend.receipts(Duration.ofDays(30)).purgeExpired(
                        EffectiveTime.at(StagedPublicationFixture.NOW.plus(Duration.ofDays(31))), 1).purged()).isOne();
            }
            for (String family : List.of("masks_receipt_rows", "confirmation_receipt_field_position",
                    "confirmation_receipt_artifact", "confirmation_receipt")) {
                for (int i = 0; i < 4; i++) { f.backend.cleanup().scanVersions(family, 128); }
            }
            assertThat(payload(f)).isZero();
            assertThat(f.number("SELECT COUNT(*) FROM masks_sources")).isEqualTo((long) (count + 1) * cycles);
            checkpoint(f, "TRUNCATE");
            System.out.println("CATCHUP_NS=" + (System.nanoTime() - catchupAt) + " PAYLOAD_DEBT=0 ACTIVE_ROWS="
                    + f.number("SELECT COUNT(*) FROM masks"));
        }
    }

    private static long effects(StagedPublicationFixture f) throws Exception {
        return f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'");
    }
    private static long payload(StagedPublicationFixture f) throws Exception {
        return f.number("SELECT (SELECT COUNT(*) FROM publication_version_masks_receipt_rows)"
                + "+(SELECT COUNT(*) FROM publication_version_confirmation_receipt_field_position)");
    }
    private static void checkpoint(StagedPublicationFixture f, String mode) throws Exception {
        try (var c = f.dataframe.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("PRAGMA wal_checkpoint(" + mode + ")")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }
    private static long size(Path path) throws Exception { return Files.exists(path) ? Files.size(path) : 0; }
}
