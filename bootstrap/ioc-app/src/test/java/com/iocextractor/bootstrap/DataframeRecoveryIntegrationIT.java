package com.iocextractor.bootstrap;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection;
import com.iocextractor.adapter.out.store.jdbc.DataframeArtifactSchema;
import com.iocextractor.adapter.out.store.jdbc.DataframeColumn;
import com.iocextractor.adapter.out.store.jdbc.DataframeFormatMigrations;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.JdbcRunLedger;
import com.iocextractor.adapter.out.store.jdbc.ServiceSchemaMigrations;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.IngestRunRecoveryService;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionConvergenceService;
import com.iocextractor.application.artifact.lifecycle.GenerationOwnedArtifactProjection;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactProjectionWorkStore;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactStreamReader;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash-window recovery of the per-file ingest saga, asserted at the data level:
 * the canonical write commits but the process dies before the CSV projection is
 * written. Startup recovery must replay the projection from canonical truth so
 * the projection ends byte-consistent with the database (no data loss).
 */
@IntegrationTest
class DataframeRecoveryIntegrationIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);
    private static final List<String> HEADER = List.of("id", "mask", "source");

    @TempDir
    Path tempDir;

    private HikariDataSource dataframeDataSource;
    private HikariDataSource serviceDataSource;

    @AfterEach
    void close() {
        if (dataframeDataSource != null) {
            dataframeDataSource.close();
        }
        if (serviceDataSource != null) {
            serviceDataSource.close();
        }
    }

    @Test
    void recovers_projection_from_canonical_truth_after_db_committed_crash() throws Exception {
        DataframeArtifactSchema schema = new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("id"), new DataframeColumn("mask"), new DataframeColumn("source")));
        dataframeDataSource = dataSource("ioc-dataframe.db", "dataframe");
        new SqliteUserVersionSchemaMigrator(dataframeDataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataframeDataSource).reconcile(List.of(schema));
        serviceDataSource = dataSource("ioc-service.db", "service");
        new SqliteUserVersionSchemaMigrator(serviceDataSource, ServiceSchemaMigrations.sqlite()).migrate();

        JdbcCanonicalArtifactRepository canonical = new JdbcCanonicalArtifactRepository(
                dataframeDataSource,
                List.of(schema),
                new CanonicalArtifactIdentityResolver(List.of(
                        new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1))),
                CLOCK);
        Path projectionPath = tempDir.resolve("masks_list_generated.csv");
        CsvArtifactProjection projection = new CsvArtifactProjection(
                canonical,
                Map.of("masks", HEADER),
                Map.of("masks", projectionPath),
                new ExportFormat("csv", StandardCharsets.UTF_8.name(), ";", "\"", "NULL"),
                new DiagnosticFactory(CLOCK));
        RunLedger runLedger = new JdbcRunLedger(serviceDataSource, CLOCK);

        // ---- crash window: canonical committed, run marked DB_COMMITTED, projection NOT written ----
        IngestRun run = runLedger.startIngest("src-1", List.of("masks"));
        canonical.write("masks", new CanonicalArtifact("masks", HEADER, List.of(
                row("1", "example.com", "letter-a", "src-1"),
                row("2", "example.org", "letter-b", "src-1"),
                row("3", "example.com", "letter-a", "src-1"))));
        runLedger.markDbCommitted(run.runId());

        // snapshot before recovery: 2 keep-first rows in the DB, projection absent, run open
        assertThat(canonical.load("masks").rows()).hasSize(2);
        assertThat(Files.notExists(projectionPath)).isTrue();
        assertThat(runLedger.findIncompleteIngestRuns()).singleElement()
                .extracting(incompleteRun -> incompleteRun.status()).isEqualTo(IngestRunStatus.DB_COMMITTED);

        // ---- startup recovery ----
        int recovered = new IngestRunRecoveryService(
                runLedger, projection, NoopDiagnosticSink.INSTANCE).recover();

        // no data loss: projection now exactly mirrors canonical truth, run closed
        assertThat(recovered).isEqualTo(1);
        assertThat(runLedger.findIncompleteIngestRuns()).isEmpty();
        List<String> dbMasks = canonical.load("masks").rows().stream().map(r -> r.value("mask")).toList();
        List<String> projectionLines = Files.readAllLines(projectionPath, StandardCharsets.UTF_8);
        assertThat(projectionLines).hasSize(dbMasks.size() + 1); // header + one line per DB row
        assertThat(projectionLines.getFirst()).isEqualTo("\"id\";\"mask\";\"source\"");
        assertThat(projectionLines).contains("\"1\";\"example.com\";\"letter-a\"", "\"2\";\"example.org\";\"letter-b\"");
    }

    @Test
    @Timeout(20)
    void older_ingest_snapshot_cannot_overwrite_newer_acknowledged_projection() throws Exception {
        var schema = new DataframeArtifactSchema("masks", HEADER.stream().map(DataframeColumn::new).toList());
        dataframeDataSource = dataSource("projection-race.db", "projection-race");
        new SqliteUserVersionSchemaMigrator(dataframeDataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataframeDataSource).reconcile(List.of(schema));
        var canonical = new JdbcCanonicalArtifactRepository(dataframeDataSource, List.of(schema),
                new CanonicalArtifactIdentityResolver(List.of(
                        new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1))), CLOCK);
        canonical.write("masks", new CanonicalArtifact("masks", HEADER,
                List.of(row("1", "old.example", "first", "source"))));
        setProjectionGeneration(1);
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        CanonicalArtifactStreamReader delayedReader = (artifact, consumer) -> {
            var result = canonical.stream(artifact, consumer);
            if (first.getAndSet(false)) {
                captured.countDown();
                await(release);
            }
            return result;
        };
        Path target = tempDir.resolve("race.csv");
        var installer = new CsvArtifactProjection(delayedReader, Map.of("masks", HEADER),
                Map.of("masks", target), new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                new DiagnosticFactory(CLOCK));
        var work = new JdbcArtifactProjectionWorkStore(dataframeDataSource, CLOCK);
        var projection = new GenerationOwnedArtifactProjection(List.of("masks"), installer, work);
        var convergence = new ArtifactProjectionConvergenceService(List.of("masks"), work, projection,
                NoopDiagnosticSink.INSTANCE);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var older = executor.submit(() -> projection.project(new ArtifactProjectionCommand("ingest", "masks")));
            assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
            canonical.write("masks", new CanonicalArtifact("masks", HEADER,
                    List.of(row("2", "new.example", "second", "source"))));
            setProjectionGeneration(2);
            var newer = executor.submit(convergence::convergePending);
            release.countDown();
            older.get(5, TimeUnit.SECONDS);
            newer.get(5, TimeUnit.SECONDS);
            assertThat(work.load("masks").pending()).isFalse();
            assertThat(Files.readString(target)).contains("old.example", "new.example");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void setProjectionGeneration(long generation) throws Exception {
        try (var connection = dataframeDataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO artifact_projection_state(artifact, required_generation, projected_generation,
                         requested_at_ms) VALUES ('masks', ?, 0, 0)
                     ON CONFLICT(artifact) DO UPDATE SET required_generation = excluded.required_generation
                     """)) {
            statement.setLong(1, generation);
            statement.executeUpdate();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Projection test interrupted", failure);
        }
    }

    private ArtifactRow row(String id, String mask, String source, String sourceKey) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("mask", mask);
        values.put("source", source);
        values.put("_source_key", sourceKey);
        return ArtifactRow.ordered(values);
    }

    private HikariDataSource dataSource(String fileName, String role) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                role, "jdbc:sqlite:" + tempDir.resolve(fileName), "low-memory", 1, 1));
    }
}
