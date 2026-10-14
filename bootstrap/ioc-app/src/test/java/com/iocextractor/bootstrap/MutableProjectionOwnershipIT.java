package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection;
import com.iocextractor.adapter.out.store.jdbc.ArtifactIdAllocatorDefinition;
import com.iocextractor.adapter.out.store.jdbc.DataframeArtifactSchema;
import com.iocextractor.adapter.out.store.jdbc.DataframeColumn;
import com.iocextractor.adapter.out.store.jdbc.DataframeFormatMigrations;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactProjectionWorkStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalImportWriter;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalLifecycleWriter;
import com.iocextractor.adapter.out.store.jdbc.JdbcExpiredArtifactStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcImportWorkspace;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleClock;
import com.iocextractor.adapter.out.store.jdbc.JdbcLifecycleControlStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionConvergenceService;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.GenerationOwnedArtifactProjection;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactStreamReader;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real SQLite/CSV qualification of coverage, fault windows and independent writer progress. */
@IntegrationTest
@Timeout(25)
class MutableProjectionOwnershipIT {
    private static final Instant START = Instant.parse("2026-10-04T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(1);
    private static final List<String> HEADER = List.of("id", "mask", "source");
    private static final List<DataframeArtifactSchema> SCHEMAS = List.of(new DataframeArtifactSchema(
            "masks", HEADER.stream().map(DataframeColumn::new).toList()));
    private static final List<ArtifactIdentityDefinition> IDENTITIES = List.of(
            new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1));
    private static final List<ArtifactIdAllocatorDefinition> IDS = List.of(
            new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1));
    @TempDir Path root;
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private HikariDataSource data;
    private Clock clock;
    private JdbcLifecycleClock safeClock;
    private JdbcWriterAdmission admission;
    private JdbcCanonicalLifecycleWriter writer;
    private JdbcCanonicalArtifactRepository reader;
    private JdbcArtifactProjectionWorkStore work;
    private Path target;

    @BeforeEach
    void initialize() {
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(clock.millis()).thenAnswer(ignored -> now.get().toEpochMilli());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        data = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                "projection-test", "jdbc:sqlite:" + root.resolve("data.db"), "low-memory", 2, 2));
        new SqliteUserVersionSchemaMigrator(data, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(data).reconcile(SCHEMAS);
        var control = new JdbcLifecycleControlStore(data, SCHEMAS);
        var disabled = control.load();
        var activating = disabled.beginActivation("projection-test-1h");
        assertThat(control.compareAndSet(disabled, activating)).isTrue();
        assertThat(control.compareAndSet(activating, activating.completeActivation(EffectiveTime.at(START)))).isTrue();
        admission = new JdbcWriterAdmission();
        safeClock = new JdbcLifecycleClock(data, clock,
                new LifecycleClockPolicy(Duration.ofSeconds(5), Duration.ofSeconds(10)), admission);
        writer = new JdbcCanonicalLifecycleWriter(data, SCHEMAS, IDS, safeClock,
                new FixedRecordValidityPolicy(TTL), clock, IDENTITIES, admission);
        reader = new JdbcCanonicalArtifactRepository(data, SCHEMAS,
                new CanonicalArtifactIdentityResolver(IDENTITIES), clock, safeClock);
        work = new JdbcArtifactProjectionWorkStore(data, clock, admission);
        target = root.resolve("masks.csv");
    }

    @AfterEach void close() { if (data != null) { data.close(); } }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restart_repairs_after_rename_and_real_ack_transaction_failure_without_new_mutation(boolean sqlFailure)
            throws Exception {
        confirm("first", "new.example");
        if (sqlFailure) {
            execute("""
                    CREATE TRIGGER fail_projection_ack BEFORE UPDATE OF projected_generation
                    ON artifact_projection_state BEGIN SELECT RAISE(ABORT, 'synthetic ack failure'); END
                    """);
        }
        ArtifactProjectionWorkStore failing = sqlFailure ? work : new ForwardingWork() {
            @Override public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
                throw new IllegalStateException("process stopped after rename");
            }
        };
        var owner = owner(reader, failing);
        assertThatThrownBy(() -> owner.project(command("failed"))).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(target)).contains("new.example");
        assertThat(work.load("masks").pending()).isTrue();
        if (sqlFailure) { execute("DROP TRIGGER fail_projection_ack"); }

        // A fresh owner has no process-local successful outcome. Durable pending work is enough.
        assertThat(convergence(owner(reader, work)).convergePending().projected()).isOne();
        assertThat(work.load("masks").pending()).isFalse();
        assertThat(work.load("masks").projectedGeneration()).isEqualTo(new ProjectionGeneration(1));
        assertNoTemporaryCsv();
    }

    @Test
    void committed_ack_with_lost_reply_needs_no_reinstallation_after_restart() throws Exception {
        confirm("first", "covered.example");
        var failing = new ForwardingWork() {
            @Override public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
                assertThat(work.acknowledge(acknowledgement)).isTrue();
                throw new IllegalStateException("ack reply lost");
            }
        };
        assertThatThrownBy(() -> owner(reader, failing).project(command("lost-reply")))
                .hasMessage("ack reply lost");
        assertThat(work.load("masks").pending()).isFalse();
        ArtifactProjection failIfReinstalled = request -> { throw new AssertionError("already covered"); };
        assertThat(convergence(failIfReinstalled).convergePending().projected()).isZero();
        assertThat(Files.readString(target)).contains("covered.example");
    }

    @Test
    void cursor_failure_before_rename_preserves_previous_file_and_pending_work() throws Exception {
        confirm("first", "old.example");
        owner(reader, work).project(command("initial"));
        String previous = Files.readString(target);
        confirm("second", "new.example");
        CanonicalArtifactStreamReader failing = (artifact, consumer) -> reader.stream(artifact, row -> {
            consumer.accept(row);
            throw new IllegalStateException("cursor failed");
        });
        assertThatThrownBy(() -> owner(failing, work).project(command("failed"))).hasMessage("cursor failed");
        assertThat(Files.readString(target)).isEqualTo(previous);
        assertThat(work.load("masks").pending()).isTrue();
        assertNoTemporaryCsv();
        convergence(owner(reader, work)).convergePending();
        assertThat(Files.readString(target)).contains("old.example", "new.example");
        assertThat(work.load("masks").pending()).isFalse();
    }

    @Test
    void cancelled_csv_build_cleans_temporary_output_and_recovers_without_new_mutation() throws Exception {
        confirm("first", "old.example");
        owner(reader, work).project(command("initial"));
        String previous = Files.readString(target);
        confirm("second", "new.example");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        CanonicalArtifactStreamReader delayed = (artifact, consumer) -> reader.stream(artifact, row -> {
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            // The production CSV consumer must observe interruption and prevent installation.
            consumer.accept(row);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var cancelled = executor.submit(() -> {
                try {
                    owner(delayed, work).project(command("cancelled"));
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    stopped.countDown();
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.cancel(true)).isTrue();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
            assertThat(Files.readString(target)).isEqualTo(previous);
            assertThat(work.load("masks").pending()).isTrue();
            assertNoTemporaryCsv();
            convergence(owner(reader, work)).convergePending();
            assertThat(Files.readString(target)).contains("old.example", "new.example");
            assertThat(work.load("masks").pending()).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void atomic_rename_failure_cleans_temporary_csv_and_leaves_work_retryable() throws Exception {
        confirm("first", "retry.example");
        Files.createDirectory(target);
        Path obstruction = Files.writeString(target.resolve("operator-file"), "preserve");

        assertThatThrownBy(() -> owner(reader, work).project(command("rename-failed")))
                .hasMessageContaining("Failed to write artifact projection");
        assertThat(Files.readString(obstruction)).isEqualTo("preserve");
        assertThat(work.load("masks").pending()).isTrue();
        assertNoTemporaryCsv();
        Files.delete(obstruction);
        Files.delete(target);
        assertThat(convergence(owner(reader, work)).convergePending().projected()).isOne();
        assertThat(Files.readString(target)).contains("retry.example");
        assertThat(work.load("masks").pending()).isFalse();
    }

    @Test
    void snapshot_coverage_survives_concurrent_ingest_import_and_expiry_without_holding_writer_admission()
            throws Exception {
        confirm("old", "expired.example");
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        CanonicalArtifactStreamReader delayed = (artifact, consumer) -> reader.stream(artifact, row -> {
            consumer.accept(row);
            if (first.getAndSet(false)) {
                captured.countDown();
                await(release);
            }
        });
        var owner = owner(delayed, work);
        var importCommand = stageImport("imported.example");
        var importer = new JdbcCanonicalImportWriter(data, SCHEMAS, IDS, IDENTITIES, root.resolve("workspace"),
                safeClock, new FixedRecordValidityPolicy(TTL), clock, admission);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var old = executor.submit(() -> owner.project(command("old-ingest")));
            assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
            now.set(START.plus(TTL));
            var imported = executor.submit(() -> importer.promote(importCommand));
            var ingested = executor.submit(() -> confirm("new-ingest", "document.example"));
            var expired = executor.submit(() -> new JdbcExpiredArtifactStore(data, SCHEMAS, admission)
                    .expireDue("masks", EffectiveTime.at(now.get()), 100));
            imported.get(5, TimeUnit.SECONDS);
            ingested.get(5, TimeUnit.SECONDS);
            assertThat(expired.get(5, TimeUnit.SECONDS).expired()).isOne();
            // All three real writer families committed while the CSV cursor was held open.
            assertThat(work.load("masks").requiredGeneration()).isEqualTo(new ProjectionGeneration(4));
            release.countDown();
            assertThat(old.get(5, TimeUnit.SECONDS).installedGeneration()).isEqualTo(new ProjectionGeneration(1));
            assertThat(work.load("masks").projectedGeneration()).isEqualTo(new ProjectionGeneration(1));
            assertThat(work.load("masks").pending()).isTrue();
            convergence(owner).convergePending();
            assertThat(Files.readString(target)).contains("imported.example", "document.example")
                    .doesNotContain("expired.example");
            assertThat(work.load("masks").pending()).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiry_only_generation_installs_valid_header_only_csv_and_keeps_insert_revision() throws Exception {
        confirm("first", "expired.example");
        var owner = owner(reader, work);
        owner.project(command("initial"));
        now.set(START.plus(TTL));
        var expiry = new JdbcExpiredArtifactStore(data, SCHEMAS, admission)
                .expireDue("masks", EffectiveTime.at(now.get()), 100);
        assertThat(expiry.artifactRevision()).isOne();
        assertThat(expiry.requiredProjectionGeneration()).isEqualTo(new ProjectionGeneration(2));
        assertThat(convergence(owner).convergePending().projected()).isOne();
        assertThat(Files.readString(target)).isEqualTo("\"id\";\"mask\";\"source\"\r\n");
        assertThat(owner.project(command("empty-recovery")).projectedRows()).isZero();
        assertThat(work.load("masks").pending()).isFalse();
    }

    @Test
    void active_projection_with_safe_clock_works_with_one_connection_pool() {
        data.setMaximumPoolSize(1);
        data.setMinimumIdle(1);
        data.getHikariPoolMXBean().softEvictConnections();
        confirm("first", "single-pool.example");
        assertThat(owner(reader, work).project(command("one-connection")).projectedRows()).isOne();
        assertThat(work.load("masks").pending()).isFalse();
    }

    private Object confirm(String observation, String mask) {
        var values = new LinkedHashMap<String, String>();
        values.put("id", null);
        values.put("mask", mask);
        values.put("source", observation);
        var row = ArtifactRow.ordered(values);
        var key = new CanonicalArtifactIdentityResolver(IDENTITIES).keyOf("masks", row).orElseThrow();
        return writer.confirm(new CanonicalArtifactConfirmation(new ObservationId(observation), observation,
                new ConfirmationReceiptContext(new ConfirmationReceiptId("receipt:" + observation),
                        "test-policy", 1, Duration.ofDays(1)), "masks", HEADER,
                List.of(new CanonicalRecordConfirmation(key, new PreparedArtifactRow(row, Optional.of("id"))))));
    }

    private CanonicalImportCommand stageImport(String mask) {
        var delivery = new ImportDeliveryId("concurrent-import");
        var snapshot = new ImportSnapshot(new ImportSnapshotReference("test-snapshot"),
                new ImportSha256("a".repeat(64)), 100);
        var pin = new ImportContractPin(new ImportContractId("test-contract"), 1,
                new ImportContractFingerprint("b".repeat(64)));
        var create = new CreateImportWorkspaceCommand(delivery, snapshot, pin,
                ImportDuplicatePolicy.COALESCE, ImportPromotionPolicy.defaults());
        var resolver = new CanonicalArtifactKeyResolver(IDENTITIES);
        var keyRow = ArtifactRow.ordered(Map.of("mask", mask, "source", "import"));
        var branch = new ImportArtifactBranch("masks", ImportArtifactRole.PRIMARY,
                Map.of("mask", ImportCell.value(mask), "source", ImportCell.value("import")),
                OptionalLong.empty(), Optional.of(resolver.recordKeyOf("masks", keyRow).orElseThrow()),
                resolver.matchKeysOf("masks", keyRow));
        var workspace = new JdbcImportWorkspace(root.resolve("workspace"), ImportWorkspaceLimits.defaults(), clock);
        try (var staging = workspace.create(create)) {
            staging.append(new ImportLogicalRow(2, List.of(branch)));
            return new CanonicalImportCommand(delivery, new ImportDeliverySequence(1),
                    new ImportSourceId("test-import"), snapshot, pin, staging.seal());
        }
    }

    private ArtifactProjection owner(CanonicalArtifactStreamReader source, ArtifactProjectionWorkStore store) {
        return new GenerationOwnedArtifactProjection(List.of("masks"), new CsvArtifactProjection(source,
                Map.of("masks", HEADER), Map.of("masks", target),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"), new DiagnosticFactory(clock)), store);
    }

    private ArtifactProjectionConvergenceService convergence(ArtifactProjection projection) {
        return new ArtifactProjectionConvergenceService(List.of("masks"), work, projection,
                NoopDiagnosticSink.INSTANCE);
    }

    private ArtifactProjectionCommand command(String run) { return new ArtifactProjectionCommand(run, "masks"); }

    private void execute(String sql) throws Exception {
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertNoTemporaryCsv() throws Exception {
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("masks.csv") && name.endsWith(".tmp"));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", failure);
        }
    }

    private class ForwardingWork implements ArtifactProjectionWorkStore {
        @Override public com.iocextractor.application.artifact.lifecycle.ArtifactProjectionState load(String artifact) {
            return work.load(artifact);
        }
        @Override public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
            return work.acknowledge(acknowledgement);
        }
        @Override public boolean recordFailure(String artifact, ProjectionGeneration generation, String code) {
            return work.recordFailure(artifact, generation, code);
        }
    }
}
