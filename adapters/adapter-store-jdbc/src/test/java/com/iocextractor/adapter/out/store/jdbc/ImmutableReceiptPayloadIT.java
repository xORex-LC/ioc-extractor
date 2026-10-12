package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.tck.junit.IntegrationTest;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The physical experiment retains the ordinary receipt contract and bounded parent retirement. */
@IntegrationTest
@Timeout(60)
class ImmutableReceiptPayloadIT {
    @TempDir Path root;

    @ParameterizedTest
    @EnumSource(JdbcReceiptPayloadLayout.class)
    void stagingPayloadCompletesAcrossArtifactsAndKeepsOrderedOrigins(JdbcReceiptPayloadLayout layout) throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, layout)) {
            var masks = command(f, "multi", "shared", "masks", 2, 130);
            var hashes = command(f, "multi", "shared", "hashes", 2, 130);
            f.backend.confirmations().confirm(masks);
            assertThat(f.text("SELECT state FROM confirmation_receipt")).isEqualTo("STAGING");
            assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isEqualTo(130);
            assertThat(f.backend.receipts(Duration.ofDays(30)).findComplete("shared", "receipt-policy", now())).isEmpty();

            f.backend.confirmations().confirm(hashes);
            var receipt = f.backend.receipts(Duration.ofDays(30)).findComplete("shared", "receipt-policy", now()).orElseThrow();
            assertThat(receipt.artifacts()).hasSize(2);
            for (var artifact : receipt.artifacts()) {
                try (var rows = artifact.records().open()) {
                    for (int ordinal = 0; ordinal < 130; ordinal++) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.value().preparedRow().template().value("description")).isNull();
                        assertThat(rows.value().preparedRow().orderedFieldPositions()).containsEntry("source", new OccurrencePosition(ordinal));
                    }
                    assertThat(rows.next()).isFalse();
                }
            }
            assertThat(f.number("SELECT COUNT(*) FROM masks")).isEqualTo(130);
            assertThat(f.number("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'")).isEqualTo(131);
            var before = f.facts("masks_receipt_rows");
            f.backend.confirmations().confirm(masks);
            assertThat(f.facts("masks_receipt_rows")).isEqualTo(before);
            assertThat(f.number("SELECT COUNT(*) FROM publication_version_masks_receipt_rows")).isEqualTo(130);
        }
    }

    @Test
    void orderedCursorPreservesRowsWithoutPositionsBetweenPositionedRows() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcReceiptPayloadLayout.IMMUTABLE)) {
            var original = command(f, "sparse", "sparse-source", "masks", 1, 3);
            var records = new ArrayList<CanonicalRecordConfirmation>();
            try (var cursor = original.records().open()) {
                while (cursor.next()) {
                    var row = cursor.value();
                    var prepared = row.preparedRow();
                    records.add(records.size() == 1 ? new CanonicalRecordConfirmation(row.rowKey(),
                            new PreparedArtifactRow(prepared.template(), prepared.idColumn(), Map.of())) : row);
                }
            }
            f.backend.confirmations().confirm(new CanonicalArtifactConfirmation(original.observationId(), original.sourceKey(),
                    original.receipt(), original.artifactName(), original.header(), records, original.registration()));
            var receipt = f.backend.receipts(Duration.ofDays(30)).findComplete("sparse-source", "receipt-policy", now()).orElseThrow();
            try (var cursor = receipt.artifacts().getFirst().records().open()) {
                assertThat(cursor.next()).isTrue();
                assertThat(cursor.value().preparedRow().orderedFieldPositions()).containsEntry("source", new OccurrencePosition(0));
                assertThat(cursor.next()).isTrue();
                assertThat(cursor.value().preparedRow().orderedFieldPositions()).isEmpty();
                assertThat(cursor.next()).isTrue();
                assertThat(cursor.value().preparedRow().orderedFieldPositions()).containsEntry("source", new OccurrencePosition(2));
                assertThat(cursor.next()).isFalse();
            }
        }
    }

    @Test
    void parentRetirementHidesPayloadImmediatelyAndPinnedReaderSurvivesBoundedReclamation() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcReceiptPayloadLayout.IMMUTABLE)) {
            f.backend.confirmations().confirm(command(f, "old", "old-source", "masks", 1, 513));
            var source = f.backend.receipts(Duration.ofDays(30)).findComplete("old-source", "receipt-policy", now()).orElseThrow();
            try (var rows = source.artifacts().getFirst().records().open()) {
                assertThat(rows.next()).isTrue();
                long before = f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'");
                assertThat(f.backend.receipts(Duration.ofDays(30)).purgeExpired(expired(), 1).purged()).isOne();
                assertThat(f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'") - before).isEqualTo(2);
                assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isZero();
                assertThat(f.number("SELECT COUNT(*) FROM confirmation_receipt_field_position")).isZero();
                assertThat(f.number("SELECT COUNT(*) FROM masks_sources")).isEqualTo(513);
                assertThat(f.number("SELECT COUNT(*) FROM masks")).isEqualTo(513);
                reclaim(f, "masks_receipt_rows", 513);
                reclaim(f, "confirmation_receipt_field_position", 513);
                int count = 1;
                while (rows.next()) { count++; }
                assertThat(count).isEqualTo(513);
            }
            assertThat(f.backend.receipts(Duration.ofDays(30)).findComplete("old-source", "receipt-policy", now())).isEmpty();
        }
    }

    @Test
    void receiptIdReuseCannotResurrectOlderPayloadBeforeOrAfterCleanup() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcReceiptPayloadLayout.IMMUTABLE)) {
            f.backend.confirmations().confirm(command(f, "old", "reused", "masks", 1, 3));
            f.backend.receipts(Duration.ofDays(30)).purgeExpired(expired(), 1);
            for (int i = 0; i < 3; i++) { f.backend.cleanup().purgeVersions("confirmation_receipt", 16); }
            assertThat(f.number("SELECT COUNT(*) FROM publication_version_confirmation_receipt WHERE deleted=1")).isOne();
            f.backend.confirmations().confirm(command(f, "new", "reused", "masks", 1, 2));
            assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isEqualTo(2);
            assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows WHERE mask LIKE 'old%'")).isZero();
            for (String family : List.of("masks_receipt_rows", "confirmation_receipt_field_position", "confirmation_receipt")) {
                for (int i = 0; i < 3; i++) { f.backend.cleanup().purgeVersions(family, 16); }
            }
            assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isEqualTo(2);
            assertThat(f.number("SELECT COUNT(*) FROM publication_version_confirmation_receipt WHERE deleted=1")).isZero();
        }
    }

    @Test
    void hydratedParentRetirementPublishesNoChildTombstonesAndKeepsCanonicalOrigins() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcReceiptPayloadLayout.IMMUTABLE)) {
            f.backend.confirmations().confirm(command(f, "hydrated", "retired", "masks", 1, 3));
            long before = f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'");

            var result = new JdbcStagedControl(f.backend).execute(Set.of("confirmation-receipt", "canonical-observation"),
                    JdbcWriterAdmission.OperationClass.MAINTENANCE, (selection, base, slots) -> {
                        for (String table : List.of("confirmation_receipt", "confirmation_receipt_artifact",
                                "confirmation_receipt_field_position", "masks_receipt_rows")) {
                            base.copy(table, "b.receipt_id=?", List.of("retired"));
                        }
                    }, (working, context, asOf) -> new JdbcConfirmationReceiptStore(working, f.schemas, Duration.ofDays(30))
                            .purgeExpired(expired(), 1));

            assertThat(result.purged()).isOne();
            assertThat(f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'") - before).isEqualTo(2);
            assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isZero();
            assertThat(f.number("SELECT COUNT(*) FROM confirmation_receipt_field_position")).isZero();
            assertThat(f.number("SELECT COUNT(*) FROM publication_version_masks_receipt_rows")).isEqualTo(3);
            assertThat(f.number("SELECT COUNT(*) FROM masks_sources")).isEqualTo(3);
            reclaim(f, "masks_receipt_rows", 3);
            reclaim(f, "confirmation_receipt_field_position", 3);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void layoutReadPreservesCloseFailuresWithoutLeakingItsOwnedJdbcResources(boolean layoutQuery) throws Exception {
        var closes = new ArrayList<String>();
        int[] queries = {0};
        var connection = stub(java.sql.Connection.class, (proxy, method, args) -> {
            if (!method.getName().equals("createStatement")) { throw new UnsupportedOperationException(method.getName()); }
            int query = queries[0]++;
            boolean failing = query == (layoutQuery ? 1 : 0);
            var rows = stub(java.sql.ResultSet.class, (rowProxy, rowMethod, rowArgs) -> switch (rowMethod.getName()) {
                case "next" -> query == 0 ? layoutQuery : true;
                case "getString" -> "IMMUTABLE";
                case "close" -> {
                    closes.add("cursor-" + query);
                    if (failing) { throw new SQLException("cursor close failed"); }
                    yield null;
                }
                default -> throw new UnsupportedOperationException(rowMethod.getName());
            });
            return stub(java.sql.Statement.class, (statementProxy, statementMethod, statementArgs) -> switch (statementMethod.getName()) {
                case "executeQuery" -> rows;
                case "close" -> {
                    closes.add("statement-" + query);
                    if (failing) { throw new SQLException("statement close failed"); }
                    yield null;
                }
                default -> throw new UnsupportedOperationException(statementMethod.getName());
            });
        });

        assertThatThrownBy(() -> JdbcReceiptPayloadLayout.load(connection))
                .isInstanceOf(SQLException.class).hasMessage("cursor close failed")
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                        .satisfies(suppressed -> assertThat(suppressed).hasMessage("statement close failed")));
        assertThat(closes).containsExactlyElementsOf(layoutQuery
                ? List.of("cursor-0", "statement-0", "cursor-1", "statement-1") : List.of("cursor-0", "statement-0"));
    }

    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler calls) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, calls));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HIDDEN_CHUNK", "SEALED", "PUBLICATION_STAGED", "COMMITTED"})
    void killedDocumentRecoversFromPersistedLayoutWithoutReapplyingCommittedReceipt(String phase) throws Exception {
        StagedPublicationFaultIT.killAt(root, phase, 0, "document", JdbcTypedJournal.CHUNK_POINTER, JdbcReceiptPayloadLayout.IMMUTABLE);
        if (phase.equals("COMMITTED")) {
            try (var paths = java.nio.file.Files.walk(root.resolve("private"))) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { java.nio.file.Files.delete(path); }
            }
        }
        long reopenedAt = System.nanoTime();
        try (var f = new StagedPublicationFixture(root, false, JdbcPublicationObserver.NOOP)) {
            long reopenNanos = System.nanoTime() - reopenedAt;
            assertThat(f.backend.store().receiptPayloads()).isEqualTo(JdbcReceiptPayloadLayout.IMMUTABLE);
            long before = f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'");
            var command = f.document("crash-document", "crash.example", "crash-source");
            if (phase.equals("COMMITTED")) {
                command = new CanonicalArtifactConfirmation(command.observationId(), command.sourceKey(), command.receipt(),
                        command.artifactName(), command.header(), new com.iocextractor.application.port.out.artifact.RowSource<>() {
                            public int size() { return 1; }
                            public com.iocextractor.application.port.out.artifact.RowCursor<CanonicalRecordConfirmation> open() {
                                throw new AssertionError("Committed recovery must not read the lost input");
                            }
                        }, command.registration());
            }
            long recoveryAt = System.nanoTime();
            f.backend.confirmations().confirm(command);
            var receipt = f.backend.receipts(Duration.ofDays(30)).findComplete("crash-source", "fault-policy", now()).orElseThrow();
            try (var rows = receipt.artifacts().getFirst().records().open()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.value().preparedRow().template().value("mask")).isEqualTo("crash.example");
                assertThat(rows.next()).isFalse();
            }
            assertThat(f.number("SELECT COUNT(*) FROM masks")).isEqualTo(2);
            if (phase.equals("COMMITTED")) {
                assertThat(f.number("SELECT SUM(contributions) FROM publication_unit WHERE state='COMMITTED'")).isEqualTo(before);
            }
            System.out.println("RECEIPT_RECOVERY phase=" + phase + " reopen_ns=" + reopenNanos
                    + " finalize_and_read_ns=" + (System.nanoTime() - recoveryAt));
        }
    }

    @Test
    void immutableKeysRetainDifferentOrdinalsAndRejectReplacementWithoutAdvancingChunk() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcReceiptPayloadLayout.IMMUTABLE)) {
            f.seed();
            var table = f.backend.store().tables().stream().filter(t -> t.name().equals("masks_receipt_rows")).findFirst().orElseThrow();
            var values = f.facts("masks_receipt_rows").getFirst();
            var one = new ArrayList<>(values);
            one.set(table.columns().indexOf("ordinal"), 1);
            var two = new ArrayList<>(values);
            two.set(table.columns().indexOf("ordinal"), 2);
            var chunk = new JdbcTypedPublicationChunk(f.backend.store().artifacts());
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(table, one, null, false, false), false)).isTrue();
            assertThat(chunk.add(new JdbcPrivateChanges.Effect(table, two, null, false, false), false)).isTrue();
            var protocol = new JdbcPublicationProtocol();
            var tx = new JdbcPublicationTransactions(f.dataframe, f.backend.configuration().admission());
            var attempt = tx.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> protocol.begin(c, "payload-test", "test-pins",
                    new JdbcPublicationProtocol.Manifest(1, 2, Long.MAX_VALUE, Map.of("confirmation-receipt", f.number("SELECT version FROM publication_epoch WHERE scope='confirmation-receipt'")))));
            tx.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
                chunk.append(c, protocol, attempt, "confirmation-receipt", 0, JdbcTypedJournal.CHUNK_POINTER, JdbcReceiptPayloadLayout.IMMUTABLE);
                chunk.append(c, protocol, attempt, "confirmation-receipt", 0, JdbcTypedJournal.CHUNK_POINTER, JdbcReceiptPayloadLayout.IMMUTABLE);
                return null;
            });
            assertThat(f.number("SELECT COUNT(*) FROM publication_version_masks_receipt_rows")).isEqualTo(3);
            assertThat(f.number("SELECT COUNT(*) FROM masks_receipt_rows")).isOne();
            var duplicate = new JdbcTypedPublicationChunk(f.backend.store().artifacts());
            assertThat(duplicate.add(new JdbcPrivateChanges.Effect(table, values, null, false, false), false)).isTrue();
            var broken = tx.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> protocol.begin(c, "duplicate-test", "test-pins",
                    new JdbcPublicationProtocol.Manifest(1, 1, Long.MAX_VALUE, Map.of("confirmation-receipt", f.number("SELECT version FROM publication_epoch WHERE scope='confirmation-receipt'")))));
            assertThatThrownBy(() -> tx.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
                duplicate.append(c, protocol, broken, "confirmation-receipt", 0, JdbcTypedJournal.CHUNK_POINTER, JdbcReceiptPayloadLayout.IMMUTABLE);
                return null;
            })).isInstanceOf(SQLException.class).hasMessageContaining("Duplicate immutable receipt payload");
            assertThat(f.number("SELECT contributions FROM publication_unit WHERE identity='duplicate-test'")).isZero();
            try (var c = f.dataframe.getConnection()) {
                assertThatThrownBy(() -> JdbcImmutableReceiptPayload.retainEffect(c,
                        new JdbcPrivateChanges.Effect(table, values, values, false, false)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("cannot be updated");
                assertThatThrownBy(() -> JdbcImmutableReceiptPayload.retainEffect(c,
                        new JdbcPrivateChanges.Effect(table, values, values, true, false)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("requires parent retirement");
            }
        }
    }

    @Test
    void physicalPinsAndMalformedLayoutFailClosed() throws Exception {
        try (var f = new StagedPublicationFixture(root, true, JdbcPublicationObserver.NOOP, JdbcReceiptPayloadLayout.IMMUTABLE)) {
            f.seed();
            try (var c = f.dataframe.getConnection(); var s = c.createStatement()) {
                s.executeUpdate("UPDATE publication_receipt_layout SET format='VERSIONED'");
                assertThatThrownBy(() -> JdbcStagedCanonicalBackend.reopen(f.backend.configuration(), JdbcPublicationObserver.NOOP))
                        .hasRootCauseMessage("Staged canonical configuration changed; drained migration required");
                s.executeUpdate("UPDATE publication_receipt_layout SET format='unknown'");
                assertThatThrownBy(() -> JdbcReceiptPayloadLayout.load(c)).isInstanceOf(SQLException.class);
                s.executeUpdate("DELETE FROM publication_receipt_layout");
                assertThatThrownBy(() -> JdbcReceiptPayloadLayout.load(c)).isInstanceOf(SQLException.class);
                s.executeUpdate("DROP TABLE publication_receipt_layout");
                assertThat(JdbcReceiptPayloadLayout.load(c)).isEqualTo(JdbcReceiptPayloadLayout.VERSIONED);
            }
        }
    }

    @Test
    void immutableAdmissionDoesNotIncludeAnUnregisteredReceiptFamily() {
        var unaudited = new JdbcPublicationTable("other_receipt_rows", List.of("receipt_id", "ordinal"),
                List.of("receipt_id", "ordinal"), "CREATE TABLE other_receipt_rows(receipt_id TEXT,ordinal INTEGER,PRIMARY KEY(receipt_id,ordinal))");

        assertThat(JdbcReceiptPayloadLayout.IMMUTABLE.contains(unaudited, Set.of("masks", "hashes"))).isFalse();
    }

    static CanonicalArtifactConfirmation command(StagedPublicationFixture f, String observation, String receiptId,
            String artifact, int expected, int count) {
        var schema = f.schemas.stream().filter(s -> s.artifactName().equals(artifact)).findFirst().orElseThrow();
        var records = new ArrayList<CanonicalRecordConfirmation>();
        for (int i = 0; i < count; i++) {
            var values = new LinkedHashMap<String, String>();
            schema.columns().forEach(c -> values.put(c.name(), null));
            values.put(artifact.equals("masks") ? "mask" : "hash", observation + "-" + i);
            values.put("source", receiptId);
            var row = new PreparedArtifactRow(ArtifactRow.ordered(values), Optional.of("id"), Map.of("source", new OccurrencePosition(i)));
            var key = new CanonicalArtifactKeyResolver(f.identities).recordKeyOf(artifact, row.template()).orElseThrow();
            records.add(new CanonicalRecordConfirmation(new ArtifactRowKey(key.keyHash()), row));
        }
        var registration = new JdbcObservationRegistrationStore(f.dataframe, StagedPublicationFixture.CLOCK)
                .registerNew(new ObservationId(observation), com.iocextractor.application.observation.ObservationOrigin.DOCUMENT);
        return new CanonicalArtifactConfirmation(registration.observationId(), receiptId,
                new ConfirmationReceiptContext(new ConfirmationReceiptId(receiptId), "receipt-policy", expected, Duration.ofDays(30)),
                artifact, schema.columns().stream().map(DataframeColumn::name).toList(),
                com.iocextractor.application.port.out.artifact.RowSource.of(records), registration);
    }

    private static EffectiveTime now() { return EffectiveTime.at(StagedPublicationFixture.NOW); }
    private static EffectiveTime expired() { return EffectiveTime.at(StagedPublicationFixture.NOW.plus(Duration.ofDays(31))); }
    private static void reclaim(StagedPublicationFixture f, String family, int expected) {
        int removed = 0;
        for (int pages = 0; removed < expected && pages < 20; pages++) {
            var page = f.backend.cleanup().scanVersions(family, 64);
            assertThat(page.scanned()).isLessThanOrEqualTo(64);
            assertThat(page.removed()).isLessThanOrEqualTo(page.scanned());
            removed += page.removed();
        }
        assertThat(removed).isEqualTo(expected);
    }
}
