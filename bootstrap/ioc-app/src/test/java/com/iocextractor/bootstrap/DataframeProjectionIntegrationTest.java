package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.CsvArtifactProjection;
import com.iocextractor.adapter.out.sink.csv.ProjectingCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.DataframeArtifactSchema;
import com.iocextractor.adapter.out.store.jdbc.DataframeColumn;
import com.iocextractor.adapter.out.store.jdbc.DataframeFormatMigrations;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalArtifactRepository;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.application.aggregation.ArtifactIdentityDefinition;
import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.aggregation.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the daemon truth path composition: aggregation writes
 * through {@link ProjectingCanonicalArtifactRepository}, which persists to the
 * JDBC canonical store (including per-source rows) and refreshes the CSV
 * projection. Exercises the wiring that only context/unit tests cover otherwise.
 */
class DataframeProjectionIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);
    private static final List<String> HEADER = List.of("id", "mask", "source");

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
    void aggregation_write_persists_canonical_sources_and_csv_projection() throws Exception {
        DataframeArtifactSchema schema = new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("id"), new DataframeColumn("mask"), new DataframeColumn("source")));
        dataSource = dataSource();
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(schema));

        JdbcCanonicalArtifactRepository canonical = new JdbcCanonicalArtifactRepository(
                dataSource,
                List.of(schema),
                new CanonicalArtifactIdentityResolver(List.of(
                        new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1))),
                CLOCK);
        Path projectionPath = tempDir.resolve("masks_list_generated.csv");
        CsvArtifactProjection projection = new CsvArtifactProjection(
                canonical,
                Map.of("masks", HEADER),
                Map.of("masks", projectionPath),
                CSVFormat.DEFAULT.builder()
                        .setDelimiter(';')
                        .setNullString("NULL")
                        .setQuoteMode(QuoteMode.ALL_NON_NULL)
                        .build(),
                StandardCharsets.UTF_8);
        CanonicalArtifactRepository projecting = new ProjectingCanonicalArtifactRepository(canonical, projection);

        projecting.write("masks", new CanonicalArtifact("masks", HEADER, List.of(
                row("1", "example.com", "letter-a", "src-1"),
                row("2", "example.org", "letter-b", "src-1"),
                row("3", "example.com", "letter-a", "src-2"))));

        // keep-first canonical rows
        assertThat(canonical.load("masks").rows())
                .extracting(r -> r.value("id") + ":" + r.value("mask"))
                .containsExactly("1:example.com", "2:example.org");
        // a dropped duplicate still records its source against the kept row
        assertThat(sourceRows()).containsExactlyInAnyOrder("1:src-1:1", "1:src-2:1", "2:src-1:1");
        // CSV projection refreshed from the canonical store (values quoted, ';' delimited)
        String csv = Files.readString(projectionPath, StandardCharsets.UTF_8);
        assertThat(csv.lines().findFirst()).hasValue("\"id\";\"mask\";\"source\"");
        assertThat(csv).contains("\"1\";\"example.com\";\"letter-a\"")
                .contains("\"2\";\"example.org\";\"letter-b\"");
        // only the two keep-first rows are projected (header + 2 rows), not the dropped duplicate
        assertThat(csv.strip().lines().count()).isEqualTo(3);
    }

    private ArtifactRow row(String id, String mask, String source, String sourceKey) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("mask", mask);
        values.put("source", source);
        values.put("_source_key", sourceKey);
        return ArtifactRow.ordered(values);
    }

    private List<String> sourceRows() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT row_id, source_key, occurrences FROM masks_sources ORDER BY row_id, source_key")) {
            var rows = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                rows.add(resultSet.getLong("row_id") + ":"
                        + resultSet.getString("source_key") + ":"
                        + resultSet.getLong("occurrences"));
            }
            return rows;
        }
    }

    private HikariDataSource dataSource() {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                "dataframe", "jdbc:sqlite:" + tempDir.resolve("ioc-dataframe.db"), "low-memory", 1, 1));
    }
}
