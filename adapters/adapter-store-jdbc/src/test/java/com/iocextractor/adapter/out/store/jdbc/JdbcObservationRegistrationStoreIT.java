package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ManagedImportObservationAdmission;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcObservationRegistrationStoreIT {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(15)
    void registrationIsIdempotentAndOrdersDifferentDeliveryPaths() throws Exception {
        try (HikariDataSource dataSource = dataSource("dataframe", "observations.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
            var store = new JdbcObservationRegistrationStore(dataSource, CLOCK);
            var firstId = new ObservationId("document-1");
            RegisteredObservation first = store.registerNew(firstId, ObservationOrigin.DOCUMENT);

            assertThat(store.registerNew(firstId, ObservationOrigin.DOCUMENT)).isEqualTo(first);
            assertThat(store.resume(firstId, first.namespaceId())).isEqualTo(first);
            assertThatThrownBy(() -> store.registerNew(firstId, ObservationOrigin.MANAGED_IMPORT))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.resume(firstId, "different-namespace"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.resume(new ObservationId("missing"), first.namespaceId()))
                    .isInstanceOf(IllegalStateException.class);

            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var importResult = workers.submit(() -> {
                    start.await();
                    return store.registerNew(new ObservationId("import-2"), ObservationOrigin.MANAGED_IMPORT);
                });
                var oneshotResult = workers.submit(() -> {
                    start.await();
                    return store.registerNew(new ObservationId("oneshot-3"), ObservationOrigin.ONESHOT);
                });
                start.countDown();
                RegisteredObservation imported = importResult.get(10, TimeUnit.SECONDS);
                RegisteredObservation oneshot = oneshotResult.get(10, TimeUnit.SECONDS);
                assertThat(Set.of(first.admissionOrder().value(), imported.admissionOrder().value(),
                        oneshot.admissionOrder().value()))
                        .containsExactlyInAnyOrder(1L, 2L, 3L);
                assertThat(imported.namespaceId()).isEqualTo(first.namespaceId());
                assertThat(oneshot.namespaceId()).isEqualTo(first.namespaceId());
            }

            store.markTerminal(firstId, first.namespaceId());
            store.markTerminal(firstId, first.namespaceId());
            assertThat(store.resume(firstId, first.namespaceId())).isEqualTo(first);

            insertReferencedOrigin(dataSource, first);
            var disposableId = new ObservationId("terminal-disposable");
            RegisteredObservation disposable = store.registerNew(disposableId, ObservationOrigin.ONESHOT);
            store.markTerminal(disposableId, disposable.namespaceId());
            assertThat(store.purgeTerminal(first)).isFalse();
            assertThat(store.purgeTerminal(disposable)).isTrue();
            assertThat(store.resume(firstId, first.namespaceId())).isEqualTo(first);
            assertThatThrownBy(() -> store.resume(disposableId, disposable.namespaceId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing registered observation");

            setNextOrder(dataSource, Long.MAX_VALUE);
            assertThatThrownBy(() -> store.registerNew(
                    new ObservationId("overflow"), ObservationOrigin.DOCUMENT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exhausted");
        }
    }

    @Test
    void jdbcJournalsRecoverDocumentAndManagedImportTerminalHandshakes() {
        try (HikariDataSource dataframe = dataSource("dataframe", "dataframe-journal.db");
             HikariDataSource service = dataSource("service", "service-journal.db")) {
            new SqliteUserVersionSchemaMigrator(dataframe, DataframeFormatMigrations.sqlite()).migrate();
            new SqliteUserVersionSchemaMigrator(service, ServiceSchemaMigrations.sqlite()).migrate();
            var registrations = new JdbcObservationRegistrationStore(dataframe, CLOCK);
            var documentJournal = new JdbcDocumentAdmissionJournal(service);
            var documents = new DocumentAdmissionService(documentJournal, registrations, CLOCK);
            var documentId = new ObservationId("document-jdbc");
            var evidence = new DocumentCandidateEvidence(Optional.of("inode-1"), 8, 10);
            var reservation = new DocumentAdmissionReservation(documentId,
                    tempDir.resolve("inbox/document.docx"), evidence,
                    tempDir.resolve("processing/document.pending"), NOW);

            var ordered = documents.admit(reservation);
            var claimed = documents.recordClaim(documentId, evidence);
            var linked = documents.link(documentId, new SourceKey("digest"));
            var terminalOnly = linked.terminal(DocumentTerminalOutcome.SUCCEEDED, NOW);
            assertThat(documentJournal.replace(linked, terminalOnly)).isTrue();
            assertThat(documents.recover(10)).singleElement()
                    .satisfies(value -> assertThat(value.registrationFinalized()).isTrue());
            assertThat(claimed.registration()).isEqualTo(ordered.registration());

            var imports = new ManagedImportObservationAdmission(registrations,
                    new JdbcObservationAdmissionReferenceStore(service, CLOCK), CLOCK);
            ImportDeliveryId deliveryId = new ImportDeliveryId("import-jdbc");
            RegisteredObservation imported = imports.register(deliveryId);
            imports.complete(deliveryId, "SUCCEEDED");
            assertThat(imports.recover(10)).isEmpty();
            assertThat(imported.admissionOrder())
                    .isEqualTo(new ObservationOrder(ordered.registration().orElseThrow()
                            .admissionOrder().value() + 1));
            assertThat(imports.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isOne();
            assertThatThrownBy(() -> registrations.resume(imported.observationId(),
                    imported.namespaceId())).isInstanceOf(IllegalStateException.class);
        }
    }

    private void insertReferencedOrigin(HikariDataSource dataSource,
                                        RegisteredObservation registration) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO canonical_compat_field_origin(
                       artifact, row_key, identity_epoch, field_name, admission_order,
                       occurrence_position, occurrence_id)
                     VALUES ('aggregate', 'row-1', 1, 'name', ?, 0, ?)
                     """)) {
            statement.setLong(1, registration.admissionOrder().value());
            statement.setString(2, registration.observationId().value());
            statement.executeUpdate();
        }
    }

    private void setNextOrder(HikariDataSource dataSource, long value) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE observation_order_control SET next_order = ? WHERE singleton_id = 1")) {
            statement.setLong(1, value);
            statement.executeUpdate();
        }
    }

    private HikariDataSource dataSource(String poolName, String filename) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(poolName,
                        "jdbc:sqlite:" + tempDir.resolve(filename), "low-memory", 1, 4));
    }
}
