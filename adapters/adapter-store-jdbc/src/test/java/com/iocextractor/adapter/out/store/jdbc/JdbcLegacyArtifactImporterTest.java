package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLegacyArtifactImporterTest {

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
    void imports_legacy_rows_preserving_ids_and_bumps_sequence_from_imported_max_id() {
        DataframeArtifactSchema schema = new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("id"),
                new DataframeColumn("mask"),
                new DataframeColumn("source")));
        JdbcCanonicalArtifactRepository repository = repository(schema);

        // Legacy CSV reading is the CSV adapter's job; here the source repository
        // hands back already-parsed rows.
        CanonicalArtifactRepository legacySource = legacySource("masks", List.of("id", "mask", "source"),
                row("id", "10", "mask", "example.com", "source", "legacy"),
                row("id", "11", "mask", "example.org", "source", "legacy"));
        var importer = new JdbcLegacyArtifactImporter(dataSource, repository, legacySource, List.of(schema));

        var summary = importer.importAll();
        repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"),
                List.of(new ArtifactRow(Map.of("mask", "example.net", "source", "new")))));

        assertThat(summary.artifacts()).isEqualTo(1);
        assertThat(summary.rows()).isEqualTo(2);
        assertThat(summary.sequenceFloor()).isEqualTo(11L);
        assertThat(repository.load("masks").rows())
                .extracting(row -> row.value("id") + ":" + row.value("mask"))
                .containsExactly("10:example.com", "11:example.org", "12:example.net");
    }

    private CanonicalArtifactRepository legacySource(String artifactName, List<String> header, ArtifactRow... rows) {
        return new CanonicalArtifactRepository() {
            @Override
            public CanonicalArtifact load(String name) {
                return name.equals(artifactName)
                        ? new CanonicalArtifact(artifactName, header, List.of(rows))
                        : new CanonicalArtifact(name, header, List.of());
            }

            @Override
            public void write(String name, CanonicalArtifact artifact) {
                throw new UnsupportedOperationException("read-only legacy source");
            }
        };
    }

    private JdbcCanonicalArtifactRepository repository(DataframeArtifactSchema schema) {
        dataSource = dataSource("legacy-" + System.nanoTime() + ".db");
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(schema));
        return new JdbcCanonicalArtifactRepository(
                dataSource,
                List.of(schema),
                new CanonicalArtifactIdentityResolver(List.of(
                        new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1))),
                CLOCK);
    }

    private ArtifactRow row(String... pairs) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return ArtifactRow.ordered(values);
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
