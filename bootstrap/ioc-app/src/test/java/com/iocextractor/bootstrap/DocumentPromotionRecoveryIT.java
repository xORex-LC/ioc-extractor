package com.iocextractor.bootstrap;

import com.iocextractor.IocExtractorApplication;
import com.iocextractor.adapter.in.ingest.DurableDocumentDispatcher;
import com.iocextractor.adapter.out.store.jdbc.JdbcConfirmationReceiptStore;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentExecutionState;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.tck.junit.EndToEndTest;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EndToEndTest
@Timeout(120)
class DocumentPromotionRecoveryIT {

    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"disabled,jdbc", "fixed,file"})
    void resumesOnlyOutstandingStepsAtEveryDurableBoundary(String lifecycle, String ledger) throws Exception {
        String service = url("service.db");
        String dataframe = url("dataframe.db");
        String observation;
        List<String> plan;
        List<String> masksCommit;
        Object projectionIdentity;
        Path projection = root.resolve("projection-0.csv");
        Path archived;
        try (var app = start(lifecycle, ledger)) {
            execute(dataframe, """
                    CREATE TRIGGER stop_hashes BEFORE INSERT ON hashes
                    BEGIN SELECT RAISE(ABORT, 'test: stop before second nonempty artifact'); END
                    """);
            Path candidate = root.resolve("inbox/one.html");
            Files.writeString(candidate, """
                    <p>recovery.example</p><p>d41d8cd98f00b204e9800998ecf8427e</p>
                    """);
            var dispatcher = app.getBean(DurableDocumentDispatcher.class);
            dispatcher.handle(candidate.toFile());
            dispatcher.handle(candidate.toFile());
            observe(() -> assertThat(value(service, "SELECT COUNT(*) FROM ingest_run WHERE plan_kind = 'WORKSPACE'"))
                    .isEqualTo("1"));
            observation = value(service, "SELECT observation_id FROM ingest_run");
            observe(() -> {
                assertThat(value(dataframe, "SELECT COUNT(*) FROM canonical_observation_commit WHERE artifact = 'masks'"))
                        .isEqualTo("1");
                assertThat(admission(app, observation).execution().failure())
                        .isEqualTo(DocumentExecutionState.Failure.PROMOTION_RECOVERY_FAILED.name());
            });
            plan = row(service, "SELECT run_id, observation_id, admission_order, plan_token FROM ingest_run");
            masksCommit = maskProof(dataframe);
            assertThat(value(service, "SELECT status FROM ingest_run")).isEqualTo("STARTED");
            assertThat(value(dataframe, "SELECT COUNT(*) FROM hashes")).isEqualTo("0");
            assertThat(admission(app, observation).registration().orElseThrow().admissionOrder().value())
                    .isEqualTo(Long.parseLong(plan.get(2)));
        }

        execute(dataframe, "DROP TRIGGER stop_hashes");
        stopRunTransition(service, "DB_COMMITTED");
        try (var app = start(lifecycle, ledger)) {
            observe(() -> {
                assertThat(value(dataframe, "SELECT COUNT(*) FROM canonical_observation_commit")).isEqualTo("5");
                assertThat(admission(app, observation).execution().failure())
                        .isEqualTo(DocumentExecutionState.Failure.PROMOTION_RECOVERY_FAILED.name());
            });
            assertThat(value(service, "SELECT status FROM ingest_run")).isEqualTo("STARTED");
            assertThat(maskProof(dataframe)).isEqualTo(masksCommit);
            assertThat(value(dataframe, "SELECT COUNT(*) FROM hashes")).isEqualTo("1");
        }

        replaceRunFault(service, "PROJECTION_COMPLETED");
        try (var app = start(lifecycle, ledger)) {
            observe(() -> {
                assertThat(value(service, "SELECT status FROM ingest_run")).isEqualTo("DB_COMMITTED");
                assertThat(projection).content().contains("recovery.example");
                assertThat(admission(app, observation).execution().failure())
                        .isEqualTo(DocumentExecutionState.Failure.PROMOTION_RECOVERY_FAILED.name());
            });
            assertThat(value(dataframe, """
                    SELECT COUNT(*) FROM artifact_projection_state
                    WHERE required_generation > 0 AND required_generation = projected_generation
                    """)).isEqualTo("5");
            projectionIdentity = fileIdentity(projection);
        }

        boolean jdbcLedger = ledger.equals("jdbc");
        execute(service, "DROP TRIGGER stop_run_transition");
        if (jdbcLedger) {
            execute(service, """
                    CREATE TRIGGER stop_source_archive BEFORE UPDATE OF status ON ingestion_ledger
                    WHEN NEW.status = 'SOURCE_ARCHIVED'
                    BEGIN SELECT RAISE(ABORT, 'test: stop after file move'); END
                    """);
        } else {
            stopRunTransition(service, "COMPLETED");
        }
        try (var app = start(lifecycle, ledger)) {
            observe(() -> {
                assertThat(value(service, "SELECT status FROM ingest_run")).isEqualTo("PROJECTION_COMPLETED");
                assertThat(admission(app, observation).execution().failure())
                        .isEqualTo(DocumentExecutionState.Failure.PROMOTION_RECOVERY_FAILED.name());
                try (var files = Files.list(root.resolve("done"))) {
                    assertThat(files.toList()).hasSize(1);
                }
            });
            try (var files = Files.list(root.resolve("done"))) {
                archived = files.findFirst().orElseThrow();
            }
            assertThat(archived).content().contains("recovery.example");
            assertThat(fileIdentity(projection)).isEqualTo(projectionIdentity);
            if (jdbcLedger) {
                assertThat(value(service, "SELECT status FROM ingestion_ledger")).isEqualTo("CLAIMED");
            }
        }

        execute(service, jdbcLedger ? "DROP TRIGGER stop_source_archive" : "DROP TRIGGER stop_run_transition");
        execute(dataframe, """
                CREATE TRIGGER stop_registration BEFORE UPDATE OF terminal_at_ms ON registered_observation
                BEGIN SELECT RAISE(ABORT, 'test: stop during terminal registration'); END
                """);
        try (var app = start(lifecycle, ledger)) {
            observe(() -> {
                var admission = admission(app, observation);
                assertThat(admission.terminalOutcome()).contains(DocumentTerminalOutcome.SUCCEEDED);
                assertThat(admission.registrationFinalized()).isFalse();
            });
            assertThat(value(service, "SELECT status FROM ingest_run")).isEqualTo("COMPLETED");
            assertThat(value(dataframe, "SELECT state FROM canonical_observation")).isEqualTo("OPEN");
            assertThat(filesNamed(root.resolve("workspace"), "recovery")).isOne();
        }

        execute(dataframe, "DROP TRIGGER stop_registration");
        Object archivedIdentity = fileIdentity(archived);
        try (var app = start(lifecycle, ledger)) {
            observe(() -> assertThat(admission(app, observation).registrationFinalized()).isTrue());
        }
        assertThat(value(service, "SELECT COUNT(*) FROM ingest_run")).isEqualTo("1");
        assertThat(row(service, "SELECT run_id, observation_id, admission_order, plan_token FROM ingest_run"))
                .isEqualTo(plan);
        assertThat(maskProof(dataframe)).isEqualTo(masksCommit);
        assertThat(fileIdentity(archived)).isEqualTo(archivedIdentity);
        assertThat(fileIdentity(projection)).isEqualTo(projectionIdentity);
        assertThat(value(dataframe, "SELECT state FROM canonical_observation")).isEqualTo("TERMINAL");
        assertThat(filesNamed(root.resolve("workspace"), "recovery")).isZero();
    }

    @Test
    void resumesThePinnedReceiptAfterItExpiresForNewObservations() throws Exception {
        String service = url("service.db");
        String dataframe = url("dataframe.db");
        String first;
        String second;
        List<String> firstArtifact;
        try (var app = start("fixed", "jdbc")) {
            submit(app, "original.html");
            observe(() -> assertThat(value(service, "SELECT status FROM ingest_run")).isEqualTo("COMPLETED"));
            first = value(service, "SELECT observation_id FROM ingest_run");
            observe(() -> assertThat(admission(app, first).registrationFinalized()).isTrue());
            execute(dataframe, """
                    CREATE TRIGGER stop_receipt_replay BEFORE INSERT ON canonical_observation_commit
                    WHEN NEW.artifact = 'masks'
                    BEGIN SELECT RAISE(ABORT, 'test: stop receipt replay'); END
                    """);
            submit(app, "duplicate.html");
            observe(() -> assertThat(value(service, "SELECT COUNT(*) FROM ingest_run WHERE plan_kind = 'RECEIPT'"))
                    .isEqualTo("1"));
            second = value(service, "SELECT observation_id FROM ingest_run WHERE plan_kind = 'RECEIPT'");
            observe(() -> assertThat(admission(app, second).execution().failure())
                    .isEqualTo(DocumentExecutionState.Failure.PROMOTION_RECOVERY_FAILED.name()));
            assertThat(value(service, "SELECT plan_token FROM ingest_run WHERE plan_kind = 'RECEIPT'"))
                    .isEqualTo("receipt:" + first);
            firstArtifact = replayedHashProof(dataframe);
            assertThat(firstArtifact.getLast()).isEqualTo("1");
            execute(dataframe, """
                    UPDATE confirmation_receipt SET purge_after_ms = completed_at_ms + 1
                    WHERE state = 'COMPLETE'
                    """);
            var receiptStore = app.getBean(JdbcConfirmationReceiptStore.class);
            receiptStore.purgeExpired(EffectiveTime.at(Instant.now()), 20);
            assertThat(value(dataframe, "SELECT COUNT(*) FROM document_confirmation_receipt_pin")).isEqualTo("1");
            assertThat(value(dataframe, "SELECT COUNT(*) FROM confirmation_receipt WHERE state = 'COMPLETE'"))
                    .isEqualTo("1");
        }

        execute(dataframe, "DROP TRIGGER stop_receipt_replay");
        try (var app = start("fixed", "jdbc")) {
            observe(() -> assertThat(admission(app, second).registrationFinalized()).isTrue());
        }
        assertThat(replayedHashProof(dataframe)).isEqualTo(firstArtifact);
        assertThat(value(dataframe, "SELECT COUNT(*) FROM document_confirmation_receipt_pin")).isEqualTo("0");
        assertThat(value(dataframe, "SELECT COUNT(*) FROM masks")).isEqualTo("1");
    }

    private void submit(ConfigurableApplicationContext app, String filename) throws IOException {
        Path candidate = root.resolve("inbox").resolve(filename);
        Files.writeString(candidate, "<p>receipt.example</p><p>d41d8cd98f00b204e9800998ecf8427e</p>");
        app.getBean(DurableDocumentDispatcher.class).handle(candidate.toFile());
    }

    private static List<String> replayedHashProof(String datasource) throws SQLException {
        return row(datasource, """
                SELECT c.effective_as_of_ms, c.artifact_revision, c.projection_generation, c.renewed
                FROM canonical_observation_commit c
                JOIN canonical_observation o ON o.observation_id = c.observation_id
                WHERE c.artifact = 'hashes' AND o.document_plan_id LIKE '%:RECEIPT:%'
                """);
    }

    private ConfigurableApplicationContext start(String lifecycle, String ledger) {
        var args = new ArrayList<>(List.of(
                "--spring.main.banner-mode=off", "--server.port=0", "--logging.level.root=WARN",
                "--ioc.runtime.mode=daemon", "--ioc.export.enabled=false",
                "--ioc.ingestion.ledger.type=" + ledger, "--ioc.ingestion.ledger.path=" + root.resolve("ledger"),
                "--ioc.storage.service.url=" + url("service.db"),
                "--ioc.storage.dataframe.url=" + url("dataframe.db"),
                "--ioc.ingestion.dirs.inbox=" + root.resolve("inbox"),
                "--ioc.ingestion.dirs.processing=" + root.resolve("processing"),
                "--ioc.ingestion.dirs.done=" + root.resolve("done"),
                "--ioc.ingestion.dirs.failed=" + root.resolve("failed"),
                "--ioc.ingestion.retry.max-attempts=50", "--ioc.ingestion.retry.backoff=100ms",
                "--ioc.lifecycle.validity.mode=" + lifecycle, "--ioc.processing.workspace.directory="
                        + root.resolve("workspace")));
        // Override the five projection paths while retaining the complete packaged artifact catalog.
        return new SpringApplicationBuilder(IocExtractorApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(context -> {
                    var environment = context.getEnvironment();
                    var properties = new LinkedHashMap<String, Object>();
                    environment.getPropertySources().forEach(source -> {
                        if (source instanceof EnumerablePropertySource<?> enumerable) {
                            for (String name : enumerable.getPropertyNames()) {
                                if (name.startsWith("ioc.sink.artifacts[")) {
                                    properties.putIfAbsent(name, source.getProperty(name));
                                }
                            }
                        }
                    });
                    for (int i = 0; i < 5; i++) {
                        properties.put("ioc.sink.artifacts[" + i + "].path", root.resolve("projection-" + i + ".csv").toString());
                    }
                    environment.getPropertySources().addFirst(
                            new MapPropertySource("recovery-projection-paths", properties));
                })
                .run(args.toArray(String[]::new));
    }

    private static void observe(ThrowingRunnable assertion) {
        await().atMost(Duration.ofSeconds(12)).untilAsserted(assertion);
    }

    private static DocumentAdmission admission(
            ConfigurableApplicationContext app, String observation) {
        return app.getBean(DocumentAdmissionService.class).find(new ObservationId(observation)).orElseThrow();
    }

    private static List<String> maskProof(String sqlUrl) throws SQLException {
        return row(sqlUrl, """
                SELECT effective_as_of_ms, artifact_revision, projection_generation, id
                FROM canonical_observation_commit, masks WHERE artifact = 'masks'
                """);
    }

    private static void stopRunTransition(String sqlUrl, String status) throws SQLException {
        execute(sqlUrl, """
                CREATE TRIGGER stop_run_transition BEFORE UPDATE OF status ON ingest_run
                WHEN NEW.status = '%s'
                BEGIN SELECT RAISE(ABORT, 'test: stop during run transition'); END
                """.formatted(status));
    }

    private static void replaceRunFault(String sqlUrl, String nextStatus) throws SQLException {
        execute(sqlUrl, "DROP TRIGGER stop_run_transition");
        stopRunTransition(sqlUrl, nextStatus);
    }

    private static long filesNamed(Path directory, String name) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> path.getFileName().toString().equals(name)).count();
        }
    }

    private static Object fileIdentity(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    }

    private String url(String name) {
        return "jdbc:sqlite:" + root.resolve(name);
    }

    private static void execute(String sqlUrl, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(sqlUrl);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String value(String sqlUrl, String sql) throws SQLException {
        return row(sqlUrl, sql).getFirst();
    }

    private static List<String> row(String sqlUrl, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(sqlUrl);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as(sql).isTrue();
            var result = new ArrayList<String>();
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                result.add(rows.getString(i));
            }
            assertThat(rows.next()).as(sql).isFalse();
            return result;
        }
    }
}
