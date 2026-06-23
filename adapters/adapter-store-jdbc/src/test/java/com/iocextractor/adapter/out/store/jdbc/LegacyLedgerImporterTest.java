package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyLedgerImporterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private JdbcIngestionLedger ledger;
    private CollectingDiagnosticSink diagnostics;
    private Path legacyDir;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + tempDir.resolve("service.db"),
                        "low-memory", 1, 1));
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        ledger = new JdbcIngestionLedger(dataSource, CLOCK);
        diagnostics = new CollectingDiagnosticSink();
        legacyDir = Files.createDirectories(tempDir.resolve("legacy"));
    }

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void imports_legacy_ledger_file_and_completes_marker() throws Exception {
        writeLegacy("source-a.properties", "source-a", IngestionStatus.SOURCE_ARCHIVED,
                List.of("partitions/source-a.csv"), "done/source-a.html", null);

        var summary = importer().importAll();

        assertThat(summary.scanned()).isEqualTo(1);
        assertThat(summary.imported()).isEqualTo(1);
        assertThat(summary.skipped()).isZero();
        var record = ledger.find(new SourceKey("source-a")).orElseThrow();
        assertThat(record.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(record.partitions()).containsExactly(Path.of("partitions/source-a.csv"));
        assertThat(record.archivedPath()).isEqualTo(Path.of("done/source-a.html"));
        assertThat(markerStatus("source-a.properties")).isEqualTo("COMPLETED");
        assertThat(markerCompletedAt("source-a.properties")).isEqualTo(CLOCK.instant().toString());
    }

    @Test
    void replays_in_progress_marker_without_duplicate_partitions() throws Exception {
        Path file = writeLegacy("source-b.properties", "source-b", IngestionStatus.SOURCE_ARCHIVED,
                List.of("partitions/source-b.csv"), "done/source-b.html", null);
        insertMarker("source-b.properties", file, "old-checksum", "IN_PROGRESS");
        ledger.markClaimed(new SourceUnit(new SourceKey("source-b"),
                Path.of("inbox/source-b.html"), Path.of("processing/source-b.html"), CLOCK.instant()));

        var summary = importer().importAll();

        assertThat(summary.imported()).isEqualTo(1);
        var record = ledger.find(new SourceKey("source-b")).orElseThrow();
        assertThat(record.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(record.partitions()).containsExactly(Path.of("partitions/source-b.csv"));
        assertThat(markerStatus("source-b.properties")).isEqualTo("COMPLETED");
        assertThat(partitionRows("source-b")).containsExactly("partitions/source-b.csv");
    }

    @Test
    void skips_completed_import_with_same_checksum() throws Exception {
        writeLegacy("source-c.properties", "source-c", IngestionStatus.SOURCE_ARCHIVED,
                List.of("partitions/source-c.csv"), "done/source-c.html", null);
        importer().importAll();
        ledger.markFailed(new SourceKey("source-c"), "manual mutation after import");

        var summary = importer().importAll();

        assertThat(summary.scanned()).isEqualTo(1);
        assertThat(summary.imported()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(ledger.find(new SourceKey("source-c")).orElseThrow().status())
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(diagnostics.diagnostics())
                .extracting(diagnostic -> diagnostic.code().id())
                .contains(StorageDiagnosticCodes.IMPORT_IDEMPOTENT_REPLAY.id());
    }

    private LegacyLedgerImporter importer() {
        return new LegacyLedgerImporter(legacyDir, ledger, dataSource, diagnostics,
                new DiagnosticFactory(CLOCK), CLOCK);
    }

    private Path writeLegacy(String name,
                             String sourceKey,
                             IngestionStatus status,
                             List<String> partitions,
                             String archivedPath,
                             String reason) throws Exception {
        Properties props = new Properties();
        props.setProperty("key", sourceKey);
        props.setProperty("status", status.name());
        props.setProperty("originalPath", "inbox/" + sourceKey + ".html");
        props.setProperty("processingPath", "processing/" + sourceKey + ".html");
        props.setProperty("archivedPath", archivedPath == null ? "" : archivedPath);
        props.setProperty("partitions", String.join("\n", partitions));
        props.setProperty("detectedAt", "2026-06-22T00:00:00Z");
        props.setProperty("updatedAt", "2026-06-22T00:00:01Z");
        props.setProperty("reason", reason == null ? "" : reason);
        Path file = legacyDir.resolve(name);
        try (Writer writer = Files.newBufferedWriter(file)) {
            props.store(writer, "ioc ingestion ledger");
        }
        return file;
    }

    private void insertMarker(String name, Path file, String checksum, String status) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO legacy_imports (name, source_path, checksum, status, completed_at)
                     VALUES (?, ?, ?, ?, NULL)
                     """)) {
            statement.setString(1, name);
            statement.setString(2, file.toString());
            statement.setString(3, checksum);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private String markerStatus(String name) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT status FROM legacy_imports WHERE name = ?")) {
            statement.setString(1, name);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private String markerCompletedAt(String name) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT completed_at FROM legacy_imports WHERE name = ?")) {
            statement.setString(1, name);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private List<String> partitionRows(String sourceKey) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT partition_path
                     FROM ingestion_partition
                     WHERE source_key = ?
                     ORDER BY partition_path
                     """)) {
            statement.setString(1, sourceKey);
            try (var resultSet = statement.executeQuery()) {
                var rows = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    rows.add(resultSet.getString(1));
                }
                return rows;
            }
        }
    }
}
