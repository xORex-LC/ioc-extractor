package com.iocextractor.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iocextractor.adapter.in.ingest.LocalImportChangeSignalSource;
import com.iocextractor.adapter.in.ingest.LocalImportSourceDefinition;
import com.iocextractor.adapter.in.ingest.LoggingIngestionStartupObserver;
import com.iocextractor.adapter.out.store.jdbc.JdbcIngestionLedger;
import com.iocextractor.adapter.out.store.jdbc.LegacyLedgerImporter;
import com.iocextractor.adapter.out.store.jdbc.ServiceSchemaMigrations;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.bootstrap.IocEcsStructuredLogEncoder;
import com.iocextractor.bootstrap.LoggingControlEventObserver;
import com.iocextractor.platform.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks real IOC producers through the production encoder and a byte-writing appender. */
@IntegrationTest
@ContractTest
@Timeout(15)
class LoggingMigrationIT {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void control_event_names_are_custom_fields_on_every_observer_path() throws Exception {
        try (var capture = new EcsCapture(LoggingControlEventObserver.class)) {
            var observer = new LoggingControlEventObserver();
            var event = new TestEvent(new ControlEventMetadata("event-1", "export.slice.completed", 1,
                    CLOCK.instant(), "correlation-1", "cause-1"));
            var failure = new IllegalStateException("dispatch failed");
            observer.published(event);
            observer.dispatching(event, "handler");
            observer.publishFailed(event, failure);
            observer.dispatchFailed(event, "handler", failure);

            var records = capture.records();
            assertThat(records).hasSize(4).allSatisfy(json -> {
                assertThat(json.at("/ioc/event/type").textValue()).isEqualTo("export.slice.completed");
                assertThat(json.at("/event/type").isMissingNode()).isTrue();
                assertThat(json.at("/ioc/event/id").textValue()).isEqualTo("event-1");
                assertThat(json.at("/ioc/event/version").longValue()).isEqualTo(1L);
                assertThat(json.at("/ioc/event/correlation_id").textValue()).isEqualTo("correlation-1");
                assertThat(json.at("/ioc/event/causation_id").textValue()).isEqualTo("cause-1");
                assertThat(json.at("/log/logger").textValue()).isEqualTo(LoggingControlEventObserver.class.getName());
            });
            assertThat(records).extracting(json -> json.at("/log/level").textValue())
                    .containsExactly("DEBUG", "DEBUG", "ERROR", "ERROR");
            assertThat(records).extracting(json -> json.at("/event/action").textValue())
                    .containsExactly("event_publish", "event_dispatch", "event_publish", "event_dispatch");
            assertThat(records).extracting(json -> json.at("/event/outcome").textValue())
                    .containsExactly("success", "unknown", "failure", "failure");
            assertThat(records.get(1).at("/ioc/event/handler").textValue()).isEqualTo("handler");
            assertThat(records.get(2).at("/error/type").textValue()).isEqualTo(failure.getClass().getName());
            assertThat(records.get(3).at("/ioc/event/handler").textValue()).isEqualTo("handler");
        }
    }

    @Test
    void current_schema_has_an_informational_disposition_without_ecs_outcome() throws Exception {
        try (var dataSource = dataSource(); var capture = new EcsCapture(SqliteUserVersionSchemaMigrator.class)) {
            var migrator = new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite());
            migrator.migrate();
            capture.clear();
            var result = migrator.migrate();

            assertThat(result.appliedVersions()).isEmpty();
            assertThat(capture.records()).singleElement().satisfies(json -> {
                assertSkipped(json, "schema_migrate", "sqlite schema already current");
                assertThat(json.at("/ioc/db/role").textValue()).isEqualTo("storage");
                assertThat(json.at("/ioc/schema/version").longValue()).isEqualTo(result.currentVersion());
            });
        }
    }

    @Test
    void completed_legacy_import_replay_has_an_informational_disposition() throws Exception {
        try (var dataSource = dataSource()) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            Path legacy = Files.createDirectory(directory.resolve("legacy"));
            Path file = legacy.resolve("completed.properties");
            Files.writeString(file, """
                    key=source-a
                    status=SOURCE_ARCHIVED
                    originalPath=inbox/source-a.html
                    processingPath=processing/source-a.html
                    archivedPath=done/source-a.html
                    detectedAt=2026-10-18T00:00:00Z
                    updatedAt=2026-10-18T00:00:01Z
                    """);
            var importer = new LegacyLedgerImporter(legacy, new JdbcIngestionLedger(dataSource, CLOCK),
                    dataSource, CLOCK);
            assertThat(importer.importAll().imported()).isEqualTo(1);
            try (var capture = new EcsCapture(LegacyLedgerImporter.class)) {
                assertThat(importer.importAll().skipped()).isEqualTo(1);
                assertThat(capture.records()).hasSize(2)
                        .filteredOn(json -> json.at("/ioc/operation/disposition").asText().equals("skipped"))
                        .singleElement().satisfies(json -> {
                    assertSkipped(json, "ledger_import", "legacy ingestion ledger import skipped completed source");
                    assertThat(json.at("/file/path").textValue()).isEqualTo(file.toString());
                });
            }
        }
    }

    @Test
    void watch_provider_failure_writes_complete_exception_json_and_terminates_worker() throws Exception {
        var source = new LocalImportChangeSignalSource(List.of(
                new LocalImportSourceDefinition(new ImportSourceId("local-a"), directory)));
        Thread worker = null;
        try (var capture = new EcsCapture(LocalImportChangeSignalSource.class)) {
            try {
                source.start(ignored -> { });
                // Fail the provider while the source is active. Normal source.close()
                // marks shutdown first and intentionally suppresses this warning.
                var watchField = LocalImportChangeSignalSource.class.getDeclaredField("watchService");
                watchField.setAccessible(true);
                var threadField = LocalImportChangeSignalSource.class.getDeclaredField("thread");
                threadField.setAccessible(true);
                worker = (Thread) threadField.get(source);
                ((WatchService) watchField.get(source)).close();
                worker.join(3000);
                assertThat(worker.isAlive()).as("failed watch worker terminates").isFalse();

                assertThat(capture.records()).singleElement().satisfies(json -> {
                    assertThat(json.at("/event/action").textValue()).isEqualTo("import_change_signal");
                    assertThat(json.at("/event/outcome").textValue()).isEqualTo("failure");
                    assertThat(json.at("/log/level").textValue()).isEqualTo("WARN");
                    assertThat(json.at("/error/type").textValue())
                            .isEqualTo("java.nio.file.ClosedWatchServiceException");
                    assertThat(json.at("/error/stack_trace").textValue())
                            .contains("ClosedWatchServiceException", "LocalImportChangeSignalSource.watch");
                    assertThat(json.at("/message").textValue())
                            .isEqualTo("local managed import change notification stopped; periodic reconcile remains active");
                });
            } finally {
                source.close();
                if (worker != null) {
                    worker.join(3000);
                    assertThat(worker.isAlive()).as("watch worker cleanup").isFalse();
                }
            }
        }
    }

    @Test
    void type_only_error_producers_continue_to_emit_without_exception_stack_trace() throws Exception {
        try (var capture = new EcsCapture(LoggingIngestionStartupObserver.class)) {
            new LoggingIngestionStartupObserver(NoopDiagnosticSink.INSTANCE, CLOCK)
                    .recoveryFailed(CLOCK.instant(), CLOCK.instant(), new IllegalStateException("recovery failed"));

            assertThat(capture.records()).singleElement().satisfies(json -> {
                assertThat(json.at("/event/action").textValue()).isEqualTo("ingest_recover");
                assertThat(json.at("/event/outcome").textValue()).isEqualTo("failure");
                assertThat(json.at("/error/type").textValue()).isEqualTo(IllegalStateException.class.getName());
                assertThat(json.at("/error/stack_trace").isMissingNode()).isTrue();
            });
        }
    }

    private com.zaxxer.hikari.HikariDataSource dataSource() {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                "service", "jdbc:sqlite:" + directory.resolve("service.db"), "low-memory", 1, 1));
    }

    private void assertSkipped(JsonNode json, String action, String message) {
        assertThat(json.at("/ioc/operation/disposition").textValue()).isEqualTo("skipped");
        assertThat(json.at("/event/type").isMissingNode()).isTrue();
        assertThat(json.at("/event/outcome").isMissingNode()).isTrue();
        assertThat(json.at("/event/action").textValue()).isEqualTo(action);
        assertThat(json.at("/log/level").textValue()).isEqualTo("DEBUG");
        assertThat(json.at("/message").textValue()).isEqualTo(message);
    }

    private record TestEvent(ControlEventMetadata metadata) implements ControlEvent { }

    private static final class EcsCapture implements AutoCloseable {
        private final Logger logger;
        private final Level previousLevel;
        private final boolean previousAdditive;
        private final LoggerContext context = new LoggerContext();
        private final IocEcsStructuredLogEncoder encoder = new IocEcsStructuredLogEncoder();
        private final OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private EcsCapture(Class<?> producer) {
            logger = (Logger) LoggerFactory.getLogger(producer);
            previousLevel = logger.getLevel();
            previousAdditive = logger.isAdditive();
            var environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "logging.structured.ecs.service.name", "ioc-extractor")));
            context.putObject(Environment.class.getName(), environment);
            encoder.setContext(context);
            encoder.setFormat("ecs");
            encoder.setEventDataset("ioc-extractor");
            encoder.start();
            appender.setContext(context);
            appender.setName("migration-json");
            appender.setEncoder(encoder);
            appender.setOutputStream(output);
            appender.start();
            logger.setLevel(Level.TRACE);
            logger.setAdditive(false);
            logger.addAppender(appender);
        }

        private void clear() {
            output.reset();
        }

        private List<JsonNode> records() throws Exception {
            assertThat(context.getStatusManager().getCopyOfStatusList())
                    .filteredOn(status -> status.getLevel() >= Status.WARN)
                    .as("encoder/appender warnings and errors").isEmpty();
            var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
            assertThat(lines).as("complete JSON lines written by the appender").isNotEmpty();
            var json = new ObjectMapper();
            var records = new java.util.ArrayList<JsonNode>();
            for (var line : lines) {
                records.add(json.readTree(line));
            }
            return records;
        }

        @Override public void close() {
            logger.detachAppender(appender);
            appender.stop();
            encoder.stop();
            context.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }
}
