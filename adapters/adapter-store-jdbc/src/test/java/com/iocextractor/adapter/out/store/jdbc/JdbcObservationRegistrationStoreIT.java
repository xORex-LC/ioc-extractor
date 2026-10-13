package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ManagedImportObservationAdmission;
import com.iocextractor.application.observation.ObservationAdmissionReference;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.ObservationRegistrationPurgeOutcome;
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
    void terminalTimeCannotPrecedeRegistrationWhenWallClockMovesBackward() throws Exception {
        try (HikariDataSource dataSource = dataSource("dataframe", "backward-clock.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
            var id = new ObservationId("clock-regression");
            var registered = new JdbcObservationRegistrationStore(dataSource, CLOCK)
                    .registerNew(id, ObservationOrigin.ONESHOT);
            var earlier = new JdbcObservationRegistrationStore(dataSource,
                    Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC));

            earlier.markTerminal(id, registered.namespaceId());

            try (var connection = dataSource.getConnection();
                 var query = connection.createStatement().executeQuery(
                         "SELECT registered_at_ms, terminal_at_ms FROM registered_observation "
                                 + "WHERE occurrence_id = 'clock-regression'")) {
                assertThat(query.next()).isTrue();
                assertThat(query.getLong("terminal_at_ms")).isEqualTo(query.getLong("registered_at_ms"));
            }
        }
    }

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
            assertThat(store.purgeTerminalSafely(disposable))
                    .isEqualTo(ObservationRegistrationPurgeOutcome.MISSING);
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
    void jdbcJournalsRecoverDocumentAndManagedImportTerminalHandshakes() throws Exception {
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
            RegisteredObservation documentRegistration = ordered.registration().orElseThrow();
            assertThat(registrations.purgeTerminal(documentRegistration)).isTrue();
            assertThat(documents.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isOne();
            assertThat(documentJournal.find(documentId)).isEmpty();

            var imports = new ManagedImportObservationAdmission(registrations,
                    new JdbcObservationAdmissionReferenceStore(service, CLOCK), CLOCK);
            insertLegacyImport(service, "legacy-import");
            assertThatThrownBy(() -> imports.register(new ImportDeliveryId("legacy-import")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("drain legacy work");

            ImportDeliveryId deliveryId = new ImportDeliveryId("import-jdbc");
            new JdbcImportDeliveryLedger(service).reserveClaim(new ImportClaimReservation(
                    deliveryId, new ImportSourceId("source-jdbc"), "candidate-jdbc", NOW));
            RegisteredObservation imported = imports.register(deliveryId);
            imports.complete(deliveryId, "SUCCEEDED");
            assertThat(imports.recover(10)).isEmpty();
            assertThat(imported.admissionOrder())
                    .isEqualTo(new ObservationOrder(ordered.registration().orElseThrow()
                            .admissionOrder().value() + 1));
            assertThat(imports.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isZero();
            markImportTerminal(service, deliveryId);
            assertThat(imports.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isOne();
            assertThatThrownBy(() -> registrations.resume(imported.observationId(),
                    imported.namespaceId())).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void documentRetryStateAndAdmissionOrderSurviveJournalReopening() throws Exception {
        try (HikariDataSource dataframe = dataSource("dataframe", "retry-dataframe.db");
             HikariDataSource service = dataSource("service", "retry-service.db")) {
            new SqliteUserVersionSchemaMigrator(dataframe, DataframeFormatMigrations.sqlite()).migrate();
            new SqliteUserVersionSchemaMigrator(service, ServiceSchemaMigrations.sqlite()).migrate();
            var registrations = new JdbcObservationRegistrationStore(dataframe, CLOCK);
            var documents = new DocumentAdmissionService(new JdbcDocumentAdmissionJournal(service), registrations, CLOCK);
            var firstId = new ObservationId("z-first");
            var secondId = new ObservationId("a-second");
            var evidence = new DocumentCandidateEvidence(Optional.of("inode"), 8, 10);
            var first = documents.admit(new DocumentAdmissionReservation(firstId,
                    tempDir.resolve("first.html"), evidence, tempDir.resolve("first.claim"), NOW));
            documents.admit(new DocumentAdmissionReservation(secondId,
                    tempDir.resolve("second.html"), evidence, tempDir.resolve("second.claim"), NOW.minusSeconds(1)));
            documents.beginExecution(firstId);
            documents.retryExecution(firstId, NOW.plusSeconds(20), "preparation failed");

            var reopened = new DocumentAdmissionService(new JdbcDocumentAdmissionJournal(service), registrations, CLOCK);
            assertThat(reopened.pending(2)).extracting(value -> value.observationId())
                    .containsExactly(firstId, secondId);
            assertThat(reopened.find(firstId)).get().satisfies(value -> {
                assertThat(value.registration()).isEqualTo(first.registration());
                assertThat(value.execution().attempts()).isOne();
                assertThat(value.execution().retryAfter()).isEqualTo(NOW.plusSeconds(20));
                assertThat(value.execution().failure()).isEqualTo("preparation failed");
            });
            var resumed = reopened.beginExecution(firstId);
            assertThat(resumed.execution().attempts()).isEqualTo(2);
            assertThat(resumed.execution().retryAfter()).isEqualTo(Instant.EPOCH);
            assertThat(resumed.registration()).isEqualTo(first.registration());
        }
    }

    @Test
    void documentReservationRetryCannotReplaceOwnedSourceEvidence() throws Exception {
        try (HikariDataSource service = dataSource("service", "reservation-authority.db")) {
            new SqliteUserVersionSchemaMigrator(service, ServiceSchemaMigrations.sqlite()).migrate();
            var journal = new JdbcDocumentAdmissionJournal(service);
            var id = new ObservationId("reserved-authority");
            var candidate = tempDir.resolve("inbox/source.html");
            var claim = tempDir.resolve("processing/source.claim");
            var evidence = new DocumentCandidateEvidence(Optional.empty(), 8, 10);
            var reservation = new DocumentAdmissionReservation(id, candidate, evidence, claim, NOW);
            var original = journal.reserve(reservation);

            assertThat(journal.reserve(new DocumentAdmissionReservation(
                    id, candidate, evidence, claim, NOW.plusSeconds(1)))).isEqualTo(original);
            for (var incompatible : java.util.List.of(
                    new DocumentAdmissionReservation(id, candidate.resolveSibling("other.html"), evidence, claim, NOW),
                    new DocumentAdmissionReservation(id, candidate,
                            new DocumentCandidateEvidence(Optional.empty(), 9, 10), claim, NOW),
                    new DocumentAdmissionReservation(id, candidate, evidence, claim.resolveSibling("other.claim"), NOW))) {
                assertThatThrownBy(() -> journal.reserve(incompatible))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed on retry");
                assertThat(journal.find(id)).contains(original);
            }
            assertThat(journal.findRecoverable(1)).containsExactly(original);
            assertThat(journal.findTerminalBefore(NOW.plusSeconds(1), 1)).isEmpty();
            assertThat(journal.purgeTerminal(id, original.version())).isFalse();
            assertThatThrownBy(() -> journal.findRecoverable(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> journal.replace(original, original)).isInstanceOf(IllegalArgumentException.class);
            var other = journal.reserve(new DocumentAdmissionReservation(new ObservationId("other-occurrence"),
                    candidate.resolveSibling("other.html"), evidence, claim.resolveSibling("other.claim"), NOW));
            var registration = new RegisteredObservation(other.observationId(), "namespace", new ObservationOrder(1),
                    ObservationOrigin.DOCUMENT);
            assertThatThrownBy(() -> journal.replace(original, other.ordered(registration, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
            var retry = original.execution(new com.iocextractor.application.ingest.admission.DocumentExecutionState(
                    1, NOW.plusSeconds(10), "registration unavailable"), NOW);
            assertThat(journal.replace(original, retry)).isTrue();
            assertThat(journal.replace(original, retry)).isFalse();
            assertThat(new JdbcDocumentAdmissionJournal(service).find(id)).contains(retry);
            assertThat(service.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test
    void importReferencePreservesNamespaceAndRankAcrossRetriesAndStaleFinalization() throws Exception {
        try (HikariDataSource service = dataSource("service", "reference-authority.db")) {
            new SqliteUserVersionSchemaMigrator(service, ServiceSchemaMigrations.sqlite()).migrate();
            var store = new JdbcObservationAdmissionReferenceStore(service, CLOCK);
            var id = new ObservationId("reference-authority");
            var registration = new RegisteredObservation(id, "namespace", new ObservationOrder(7),
                    ObservationOrigin.MANAGED_IMPORT);
            var original = store.link(registration);
            assertThat(store.link(registration)).isEqualTo(original);
            assertThat(store.findUnfinalized(1)).containsExactly(original);
            for (var incompatible : java.util.List.of(
                    new RegisteredObservation(id, "other-namespace", new ObservationOrder(7), ObservationOrigin.MANAGED_IMPORT),
                    new RegisteredObservation(id, "namespace", new ObservationOrder(8), ObservationOrigin.MANAGED_IMPORT))) {
                assertThatThrownBy(() -> store.link(incompatible))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed on retry");
                var replacement = new ObservationAdmissionReference(incompatible, 1, Optional.empty(), false, NOW, NOW);
                assertThatThrownBy(() -> store.replace(original, replacement)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> store.link(new RegisteredObservation(id, "namespace", new ObservationOrder(7),
                    ObservationOrigin.DOCUMENT))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.replace(original, original)).isInstanceOf(IllegalArgumentException.class);
            var other = new ObservationAdmissionReference(new RegisteredObservation(new ObservationId("other-import"),
                    "namespace", new ObservationOrder(9), ObservationOrigin.MANAGED_IMPORT),
                    1, Optional.empty(), false, NOW, NOW);
            assertThatThrownBy(() -> store.replace(original, other)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.purgeFinalized(id, original.version())).isFalse();
            var terminal = original.terminal("SUCCEEDED", NOW);
            assertThat(store.replace(original, terminal)).isTrue();
            assertThat(store.replace(original, terminal)).isFalse();
            assertThat(store.replace(terminal, terminal.finalized(NOW))).isTrue();
            assertThat(store.findUnfinalized(1)).isEmpty();
            assertThat(store.findFinalizedBefore(NOW, 1)).isEmpty();
            var eligible = store.findFinalizedBefore(NOW.plusMillis(1), 1);
            assertThat(eligible).containsExactly(terminal.finalized(NOW));
            assertThat(store.purgeFinalized(id, terminal.version())).isFalse();
            assertThat(store.purgeFinalized(id, eligible.getFirst().version())).isTrue();
            assertThat(store.find(id)).isEmpty();
            assertThatThrownBy(() -> store.findUnfinalized(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.findFinalizedBefore(NOW, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test
    void ignoredReservationWritesFailRatherThanInventingDurableOwnership() throws Exception {
        try (HikariDataSource service = dataSource("service", "ignored-reservations.db")) {
            new SqliteUserVersionSchemaMigrator(service, ServiceSchemaMigrations.sqlite()).migrate();
            try (var connection = service.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER ignore_document BEFORE INSERT ON document_admission BEGIN SELECT RAISE(IGNORE); END");
                statement.execute("CREATE TRIGGER ignore_import BEFORE INSERT ON observation_admission_reference BEGIN SELECT RAISE(IGNORE); END");
            }
            var id = new ObservationId("ignored-reservation");
            var journal = new JdbcDocumentAdmissionJournal(service);
            assertThatThrownBy(() -> journal.reserve(new DocumentAdmissionReservation(id,
                    tempDir.resolve("source.html"), new DocumentCandidateEvidence(Optional.empty(), 8, 10),
                    tempDir.resolve("source.claim"), NOW)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed no rows");
            assertThat(journal.find(id)).isEmpty();
            var references = new JdbcObservationAdmissionReferenceStore(service, CLOCK);
            assertThatThrownBy(() -> references.link(new RegisteredObservation(id, "namespace",
                    new ObservationOrder(1), ObservationOrigin.MANAGED_IMPORT)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed no rows");
            assertThat(references.find(id)).isEmpty();
            assertThat(service.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private void markImportTerminal(HikariDataSource dataSource, ImportDeliveryId deliveryId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE import_delivery
                     SET state = 'TERMINAL', terminal_outcome = 'SUCCEEDED',
                         terminal_at_ms = ?, updated_at_ms = ?, purge_after_ms = ?
                     WHERE delivery_id = ?
                     """)) {
            statement.setLong(1, NOW.toEpochMilli());
            statement.setLong(2, NOW.toEpochMilli());
            statement.setLong(3, NOW.plusSeconds(1).toEpochMilli());
            statement.setString(4, deliveryId.value());
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private void insertLegacyImport(HikariDataSource dataSource, String deliveryId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO import_delivery(
                       delivery_id, source_id, candidate_token, state, version, attempt_count,
                       created_at_ms, updated_at_ms)
                     VALUES (?, 'legacy-source', 'legacy-candidate', 'DETECTED', 0, 0, ?, ?)
                     """)) {
            statement.setString(1, deliveryId);
            statement.setLong(2, NOW.toEpochMilli());
            statement.setLong(3, NOW.toEpochMilli());
            statement.executeUpdate();
        }
    }

    @Test
    void statusAndBoundedCleanupKeepUnresolvedOneshotVisible() {
        try (HikariDataSource dataSource = dataSource("dataframe", "observation-status.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
            var registrations = new JdbcObservationRegistrationStore(dataSource, CLOCK);
            var status = new JdbcObservationRegistrationStatusReader(dataSource);
            RegisteredObservation document = registrations.registerNew(
                    new ObservationId("document-pending"), ObservationOrigin.DOCUMENT);
            RegisteredObservation unresolved = registrations.registerNew(
                    new ObservationId("oneshot-unresolved"), ObservationOrigin.ONESHOT);
            RegisteredObservation completed = registrations.registerNew(
                    new ObservationId("oneshot-completed"), ObservationOrigin.ONESHOT);
            registrations.markTerminal(completed.observationId(), completed.namespaceId());

            assertThat(status.status()).satisfies(value -> {
                assertThat(value.pendingTotal()).isEqualTo(2);
                assertThat(value.pendingOneshot()).isOne();
                assertThat(value.oldestPendingOneshot()).contains(NOW);
            });
            assertThat(registrations.purgeTerminalOneshotBefore(NOW.plusMillis(1), 1)).isOne();
            assertThat(registrations.resume(document.observationId(), document.namespaceId()))
                    .isEqualTo(document);
            assertThat(registrations.resume(unresolved.observationId(), unresolved.namespaceId()))
                    .isEqualTo(unresolved);
            assertThatThrownBy(() -> registrations.resume(
                    completed.observationId(), completed.namespaceId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing registered observation");
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
