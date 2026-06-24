package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.ArtifactIdentityDefinition;
import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.aggregation.CanonicalArtifactIdentityResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void imports_generated_csv_preserving_ids_and_bumps_sequence_from_sidecar() throws Exception {
        DataframeArtifactSchema schema = new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("id"),
                new DataframeColumn("mask"),
                new DataframeColumn("source")));
        JdbcCanonicalArtifactRepository repository = repository(schema);
        Path csv = tempDir.resolve("masks_list_generated.csv");
        Files.writeString(csv, "\"id\";\"mask\";\"source\"\r\n"
                + "\"10\";\"example.com\";\"legacy\"\r\n"
                + "\"11\";\"example.org\";\"legacy\"\r\n");
        Path idIndex = tempDir.resolve(".ioc-id-index.csv");
        Files.writeString(idIndex, "\"artifact\";\"key\";\"id\";\"created_at\";\"updated_at\"\r\n"
                + "\"masks\";\"old\";\"99\";\"2026-06-01T00:00:00Z\";\"2026-06-01T00:00:00Z\"\r\n");
        var importer = new JdbcLegacyArtifactImporter(
                dataSource,
                repository,
                List.of(schema),
                Map.of("masks", csv),
                idIndex);

        var summary = importer.importAll();
        repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"),
                List.of(new ArtifactRow(Map.of("mask", "example.net", "source", "new")))));

        assertThat(summary.artifacts()).isEqualTo(1);
        assertThat(summary.rows()).isEqualTo(2);
        assertThat(summary.sequenceFloor()).isEqualTo(99L);
        assertThat(repository.load("masks").rows())
                .extracting(row -> row.value("id") + ":" + row.value("mask"))
                .containsExactly("10:example.com", "11:example.org", "100:example.net");
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

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
