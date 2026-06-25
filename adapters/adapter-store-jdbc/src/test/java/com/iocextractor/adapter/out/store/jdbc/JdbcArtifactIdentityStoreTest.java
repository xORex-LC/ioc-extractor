package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
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

class JdbcArtifactIdentityStoreTest {

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

    private JdbcArtifactIdentityStore store(CollectingDiagnosticSink diagnostics) {
        dataSource = dataSource("identity-" + System.nanoTime() + ".db");
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
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

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
