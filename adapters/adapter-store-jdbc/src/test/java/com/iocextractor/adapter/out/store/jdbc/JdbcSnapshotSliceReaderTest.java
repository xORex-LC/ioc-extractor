package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSnapshotSliceReaderTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void streams_profile_in_plan_and_id_order_with_snapshot_metadata() {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "2", "mask", "b.example"),
                row("id", "1", "mask", "a.example"));
        fixture.write("hashes", row("id", "7", "hash", "BBBB"),
                row("id", "3", "hash", "AAAA"));
        RecordingConsumer consumer = new RecordingConsumer();

        SnapshotMetadata result = fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer);

        assertThat(consumer.events).containsExactly(
                "begin", "artifact:masks", "row:1", "row:2", "end-artifact",
                "artifact:hashes", "row:3", "row:7", "end-artifact", "end");
        assertThat(result).isEqualTo(consumer.metadata);
        assertThat(result.artifacts())
                .extracting(SnapshotArtifactMetadata::artifactName)
                .containsExactly("masks", "hashes");
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.coverage().revision())
                .containsExactly(1L, 1L);
        assertThat(result.artifacts())
                .extracting(metadata -> metadata.coverage().upperId())
                .containsExactly(2L, 7L);
        assertThat(result.artifacts())
                .allSatisfy(metadata -> {
                    assertThat(metadata.identityEpoch()).isOne();
                    assertThat(metadata.identityHash()).isNotBlank();
                    assertThat(metadata.schemaHash()).isEqualTo(HASH_B);
                });
    }

    @Test
    void commit_after_snapshot_start_is_deferred_to_the_next_snapshot() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        fixture.write("hashes", row("id", "1", "hash", "AAAA"));
        var began = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        BlockingConsumer consumer = new BlockingConsumer(began, release);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var current = executor.submit(() -> fixture.reader()
                    .stream(new SnapshotRequest(fixture.plan()), consumer));
            assertThat(began.await(5, TimeUnit.SECONDS)).isTrue();

            try {
                fixture.write("hashes", row("id", "2", "hash", "BBBB"));
            } finally {
                release.countDown();
            }
            SnapshotMetadata currentMetadata = current.get(5, TimeUnit.SECONDS);

            assertThat(consumer.rowsByArtifact.get("hashes")).containsExactly("1");
            assertThat(currentMetadata.artifacts().get(1).coverage().revision()).isEqualTo(1);
        }

        RecordingConsumer next = new RecordingConsumer();
        SnapshotMetadata nextMetadata = fixture.reader()
                .stream(new SnapshotRequest(fixture.plan()), next);
        assertThat(next.rowsByArtifact.get("hashes")).containsExactly("1", "2");
        assertThat(nextMetadata.artifacts().get(1).coverage().revision()).isEqualTo(2);
    }

    @Test
    void consumer_failure_is_propagated_and_all_jdbc_resources_are_released() {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        RuntimeException failure = new IllegalStateException("consumer failed");
        SnapshotRowConsumer consumer = new NoopConsumer() {
            @Override
            public void row(ArtifactRow row) {
                throw failure;
            }
        };

        assertThatThrownBy(() -> fixture.reader().stream(new SnapshotRequest(fixture.plan()), consumer))
                .isSameAs(failure);
        assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(dataSource.getHikariPoolMXBean().getIdleConnections()).isPositive();
    }

    @Test
    void unknown_artifact_is_rejected_before_snapshot_io() {
        Fixture fixture = fixture();
        ExportPlan unknown = plan(List.of(spec("unknown", "value", HASH_A)));

        assertThatThrownBy(() -> fixture.reader().stream(
                new SnapshotRequest(unknown), new NoopConsumer()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown dataframe artifact");
    }

    @Test
    void sql_failure_emits_snapshot_diagnostic() throws Exception {
        Fixture fixture = fixture();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE hashes");
        }
        var diagnostics = new CollectingDiagnosticSink();
        var reader = new JdbcSnapshotSliceReader(
                dataSource, fixture.schemas(), CLOCK, diagnostics, new DiagnosticFactory(CLOCK));

        assertThatThrownBy(() -> reader.stream(new SnapshotRequest(fixture.plan()), new NoopConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED.id());
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED);
    }

    @Test
    void malformed_canonical_coverage_emits_snapshot_diagnostic() throws Exception {
        Fixture fixture = fixture();
        fixture.write("masks", row("id", "1", "mask", "a.example"));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE artifact_revision SET changed_at = ? WHERE artifact = ?")) {
            statement.setString(1, "not-an-instant");
            statement.setString(2, "masks");
            statement.executeUpdate();
        }
        var diagnostics = new CollectingDiagnosticSink();
        var reader = new JdbcSnapshotSliceReader(
                dataSource, fixture.schemas(), CLOCK, diagnostics, new DiagnosticFactory(CLOCK));

        assertThatThrownBy(() -> reader.stream(new SnapshotRequest(fixture.plan()), new NoopConsumer()))
                .isInstanceOf(DiagnosticException.class)
                .hasMessageContaining(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED.id())
                .hasRootCauseInstanceOf(java.time.format.DateTimeParseException.class);
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.SNAPSHOT_READ_FAILED);
    }

    private Fixture fixture() {
        List<DataframeArtifactSchema> schemas = List.of(
                schema("masks", "id", "mask"),
                schema("hashes", "id", "hash"));
        List<ArtifactIdentityDefinition> identities = List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1),
                new ArtifactIdentityDefinition("hashes", List.of("hash"), false, 1));
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + tempDir.resolve("snapshot.db"),
                        "low-memory", 1, 2));
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        var identityStore = new JdbcArtifactIdentityStore(dataSource, CLOCK);
        identities.forEach(identityStore::ensure);
        var repository = new JdbcCanonicalArtifactRepository(
                dataSource, schemas, new CanonicalArtifactIdentityResolver(identities), CLOCK);
        ExportPlan plan = plan(List.of(
                spec("masks", "mask", identities.get(0).identityHash()),
                spec("hashes", "hash", identities.get(1).identityHash())));
        return new Fixture(
                schemas,
                plan,
                repository,
                new JdbcSnapshotSliceReader(dataSource, schemas, CLOCK));
    }

    private ExportPlan plan(List<ExportArtifactSpec> artifacts) {
        List<String> names = artifacts.stream().map(ExportArtifactSpec::artifactName).toList();
        return new ExportPlan(
                1,
                new ExportProfile("reputation", ExportMode.COMPLETE, names),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                artifacts);
    }

    private ExportArtifactSpec spec(String artifact, String valueColumn, String identityHash) {
        return new ExportArtifactSpec(
                artifact, artifact + ".csv", List.of("id", valueColumn),
                1, identityHash, HASH_B, HASH_B);
    }

    private DataframeArtifactSchema schema(String name, String... columns) {
        return new DataframeArtifactSchema(name, java.util.Arrays.stream(columns)
                .map(DataframeColumn::new)
                .toList());
    }

    private ArtifactRow row(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return ArtifactRow.ordered(values);
    }

    private static class RecordingConsumer implements SnapshotRowConsumer {
        final List<String> events = new ArrayList<>();
        final Map<String, List<String>> rowsByArtifact = new LinkedHashMap<>();
        SnapshotMetadata metadata;
        String artifact;

        @Override
        public void begin(SnapshotMetadata metadata) {
            this.metadata = metadata;
            events.add("begin");
        }

        @Override
        public void beginArtifact(SnapshotArtifactMetadata artifact) {
            this.artifact = artifact.artifactName();
            rowsByArtifact.put(this.artifact, new ArrayList<>());
            events.add("artifact:" + this.artifact);
        }

        @Override
        public void row(ArtifactRow row) {
            rowsByArtifact.get(artifact).add(row.value("id"));
            events.add("row:" + row.value("id"));
        }

        @Override
        public void endArtifact() {
            events.add("end-artifact");
        }

        @Override
        public void end() {
            events.add("end");
        }
    }

    private static final class BlockingConsumer extends RecordingConsumer {
        private final CountDownLatch began;
        private final CountDownLatch release;

        private BlockingConsumer(CountDownLatch began, CountDownLatch release) {
            this.began = began;
            this.release = release;
        }

        @Override
        public void begin(SnapshotMetadata metadata) {
            super.begin(metadata);
            began.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding snapshot", e);
            }
        }
    }

    private static class NoopConsumer implements SnapshotRowConsumer {
        @Override
        public void begin(SnapshotMetadata metadata) {
        }

        @Override
        public void beginArtifact(SnapshotArtifactMetadata artifact) {
        }

        @Override
        public void row(ArtifactRow row) {
        }

        @Override
        public void endArtifact() {
        }

        @Override
        public void end() {
        }
    }

    private record Fixture(List<DataframeArtifactSchema> schemas,
                           ExportPlan plan,
                           JdbcCanonicalArtifactRepository repository,
                           JdbcSnapshotSliceReader reader) {

        void write(String artifact, ArtifactRow... rows) {
            repository.write(artifact, new CanonicalArtifact(
                    artifact, rows[0].values().keySet().stream().toList(), List.of(rows)));
        }
    }
}
