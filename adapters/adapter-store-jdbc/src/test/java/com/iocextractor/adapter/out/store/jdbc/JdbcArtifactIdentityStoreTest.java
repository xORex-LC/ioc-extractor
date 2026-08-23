package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityStore;
import com.iocextractor.application.tck.artifact.CanonicalIdentityStoreContractTest;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcArtifactIdentityStoreTest extends CanonicalIdentityStoreContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-24T00:00:00Z"), ZoneOffset.UTC);

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
    void registers_missing_identity_marker() {
        JdbcArtifactIdentityStore store = store(new CollectingDiagnosticSink());
        ArtifactIdentityDefinition definition = definition("masks", List.of("mask"), false, 1);

        var stored = store.ensure(definition);

        assertThat(stored.artifactName()).isEqualTo("masks");
        assertThat(stored.identityHash()).isEqualTo(definition.identityHash());
        assertThat(stored.epoch()).isEqualTo(1);
    }

    @Test
    void same_identity_is_idempotent() {
        CollectingDiagnosticSink diagnostics = new CollectingDiagnosticSink();
        JdbcArtifactIdentityStore store = store(diagnostics);
        ArtifactIdentityDefinition definition = definition("masks", List.of("mask"), false, 1);

        store.ensure(definition);
        var replay = store.ensure(definition);

        assertThat(replay.identityHash()).isEqualTo(definition.identityHash());
        assertThat(diagnostics.diagnostics()).isEmpty();
    }

    @Test
    void identity_drift_at_same_epoch_is_fatal_and_diagnostic() {
        CollectingDiagnosticSink diagnostics = new CollectingDiagnosticSink();
        JdbcArtifactIdentityStore store = store(diagnostics);
        store.ensure(definition("masks", List.of("mask"), false, 1));

        assertThatThrownBy(() -> store.ensure(definition("masks", List.of("mask", "source"), false, 1)))
                .hasMessageContaining(StorageDiagnosticCodes.IDENTITY_DRIFT.id());
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .contains(StorageDiagnosticCodes.IDENTITY_DRIFT.id());
    }

    @Test
    void epoch_bump_authorizes_identity_hash_update_after_backfill() {
        CollectingDiagnosticSink diagnostics = new CollectingDiagnosticSink();
        JdbcArtifactIdentityStore store = store(diagnostics);
        store.ensure(definition("masks", List.of("mask"), false, 1));
        ArtifactIdentityDefinition bumped = definition("masks", List.of("mask", "source"), false, 2);

        var stored = store.ensure(bumped);

        assertThat(stored.identityHash()).isEqualTo(bumped.identityHash());
        assertThat(stored.epoch()).isEqualTo(2);
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .contains(StorageDiagnosticCodes.IDENTITY_EPOCH_BUMP.id());
    }

    @Test
    void compound_backfill_preserves_public_and_lifecycle_identity_and_builds_aliases() throws Exception {
        JdbcArtifactIdentityStore store = store(new CollectingDiagnosticSink());
        ArtifactIdentityDefinition oldDefinition = definition("masks", List.of("mask"), false, 1);
        store.ensure(oldDefinition);
        execute("""
                INSERT INTO masks(
                    id, mask, source, row_key, _created_at, _first_source_key,
                    _lifecycle_id, _first_confirmed_at_epoch_ms,
                    _last_confirmed_at_epoch_ms, _valid_until_epoch_ms)
                VALUES (41, 'Example.TEST', 'feed-a', 'legacy-key', '2026-06-24T00:00:00Z',
                        'feed-a', 701, 100, 200, 1000)
                """);
        ArtifactIdentityDefinition next = compoundDefinition(2);

        store.ensure(next);

        assertThat(queryLong("SELECT id FROM masks")).isEqualTo(41L);
        assertThat(queryLong("SELECT _lifecycle_id FROM masks")).isEqualTo(701L);
        assertThat(queryString("SELECT mask || '|' || source FROM masks"))
                .isEqualTo("Example.TEST|feed-a");
        assertThat(queryString("SELECT row_key FROM masks")).isNotEqualTo("legacy-key");
        assertThat(queryLong("SELECT canonical_row_id FROM canonical_match_alias"))
                .isEqualTo(41L);
        assertThat(queryLong("SELECT lifecycle_id FROM canonical_match_alias"))
                .isEqualTo(701L);
    }

    @Test
    void collision_preflight_rolls_back_every_durable_identity_change() throws Exception {
        JdbcArtifactIdentityStore store = store(new CollectingDiagnosticSink());
        ArtifactIdentityDefinition oldDefinition = definition("masks", List.of("mask"), false, 1);
        store.ensure(oldDefinition);
        execute("""
                INSERT INTO masks(
                    id, mask, source, row_key, _created_at, _first_source_key,
                    _lifecycle_id, _first_confirmed_at_epoch_ms,
                    _last_confirmed_at_epoch_ms, _valid_until_epoch_ms)
                VALUES (51, 'same.example', 'feed-a', 'manually-diverged-a', '2026-06-24T00:00:00Z',
                        'feed-a', 801, 100, 200, 1000),
                       (52, 'same.example', 'feed-a', 'manually-diverged-b', '2026-06-24T00:00:00Z',
                        'feed-a', 802, 100, 200, 1000)
                """);

        assertThatThrownBy(() -> store.ensure(compoundDefinition(2)))
                .hasMessageContaining(StorageDiagnosticCodes.IDENTITY_DRIFT.id());

        assertThat(queryString("SELECT group_concat(row_key, ',') FROM masks ORDER BY id"))
                .isEqualTo("manually-diverged-a,manually-diverged-b");
        assertThat(queryLong("SELECT epoch FROM artifact_identity WHERE artifact = 'masks'"))
                .isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM canonical_match_alias")).isZero();
    }

    @Override
    protected ArtifactIdentityStore createIdentityStore() {
        return store(new CollectingDiagnosticSink());
    }

    private JdbcArtifactIdentityStore store(CollectingDiagnosticSink diagnostics) {
        dataSource = dataSource("identity-" + System.nanoTime() + ".db");
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(
                new DataframeArtifactSchema("masks", List.of(
                        new DataframeColumn("mask"), new DataframeColumn("source")))));
        return new JdbcArtifactIdentityStore(
                dataSource, CLOCK, diagnostics, new com.iocextractor.diagnostics.DiagnosticFactory(CLOCK),
                "dataframe");
    }

    private ArtifactIdentityDefinition definition(String artifact,
                                                  List<String> columns,
                                                  boolean firstNonEmpty,
                                                  int epoch) {
        return new ArtifactIdentityDefinition(artifact, columns, firstNonEmpty, epoch);
    }

    private ArtifactIdentityDefinition compoundDefinition(int epoch) {
        return new ArtifactIdentityDefinition(
                "masks",
                new CanonicalKeyDefinition(
                        "mask-source-row-v" + epoch,
                        CanonicalKeyMode.COMPOSITE,
                        List.of("mask", "source")),
                List.of(new CanonicalKeyDefinition(
                        "mask-v1", CanonicalKeyMode.COMPOSITE, List.of("mask"))),
                epoch);
    }

    private void execute(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String queryString(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
