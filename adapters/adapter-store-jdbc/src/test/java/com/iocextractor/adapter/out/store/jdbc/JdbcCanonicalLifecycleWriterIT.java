package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcCanonicalLifecycleWriterIT {

    private static final Instant START = Instant.parse("2026-08-16T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(1);
    private static final Clock ALLOCATOR_CLOCK = Clock.fixed(START, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private List<DataframeArtifactSchema> schemas;
    private MutableTimeSource timeSource;

    @BeforeEach
    void setUp() {
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + tempDir.resolve("lifecycle.db"),
                        "low-memory", 6, 6));
        schemas = List.of(schema("masks"), schema("hashes"));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        activate();
        timeSource = new MutableTimeSource(START);
    }

    @AfterEach
    void close() {
        dataSource.close();
    }

    @Test
    void last_artifact_atomically_publishes_only_a_complete_count_validated_receipt() throws Exception {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);
        ConfirmationReceiptContext receipt = receipt("receipt-two-artifacts", 2);

        writer.confirm(command("observation-two-artifacts", "masks", receipt,
                row("mask-a", "a.example")));

        assertThat(queryString("SELECT state FROM confirmation_receipt WHERE receipt_id = 'receipt-two-artifacts'"))
                .isEqualTo("STAGING");
        assertThat(queryLong("SELECT COUNT(*) FROM confirmation_receipt_artifact "
                + "WHERE receipt_id = 'receipt-two-artifacts'"))
                .isOne();

        writer.confirm(command("observation-two-artifacts", "hashes", receipt,
                row("hash-a", "AABB")));

        assertThat(queryString("SELECT state FROM confirmation_receipt WHERE receipt_id = 'receipt-two-artifacts'"))
                .isEqualTo("COMPLETE");
        assertThat(queryLong("SELECT row_count FROM confirmation_receipt "
                + "WHERE receipt_id = 'receipt-two-artifacts'"))
                .isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM masks_receipt_rows "
                + "WHERE receipt_id = 'receipt-two-artifacts'"))
                .isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM hashes_receipt_rows "
                + "WHERE receipt_id = 'receipt-two-artifacts'"))
                .isOne();
    }

    @Test
    void zero_row_artifact_still_publishes_its_complete_receipt_marker() throws Exception {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);

        var result = writer.confirm(command(
                "observation-empty", "masks", receipt("receipt-empty", 1)));

        assertThat(result.confirmedRecords()).isZero();
        assertThat(result.artifactRevision()).isZero();
        assertThat(queryString("SELECT state FROM confirmation_receipt WHERE receipt_id = 'receipt-empty'"))
                .isEqualTo("COMPLETE");
        assertThat(queryLong("SELECT row_count FROM confirmation_receipt_artifact "
                + "WHERE receipt_id = 'receipt-empty' AND artifact = 'masks'"))
                .isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM masks_receipt_rows "
                + "WHERE receipt_id = 'receipt-empty'"))
                .isZero();
    }

    @Test
    void complete_receipt_is_typed_policy_scoped_and_removed_with_terminal_observation() throws Exception {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);
        ConfirmationReceiptContext receipt = receipt("receipt-replayable", 2);
        writer.confirm(command(
                "observation-replayable", "masks", receipt, row("mask-a", "a.example")));
        writer.confirm(command(
                "observation-replayable", "hashes", receipt, row("hash-a", "AABB")));
        var store = new JdbcConfirmationReceiptStore(dataSource, schemas, Duration.ofDays(30));

        var snapshot = store.findComplete(
                "source-key", "policy-v1", EffectiveTime.at(START)).orElseThrow();

        assertThat(snapshot.artifacts()).extracting(artifact -> artifact.artifactName())
                .containsExactly("hashes", "masks");
        assertThat(snapshot.artifacts()).allSatisfy(artifact -> {
            assertThat(artifact.header())
                    .containsExactly("id", "value", "source", "time_first_seen", "time_last_seen");
            assertThat(artifact.records()).singleElement().satisfies(record -> {
                assertThat(record.preparedRow().template().value("id")).isNull();
                assertThat(record.preparedRow().template().value("source")).isEqualTo("feed-name");
                assertThat(record.preparedRow().template().value("time_first_seen")).isNull();
            });
        });
        assertThat(store.findComplete(
                "source-key", "policy-v2", EffectiveTime.at(START))).isEmpty();

        store.markTerminal(
                new ObservationId("observation-replayable"),
                EffectiveTime.at(START),
                Duration.ofDays(30));
        var purged = store.purgeExpired(EffectiveTime.at(START.plus(Duration.ofDays(31))), 10);

        assertThat(purged.purged()).isOne();
        assertThat(purged.moreEligible()).isFalse();
        assertThat(queryLong("SELECT COUNT(*) FROM confirmation_receipt")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM canonical_observation")).isZero();

        store.markTerminal(
                new ObservationId("attempt-without-canonical-commit"),
                EffectiveTime.at(START),
                Duration.ofDays(30));
    }

    @Test
    void failed_canonical_transaction_rolls_back_confirmation_and_receipt_but_burns_reserved_ids()
            throws Exception {
        execute("""
                CREATE TRIGGER reject_boom BEFORE INSERT ON masks
                WHEN NEW.value = 'boom'
                BEGIN
                    SELECT RAISE(ABORT, 'forced canonical failure');
                END
                """);
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-failed", "masks", receipt("receipt-failed", 1), row("failed", "boom"))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Failed lifecycle confirmation");

        assertThat(queryLong("SELECT COUNT(*) FROM masks")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM masks_sources")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM canonical_observation_commit")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM confirmation_receipt")).isZero();
        assertThat(queryLong("SELECT next_value FROM artifact_id_allocator WHERE artifact = 'masks'"))
                .isEqualTo(2);
        assertThat(queryLong("SELECT next_value FROM lifecycle_id_allocator WHERE singleton_id = 1"))
                .isEqualTo(2);

        execute("DROP TRIGGER reject_boom");
        writer.confirm(command(
                "observation-success", "masks", receipt("receipt-success", 1), row("success", "ok")));

        assertThat(queryLong("SELECT id FROM masks WHERE row_key = 'success'"))
                .isEqualTo(2);
        assertThat(queryLong("SELECT _lifecycle_id FROM masks WHERE row_key = 'success'"))
                .isEqualTo(2);
        assertThat(queryString("SELECT state FROM confirmation_receipt WHERE receipt_id = 'receipt-success'"))
                .isEqualTo("COMPLETE");
    }

    @Test
    void confirmation_winning_write_ownership_is_not_lost_to_concurrent_expiry() throws Exception {
        JdbcCanonicalLifecycleWriter setupWriter = writer(JdbcLifecycleTransactionObserver.NOOP);
        setupWriter.confirm(command(
                "observation-initial", "masks", receipt("receipt-initial", 1), row("race", "old")));
        long oldLifecycle = queryLong("SELECT _lifecycle_id FROM masks WHERE row_key = 'race'");
        timeSource.set(START.plus(TTL));

        CountDownLatch confirmationOwnsWrite = new CountDownLatch(1);
        CountDownLatch expiryAttempted = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);
        JdbcLifecycleTransactionObserver writerObserver = (phase, operation, artifact) -> {
            if (operation == JdbcLifecycleTransactionObserver.Operation.CONFIRM
                    && phase == JdbcLifecycleTransactionObserver.Phase.AFTER_WRITE_OWNERSHIP) {
                confirmationOwnsWrite.countDown();
                await(releaseConfirmation, "confirmation release");
            }
        };
        JdbcLifecycleTransactionObserver expiryObserver = (phase, operation, artifact) -> {
            if (operation == JdbcLifecycleTransactionObserver.Operation.EXPIRE
                    && phase == JdbcLifecycleTransactionObserver.Phase.BEFORE_WRITE_OWNERSHIP) {
                expiryAttempted.countDown();
            }
        };
        JdbcCanonicalLifecycleWriter racingWriter = writer(writerObserver);
        JdbcExpiredArtifactStore expiry = new JdbcExpiredArtifactStore(dataSource, schemas, expiryObserver);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirmation = executor.submit(() -> racingWriter.confirm(command(
                    "observation-race-confirm", "masks", receipt("receipt-race-confirm", 1),
                    row("race", "new"))));
            assertThat(confirmationOwnsWrite.await(5, TimeUnit.SECONDS)).isTrue();
            var expiration = executor.submit(() -> expiry.expireDue(
                    "masks", EffectiveTime.at(START.plus(TTL)), 10));
            assertThat(expiryAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseConfirmation.countDown();

            assertThat(confirmation.get(5, TimeUnit.SECONDS).restarted()).isOne();
            assertThat(expiration.get(5, TimeUnit.SECONDS).expired()).isZero();
        }
        assertThat(queryLong("SELECT COUNT(*) FROM masks")).isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM masks_history")).isOne();
        assertThat(queryLong("SELECT _lifecycle_id FROM masks WHERE row_key = 'race'"))
                .isNotEqualTo(oldLifecycle);
    }

    @Test
    void expiry_winning_write_ownership_closes_old_lifecycle_before_confirmation_creates_new_one()
            throws Exception {
        JdbcCanonicalLifecycleWriter setupWriter = writer(JdbcLifecycleTransactionObserver.NOOP);
        long initialRevision = setupWriter.confirm(command(
                "observation-initial", "masks", receipt("receipt-initial", 1), row("race", "old")))
                .artifactRevision();
        long oldLifecycle = queryLong("SELECT _lifecycle_id FROM masks WHERE row_key = 'race'");
        timeSource.set(START.plus(TTL));

        CountDownLatch expiryOwnsWrite = new CountDownLatch(1);
        CountDownLatch confirmationAttempted = new CountDownLatch(1);
        CountDownLatch releaseExpiry = new CountDownLatch(1);
        JdbcLifecycleTransactionObserver expiryObserver = (phase, operation, artifact) -> {
            if (operation == JdbcLifecycleTransactionObserver.Operation.EXPIRE
                    && phase == JdbcLifecycleTransactionObserver.Phase.AFTER_WRITE_OWNERSHIP) {
                expiryOwnsWrite.countDown();
                await(releaseExpiry, "expiry release");
            }
        };
        JdbcExpiredArtifactStore expiry = new JdbcExpiredArtifactStore(dataSource, schemas, expiryObserver);
        JdbcCanonicalLifecycleWriter racingWriter = writer(JdbcLifecycleTransactionObserver.NOOP);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiration = executor.submit(() -> expiry.expireDue(
                    "masks", EffectiveTime.at(START.plus(TTL)), 10));
            assertThat(expiryOwnsWrite.await(5, TimeUnit.SECONDS)).isTrue();
            var confirmation = executor.submit(() -> {
                confirmationAttempted.countDown();
                return racingWriter.confirm(command(
                        "observation-race-confirm", "masks", receipt("receipt-race-confirm", 1),
                        row("race", "new")));
            });
            assertThat(confirmationAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseExpiry.countDown();

            assertThat(expiration.get(5, TimeUnit.SECONDS).expired()).isOne();
            var confirmed = confirmation.get(5, TimeUnit.SECONDS);
            assertThat(confirmed.created()).isOne();
            assertThat(confirmed.artifactRevision())
                    .isEqualTo(initialRevision + 1);
        }
        assertThat(queryLong("SELECT COUNT(*) FROM masks")).isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM masks_history")).isOne();
        assertThat(queryLong("SELECT _lifecycle_id FROM masks WHERE row_key = 'race'"))
                .isNotEqualTo(oldLifecycle);
    }

    @Test
    void lifecycle_write_preserves_public_order_and_nullable_business_times() {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);

        writer.confirm(command(
                "observation-null-times", "masks", receipt("receipt-null-times", 1),
                rowWithIdSlot("nullable-times", "example.test", Optional.of("id"), " ")));

        var snapshot = new JdbcActiveArtifactReader(dataSource, schemas)
                .loadActive("masks", EffectiveTime.at(START));
        assertThat(snapshot.header())
                .containsExactly("id", "value", "source", "time_first_seen", "time_last_seen");
        assertThat(snapshot.records()).singleElement().satisfies(record -> {
            assertThat(record.row().value("id")).isEqualTo("1");
            assertThat(record.row().value("time_first_seen")).isNull();
            assertThat(record.row().value("time_last_seen")).isNull();
        });
    }

    @Test
    void confirmation_fails_closed_on_invalid_persisted_lifecycle_order() throws Exception {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);
        writer.confirm(command(
                "observation-valid", "masks", receipt("receipt-valid", 1),
                row("corrupt-order", "example.test")));
        execute("""
                UPDATE masks
                SET _last_confirmed_at_epoch_ms = _valid_until_epoch_ms
                WHERE row_key = 'corrupt-order'
                """);

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-rejected", "masks", receipt("receipt-rejected", 1),
                row("corrupt-order", "example.test"))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("invalid ordered metadata");

        assertThat(queryLong("SELECT COUNT(*) FROM canonical_observation_commit "
                + "WHERE observation_id = 'observation-rejected'"))
                .isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM confirmation_receipt "
                + "WHERE receipt_id = 'receipt-rejected'"))
                .isZero();
    }

    @Test
    void constructor_rejects_ambiguous_schema_and_public_id_allocator_catalogs() {
        assertThatThrownBy(() -> new JdbcCanonicalLifecycleWriter(
                dataSource,
                List.of(schema("masks"), schema("masks")),
                List.of(),
                timeSource,
                new FixedRecordValidityPolicy(TTL),
                ALLOCATOR_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate dataframe artifact schema: masks");

        assertThatThrownBy(() -> new JdbcCanonicalLifecycleWriter(
                dataSource,
                schemas,
                List.of(new ArtifactIdAllocatorDefinition(
                        "unknown", ArtifactIdStrategy.ASCENDING, 1, 1)),
                timeSource,
                new FixedRecordValidityPolicy(TTL),
                ALLOCATOR_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match an id-bearing artifact: unknown");

        var definition = new ArtifactIdAllocatorDefinition(
                "masks", ArtifactIdStrategy.ASCENDING, 1, 1);
        assertThatThrownBy(() -> new JdbcCanonicalLifecycleWriter(
                dataSource,
                schemas,
                List.of(definition, definition),
                timeSource,
                new FixedRecordValidityPolicy(TTL),
                ALLOCATOR_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate public id allocator definition: masks");
    }

    @Test
    void confirmation_rejects_commands_that_violate_the_artifact_schema_contract() {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-unknown", "unknown", receipt("receipt-unknown", 1))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Unknown dataframe artifact: unknown");

        CanonicalArtifactConfirmation wrongHeader = new CanonicalArtifactConfirmation(
                new ObservationId("observation-wrong-header"),
                "source-key",
                receipt("receipt-wrong-header", 1),
                "masks",
                List.of("id", "value"),
                List.of());
        assertThatThrownBy(() -> writer.confirm(wrongHeader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header does not match artifact schema: masks");

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-missing-id-slot", "masks", receipt("receipt-missing-id-slot", 1),
                rowWithIdSlot("missing-id-slot", "value", Optional.empty(), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public-id slot does not match artifact schema: masks");

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-wrong-id-slot", "masks", receipt("receipt-wrong-id-slot", 1),
                rowWithIdSlot("wrong-id-slot", "value", Optional.of("value"), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public-id slot does not match artifact schema: masks");

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-supplied-id", "masks", receipt("receipt-supplied-id", 1),
                rowWithIdSlot("supplied-id", "value", Optional.of("id"), "7"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Service-owned public id must remain deferred");
    }

    @Test
    void observation_identity_cannot_cross_sources_or_resume_after_terminal_state() {
        JdbcCanonicalLifecycleWriter writer = writer(JdbcLifecycleTransactionObserver.NOOP);
        writer.confirm(command(
                "observation-owned", "masks", receipt("receipt-owned", 1), row("owned", "value")));

        assertThatThrownBy(() -> writer.confirm(commandForSource(
                "observation-owned", "another-source", "masks", receipt("receipt-owned-replay", 1),
                row("owned-replay", "value"))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("already used for another source");

        new JdbcConfirmationReceiptStore(dataSource, schemas, Duration.ofDays(30)).markTerminal(
                new ObservationId("observation-owned"),
                EffectiveTime.at(START),
                Duration.ofDays(30));

        assertThatThrownBy(() -> writer.confirm(command(
                "observation-owned", "hashes", receipt("receipt-terminal", 1),
                row("terminal", "AABB"))))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Canonical observation identity is not writable");
    }

    private JdbcCanonicalLifecycleWriter writer(JdbcLifecycleTransactionObserver observer) {
        return new JdbcCanonicalLifecycleWriter(
                dataSource,
                schemas,
                List.of(
                        new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                        new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                timeSource,
                new FixedRecordValidityPolicy(TTL),
                ALLOCATOR_CLOCK,
                observer);
    }

    private CanonicalArtifactConfirmation command(String observation,
                                                  String artifact,
                                                  ConfirmationReceiptContext receipt,
                                                  CanonicalRecordConfirmation... records) {
        return commandForSource(observation, "source-key", artifact, receipt, records);
    }

    private CanonicalArtifactConfirmation commandForSource(String observation,
                                                           String sourceKey,
                                                           String artifact,
                                                           ConfirmationReceiptContext receipt,
                                                           CanonicalRecordConfirmation... records) {
        return new CanonicalArtifactConfirmation(
                new ObservationId(observation),
                sourceKey,
                receipt,
                artifact,
                List.of("id", "value", "source", "time_first_seen", "time_last_seen"),
                List.of(records));
    }

    private CanonicalRecordConfirmation row(String rowKey, String value) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("id", null);
        values.put("value", value);
        values.put("source", "feed-name");
        values.put("time_first_seen", null);
        values.put("time_last_seen", null);
        return new CanonicalRecordConfirmation(
                new ArtifactRowKey(rowKey),
                new PreparedArtifactRow(ArtifactRow.ordered(values), Optional.of("id")));
    }

    private CanonicalRecordConfirmation rowWithIdSlot(String rowKey,
                                                      String value,
                                                      Optional<String> idColumn,
                                                      String id) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("value", value);
        values.put("source", "feed-name");
        values.put("time_first_seen", null);
        values.put("time_last_seen", null);
        return new CanonicalRecordConfirmation(
                new ArtifactRowKey(rowKey),
                new PreparedArtifactRow(ArtifactRow.ordered(values), idColumn));
    }

    private ConfirmationReceiptContext receipt(String id, int expectedArtifacts) {
        return new ConfirmationReceiptContext(
                new ConfirmationReceiptId(id), "policy-v1", expectedArtifacts, Duration.ofDays(30));
    }

    private DataframeArtifactSchema schema(String artifact) {
        return new DataframeArtifactSchema(artifact, List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn("value", "TEXT"),
                new DataframeColumn("source", "TEXT"),
                new DataframeColumn("time_first_seen", "TEXT"),
                new DataframeColumn("time_last_seen", "TEXT")));
    }

    private void activate() {
        var control = new JdbcLifecycleControlStore(dataSource, schemas);
        var disabled = control.load();
        var activating = disabled.beginActivation("fixed-1h-v1");
        assertThat(control.compareAndSet(disabled, activating)).isTrue();
        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(START)))).isTrue();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(description + " barrier timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " barrier interrupted", e);
        }
    }

    private static final class MutableTimeSource implements LifecycleTimeSource {

        private final AtomicReference<Instant> instant;
        private final AtomicInteger samples;

        private MutableTimeSource(Instant instant) {
            this.instant = new AtomicReference<>(instant);
            this.samples = new AtomicInteger();
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public EffectiveTime now() {
            samples.incrementAndGet();
            return EffectiveTime.at(instant.get());
        }
    }
}
