package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.csv.CommonsCsvDelimitedRecordReader;
import com.iocextractor.adapter.out.store.jdbc.ArtifactIdAllocatorDefinition;
import com.iocextractor.adapter.out.store.jdbc.DataframeArtifactSchema;
import com.iocextractor.adapter.out.store.jdbc.DataframeColumn;
import com.iocextractor.adapter.out.store.jdbc.DataframeFormatMigrations;
import com.iocextractor.adapter.out.store.jdbc.DataframeSchemaReconciler;
import com.iocextractor.adapter.out.store.jdbc.JdbcArtifactIdentityStore;
import com.iocextractor.adapter.out.store.jdbc.JdbcCanonicalImportWriter;
import com.iocextractor.adapter.out.store.jdbc.JdbcImportCommitEvidenceStore;
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
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.dataframeimport.DataframeImportStagingService;
import com.iocextractor.application.dataframeimport.ImportStagingCommand;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.contract.DataframeImportRecognizer;
import com.iocextractor.application.dataframeimport.model.ImportCatalogFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Physical selected-route import through stage, coalescing and canonical receipt replay. */
@IntegrationTest
@Timeout(60)
class RouterSelectedImportDeliveryIT {
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path tempDir;

    @Test
    void selectedCsvRowsCoalesceOnFinalHostAndRecoverFromReceiptWithoutStage() throws Exception {
        var routeFixture = new RouterProcessedImportRowPreparerTest();
        try (var route = routeFixture.fixture()) {
            Path input = tempDir.resolve("delivery.csv");
            byte[] bytes = ("ioc\nhttps://EVIL.example/one\nhttp://evil.example/two\n")
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(input, bytes);
            var snapshot = new ImportSnapshot(new ImportSnapshotReference("snapshot:selected"),
                    new ImportSha256(HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes))), bytes.length);
            var delivery = new ImportDeliveryId("selected-import-delivery");
            var source = new ImportSourceId("local-feed");
            var contract = routeFixture.contract();
            var catalog = new DataframeImportCatalog(true,
                    Map.of(source, new DataframeImportCatalogDraft.Source(source.value(),
                            ImportSourceTransport.LOCAL, tempDir.toString(), null,
                            List.of(contract.id().value()), "standard")),
                    Map.of(), Map.of(contract.id(), contract),
                    new ImportCatalogFingerprint("b".repeat(64)));
            var reader = new CommonsCsvDelimitedRecordReader(ignored -> input);
            Path workspaceRoot = tempDir.resolve("workspace");
            var workspace = new JdbcImportWorkspace(workspaceRoot, ImportWorkspaceLimits.defaults(), CLOCK);
            var staging = new DataframeImportStagingService(
                    new DataframeImportRecognizer(catalog, reader), route.mapper(), reader,
                    workspace, ImportWorkspaceLimits.defaults());
            var staged = staging.stage(new ImportStagingCommand(delivery, source, snapshot));

            assertThat(staged.stage().sourceRows()).isEqualTo(2);
            assertThat(staged.stage().acceptedRows()).isOne();
            assertThat(staged.stage().rejectedRows()).isZero();

            try (HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy())
                    .create(new SqliteDataSourceSettings("dataframe",
                            "jdbc:sqlite:" + tempDir.resolve("canonical.db"), "low-memory", 4, 4))) {
                var schemas = List.of(new DataframeArtifactSchema("masks", List.of(
                        new DataframeColumn("id", "INTEGER"), new DataframeColumn("mask", "TEXT"),
                        new DataframeColumn("alternate", "TEXT"),
                        new DataframeColumn("source", "TEXT"))));
                var identities = List.of(new ArtifactIdentityDefinition("masks",
                        new CanonicalKeyDefinition("mask-row-v1", CanonicalKeyMode.COMPOSITE,
                                List.of("mask")), List.of(), 1));
                new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
                new DataframeSchemaReconciler(dataSource).reconcile(schemas);
                new JdbcArtifactIdentityStore(dataSource, CLOCK).ensureAll(identities);
                var control = new JdbcLifecycleControlStore(dataSource, schemas);
                var disabled = control.load();
                var activating = disabled.beginActivation("selected-import-fixed-12h-v1");
                assertThat(control.compareAndSet(disabled, activating)).isTrue();
                assertThat(control.compareAndSet(activating,
                        activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();

                var writer = new JdbcCanonicalImportWriter(dataSource, schemas,
                        List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1)),
                        identities, workspaceRoot,
                        new JdbcLifecycleClock(dataSource, CLOCK,
                                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30))),
                        new FixedRecordValidityPolicy(Duration.ofHours(12)), CLOCK,
                        new JdbcWriterAdmission());
                var command = new CanonicalImportCommand(delivery, new ImportDeliverySequence(1), source,
                        snapshot, staged.contract(), staged.stage());
                writer.promote(command);
                workspace.discard(delivery);

                var receipt = new JdbcImportCommitEvidenceStore(dataSource).find(delivery).orElseThrow();
                assertThat(receipt.acceptedRows()).isOne();
                assertThat(receipt.rejectedRows()).isZero();
                assertThat(receipt.terminalOutcome()).isEqualTo(ImportTerminalOutcome.SUCCEEDED);
                assertThat(receipt.affectedArtifacts()).containsExactly("masks");
                try (Connection connection = dataSource.getConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT mask FROM masks")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("evil.example");
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }
}
