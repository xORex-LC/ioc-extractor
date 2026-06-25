package com.iocextractor.bootstrap;

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
import com.iocextractor.application.port.out.artifact.RunLedger;
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
 * Crash-window recovery of the per-file ingest saga, asserted at the data level:
 * the canonical write commits but the process dies before the CSV projection is
 * written. Startup recovery must replay the projection from canonical truth so
 * the projection ends byte-consistent with the database (no data loss).
 */
class DataframeRecoveryIntegrationTest {

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
                CSVFormat.DEFAULT.builder()
                        .setDelimiter(';')
                        .setNullString("NULL")
                        .setQuoteMode(QuoteMode.ALL_NON_NULL)
                        .build(),
                StandardCharsets.UTF_8);
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
                .extracting(IngestRun::status).isEqualTo(IngestRunStatus.DB_COMMITTED);

        // ---- startup recovery ----
        int recovered = new IngestRunRecoveryService(runLedger, projection).recover();

        // no data loss: projection now exactly mirrors canonical truth, run closed
        assertThat(recovered).isEqualTo(1);
        assertThat(runLedger.findIncompleteIngestRuns()).isEmpty();
        List<String> dbMasks = canonical.load("masks").rows().stream().map(r -> r.value("mask")).toList();
        List<String> projectionLines = Files.readAllLines(projectionPath, StandardCharsets.UTF_8);
        assertThat(projectionLines).hasSize(dbMasks.size() + 1); // header + one line per DB row
        assertThat(projectionLines.getFirst()).isEqualTo("\"id\";\"mask\";\"source\"");
        assertThat(projectionLines).contains("\"1\";\"example.com\";\"letter-a\"", "\"2\";\"example.org\";\"letter-b\"");
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
