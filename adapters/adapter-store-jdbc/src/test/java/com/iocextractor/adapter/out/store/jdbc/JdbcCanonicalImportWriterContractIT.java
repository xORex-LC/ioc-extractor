package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.CanonicalMatchCardinality;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.DataframeImportPromotionService;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRequestedSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportRowWarning;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.tck.dataframeimport.CanonicalImportWriterContractTest;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the canonical import TCK and atomicity matrix against real SQLite files. */
@IntegrationTest
@ContractTest
class JdbcCanonicalImportWriterContractIT extends CanonicalImportWriterContractTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void stagedConfigurationRejectsIncompleteOrAmbiguousCatalogsBeforeTouchingCanonicalTables() {
        var canonical = environment("invalid-staged-catalog");
        var configuration = stagedConfiguration(canonical, CLOCK);
        assertThatThrownBy(() -> withCatalog(configuration, List.of(), configuration.publicIds(), configuration.identities(), Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalog or retention");
        assertThatThrownBy(() -> withCatalog(configuration, java.util.Collections.nCopies(129, canonical.schemas.getFirst()),
                configuration.publicIds(), configuration.identities(), Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalog or retention");
        for (Duration retention : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, configuration.publicIds(), canonical.identities, Map.of(), retention))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalog or retention");
        }
        assertThatThrownBy(() -> withCatalog(configuration, List.of(canonical.schemas.getFirst(), canonical.schemas.getFirst()),
                configuration.publicIds(), canonical.identities, Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalogs disagree");
        assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, configuration.publicIds(),
                List.of(canonical.identities.getFirst()), Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalogs disagree");
        assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, configuration.publicIds(), canonical.identities,
                Map.of("unknown", ArtifactWritePolicy.keepFirst()), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalogs disagree");
        assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, List.of(), canonical.identities, Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity epoch");
        var wrongIds = List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 2), configuration.publicIds().get(1));
        assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, wrongIds, canonical.identities, Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity epoch");
        var extraIds = new ArrayList<>(configuration.publicIds());
        extraIds.add(new ArtifactIdAllocatorDefinition("unknown", ArtifactIdStrategy.ASCENDING, 1, 1));
        assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, extraIds, canonical.identities, Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ID catalog differs");
        for (var shape : List.of(new DataframeArtifactSchema("masks", List.of()),
                new DataframeArtifactSchema("masks", List.of(new DataframeColumn("mask"), new DataframeColumn("mask"))))) {
            assertThatThrownBy(() -> withCatalog(configuration, List.of(shape, canonical.schemas.get(1)), configuration.publicIds(),
                    canonical.identities, Map.of(), Duration.ofDays(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("native schema");
        }
        assertThatThrownBy(() -> withCatalog(configuration, List.of(schema("publication_reserved", "mask")), List.of(),
                List.of(identity("publication_reserved", "mask")), Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("native schema");
        assertThatThrownBy(() -> withCatalog(configuration, canonical.schemas, configuration.publicIds(),
                List.of(identity("masks", "unavailable"), canonical.identities.get(1)), Map.of(), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown public fields");
        assertThat(canonical.count("masks")).isZero();
        assertThat(canonical.count("artifact_id_allocator")).isZero();
    }

    @Test
    void stagedReopenRefusesChangedPolicyPinsOrMissingConfigurationBeforeReadingBusinessData() throws Exception {
        var canonical = environment("staged-reopen-pins");
        var configuration = stagedConfiguration(canonical, CLOCK);
        JdbcStagedCanonicalBackend.createEmpty(configuration, JdbcPublicationObserver.NOOP);
        var changed = withCatalog(configuration, configuration.schemas(), configuration.publicIds(), configuration.identities(),
                Map.of("masks", ArtifactWritePolicy.keepFirst()), Duration.ofDays(90));
        assertThatThrownBy(() -> JdbcStagedCanonicalBackend.reopen(changed, JdbcPublicationObserver.NOOP))
                .hasRootCauseMessage("Staged canonical configuration changed; drained migration required");
        try (var connection = canonical.dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM publication_configuration");
        }
        assertThatThrownBy(() -> JdbcStagedCanonicalBackend.reopen(configuration, JdbcPublicationObserver.NOOP))
                .hasRootCauseMessage("Staged canonical configuration changed; drained migration required");
        assertThat(canonical.count("masks")).isZero();
    }

    private static JdbcStagedCanonicalConfiguration withCatalog(JdbcStagedCanonicalConfiguration original,
            List<DataframeArtifactSchema> schemas, List<ArtifactIdAllocatorDefinition> ids,
            List<ArtifactIdentityDefinition> identities, Map<String, ArtifactWritePolicy> policies, Duration retention) {
        return new JdbcStagedCanonicalConfiguration(original.dataSource(), schemas, ids, identities,
                original.clock(), original.allocatorClock(), original.validity(), original.admission(), original.workspaces(),
                original.importWorkspaceRoot(), policies, retention);
    }

    @Test
    void stagedInitializationRefusesPopulatedDataOrUnownedTransactionAndReopenRefusesCatalogDrift() throws Exception {
        var canonical = environment("staged-init-guards");
        var configuration = stagedConfiguration(canonical, CLOCK);
        var command = canonical.stage("existing-native", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "retained.example", "source", "feed"), OptionalLong.empty()))), List.of());
        canonical.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        assertThatThrownBy(() -> JdbcStagedCanonicalBackend.createEmpty(configuration, JdbcPublicationObserver.NOOP))
                .hasRootCauseMessage("Candidate initialization requires an empty native table: masks");
        assertThat(canonical.queryString("SELECT mask FROM masks")).isEqualTo("retained.example");
        var fresh = environment("staged-empty-guards");
        try (var connection = fresh.dataSource.getConnection()) {
            assertThatThrownBy(() -> JdbcTypedPublicationStore.initializeEmpty(connection, fresh.schemas))
                    .isInstanceOf(SQLException.class).hasMessageContaining("owned transaction");
            connection.setAutoCommit(false);
            try (var update = connection.createStatement()) {
                update.executeUpdate("UPDATE canonical_lifecycle_control SET state='ACTIVATING',activated_at_ms=NULL");
            }
            assertThatThrownBy(() -> JdbcTypedPublicationStore.initializeEmpty(connection, fresh.schemas))
                    .isInstanceOf(SQLException.class).hasMessageContaining("active fixed lifecycle");
            connection.rollback();
        }
        JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(fresh, CLOCK), JdbcPublicationObserver.NOOP);
        try (var connection = fresh.dataSource.getConnection()) {
            assertThatThrownBy(() -> JdbcTypedPublicationStore.reopen(connection, List.of(schema("unknown", "value"))))
                    .isInstanceOf(SQLException.class).hasMessageContaining("catalog changed");
            try (var update = connection.createStatement()) {
                update.executeUpdate("DELETE FROM publication_native_schema");
            }
            assertThatThrownBy(() -> JdbcTypedPublicationStore.reopen(connection, fresh.schemas))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Missing staged-publication catalog");
        }
    }

    @Test
    void stagedMaintenanceRejectsUnboundedPagesAndUnknownArtifactsWithoutCreatingPublicationWork() {
        var canonical = environment("staged-maintenance-bounds");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        var asOf = EffectiveTime.at(NOW);
        assertThat(backend.expiry().nearestDeadline()).isEmpty();
        for (int limit : List.of(0, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> backend.expiry().expireDue("masks", asOf, limit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch size");
            assertThatThrownBy(() -> backend.history().purge("masks", asOf, limit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch size");
            assertThatThrownBy(() -> backend.receipts(Duration.ofDays(30)).purgeExpired(asOf, limit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch size");
        }
        assertThatThrownBy(() -> backend.expiry().expireDue("unknown", asOf, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(canonical.count("publication_unit")).isZero();
        assertThat(canonical.count("masks")).isZero();
    }

    @Test
    @Timeout(30)
    void stagedControlFailureCancelsHiddenChangesWithoutTakingPromotionOwnership() {
        var canonical = environment("control-cancellation");
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.HIDDEN_CHUNK && fail.getAndSet(false)) { throw new IllegalStateException("control interrupted"); }
        });
        backend.imports().promote(canonical.stage("control-seed", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "control.example"), OptionalLong.empty()))), List.of()));
        var before = backend.projections().load("masks");
        fail.set(true);
        assertThatThrownBy(() -> backend.projections().recordFailure("masks", before.requiredGeneration(), "PROJECTION.FAILED"))
                .isInstanceOf(IllegalStateException.class).hasMessage("control interrupted");
        assertThat(backend.projections().load("masks")).isEqualTo(before);
        assertThat(canonical.queryLong("SELECT COUNT(*) FROM publication_unit WHERE state='CANCELLED'")).isOne();
        assertThat(canonical.count("publication_owner_failure")).isZero();
        backend.projections().recordFailure("masks", before.requiredGeneration(), "PROJECTION.FAILED");
        assertThat(backend.projections().load("masks")).isEqualTo(before);
        assertThat(canonical.queryString("SELECT last_error_code FROM artifact_projection_state WHERE artifact='masks'"))
                .isEqualTo("PROJECTION.FAILED");
    }

    @Test
    @Timeout(30)
    void stagedFatalPreparationReleasesOwnershipAndRetryReclaimsOnlyObsoleteAttemptEvidence() {
        var canonical = environment("fatal-preparation");
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var failure = new AssertionError("private preparation failure");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.SEALED && fail.getAndSet(false)) { throw failure; }
        });
        var command = canonical.stage("fatal-retry", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "fatal.example", "source", "feed"), OptionalLong.empty()))), List.of());
        assertThatThrownBy(() -> backend.imports().promote(command)).isSameAs(failure);
        assertThat(canonical.count("masks")).isZero();
        assertThat(backend.importEvidence().find(command.deliveryId())).isEmpty();
        assertThat(backend.imports().promote(command).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        for (int pass = 0; pass < 100; pass++) {
            assertThat(backend.cleanup().purgeJournal(1).removed()).isBetween(0, 1);
        }
        assertThat(canonical.count("publication_attempt")).isOne();
        assertThat(canonical.count("publication_fact")).isZero();
        canonical.deleteStage(command);
        assertThat(backend.imports().promote(command).outcome()).isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
    }

    @Test
    @Timeout(60)
    void stagedOrderedFieldsPreserveNativeTriStateAndMergePolicyOutcomesAcrossRegisteredObservations() throws Exception {
        var canonical = environment("ordered-staged-matrix");
        var oracle = environment("ordered-native-matrix");
        var fieldPolicy = new ArtifactWritePolicy(ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST, null,
                Map.of("description", ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
        var configuration = stagedConfiguration(canonical, CLOCK);
        var backend = JdbcStagedCanonicalBackend.createEmpty(withCatalog(configuration, configuration.schemas(), configuration.publicIds(),
                configuration.identities(), Map.of("masks", fieldPolicy), configuration.importReceiptRetention()), JdbcPublicationObserver.NOOP);
        var nativeWriter = oracle.policyWriter(Map.of("masks", fieldPolicy));
        var inputs = List.of(ImportCell.absent(), ImportCell.nullValue(), ImportCell.value(" "), ImportCell.value("seed"), ImportCell.value("new"));
        for (var merge : ImportMergePolicy.values()) {
            for (int index = 0; index < inputs.size(); index++) {
                String name = merge.name().toLowerCase(java.util.Locale.ROOT) + "-" + index;
                String mask = name + ".example";
                for (var environment : List.of(canonical, oracle)) {
                    var seed = environment.stage(name + "-seed", ImportPromotionPolicy.defaults(), List.of(row(2,
                            branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                    values("mask", mask, "description", "seed"), OptionalLong.empty()))), List.of());
                    var incoming = new LinkedHashMap<>(values("mask", mask)); incoming.put("description", inputs.get(index));
                    var command = environment.stage(name + "-next", ImportPromotionPolicy.defaults(), List.of(row(2,
                            branch(environment, "masks", ImportArtifactRole.PRIMARY, incoming,
                                    Map.of("mask", ImportMergePolicy.KEEP_EXISTING, "description", merge), OptionalLong.empty()))), List.of());
                    var registrations = new JdbcObservationRegistrationStore(environment.dataSource, CLOCK);
                    var first = registrations.registerNew(new ObservationId(seed.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
                    var second = registrations.registerNew(new ObservationId(command.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
                    var writer = environment == canonical ? backend.imports() : nativeWriter;
                    writer.promote(withRegistration(seed, first)); writer.promote(withRegistration(command, second));
                }
                for (var table : backend.store().tables()) {
                    assertThat(typedRows(canonical.dataSource, table.name())).as("ordered native oracle: %s/%s", name, table.name())
                            .isEqualTo(typedRows(oracle.dataSource, table.name()));
                }
            }
        }
        assertThat(canonical.count("masks")).isEqualTo(25);
        assertThat(canonical.count("import_commit")).isEqualTo(50);
    }

    @Test
    @Timeout(30)
    void checkedPreparationFailuresReleaseOwnedResourcesForPromotionAndControl() {
        var canonical = environment("checked-preparation-failure");
        var configuration = stagedConfiguration(canonical, CLOCK);
        var backend = JdbcStagedCanonicalBackend.createEmpty(configuration, JdbcPublicationObserver.NOOP);
        for (boolean promotion : List.of(true, false)) {
            var task = new JdbcStagedPublication.NativeTask() {
                public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base, JdbcPublicationSlotBase slots,
                                    EffectiveTime asOf) throws SQLException { base.metadata("masks"); }
                public void prepare(javax.sql.DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) throws SQLException {
                    throw new SQLException("native preparation refused");
                }
            };
            var unit = new JdbcStagedPublication.Unit("checked-failure-" + promotion, "checked-pins", java.util.Set.of("artifact:masks"), promotion);
            assertThatThrownBy(() -> backend.publication().execute(unit, task)).hasRootCauseMessage("native preparation refused");
            assertThat(configuration.workspaces().pressure().leasedWorkspaces()).isZero();
            assertThat(canonical.count("masks")).isZero();
        }
        assertThat(canonical.count("publication_owner_failure")).isOne();
        assertThat(canonical.count("publication_unit")).isZero();
        assertThat(canonical.queryString("SELECT unit FROM publication_logical_owner")).isNull();
    }

    @Test
    void publicationUnitRejectsUnboundedOrUnpinnedWorkBeforeStartingPreparation() {
        var bounded = java.util.Set.of("artifact:masks");
        assertThatThrownBy(() -> new JdbcStagedPublication.Unit(" ", "pins", bounded, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcStagedPublication.Unit("unit", " ", bounded, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcStagedPublication.Unit("unit", "pins", java.util.Set.of(), true)).isInstanceOf(IllegalArgumentException.class);
        var scopes = new java.util.HashSet<String>();
        for (int index = 0; index < 257; index++) { scopes.add("artifact:" + index); }
        assertThatThrownBy(() -> new JdbcStagedPublication.Unit("unit", "pins", scopes, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(30)
    void stagedImportRejectsForgedObservationAuthorityBeforeExposingCanonicalData() {
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            var canonical = environment("forged-registration-" + mismatch);
            var configuration = stagedConfiguration(canonical, CLOCK);
            var policy = new ArtifactWritePolicy(ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST, null,
                    Map.of("description", ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
            var backend = JdbcStagedCanonicalBackend.createEmpty(withCatalog(configuration, configuration.schemas(),
                    configuration.publicIds(), configuration.identities(), Map.of("masks", policy), configuration.importReceiptRetention()),
                    JdbcPublicationObserver.NOOP);
            var registrations = new JdbcObservationRegistrationStore(canonical.dataSource, CLOCK);
            var command = canonical.stage("forged-" + mismatch, ImportPromotionPolicy.defaults(), List.of(row(2,
                    branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                            values("mask", "forged-" + mismatch + ".example", "description", "ranked"), OptionalLong.empty()))), List.of());
            var genuine = registrations.registerNew(new ObservationId(command.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
            var forged = new RegisteredObservation(
                    mismatch == 0 ? new ObservationId("unregistered") : genuine.observationId(),
                    mismatch == 3 ? "another-namespace" : genuine.namespaceId(),
                    mismatch == 1 ? new com.iocextractor.application.observation.ObservationOrder(genuine.admissionOrder().value() + 1) : genuine.admissionOrder(),
                    mismatch == 2 ? ObservationOrigin.DOCUMENT : genuine.origin());
            assertThatThrownBy(() -> backend.imports().promote(withRegistration(command, forged)))
                    .hasMessageContaining("Registered observation does not match dataframe authority");
            assertThat(canonical.count("masks")).isZero();
            assertThat(canonical.count("import_commit")).isZero();
            assertThat(backend.imports().promote(withRegistration(command, genuine)).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
            assertThat(canonical.count("import_commit")).isOne();
        }
    }

    @Test
    @Timeout(30)
    void stagedAlternativeMatchesRejectStableIdentityChangesWithoutMutatingParticipants() throws Exception {
        var schema = schema("masks", "mask");
        var record = new CanonicalKeyDefinition("record-v1", CanonicalKeyMode.COMPOSITE, List.of("mask", "description"));
        var match = new CanonicalKeyDefinition("mask-v1", CanonicalKeyMode.COMPOSITE, List.of("mask"));
        var identity = new ArtifactIdentityDefinition("masks", record, List.of(match), 1);
        var canonical = environment("alternative-match-staged", List.of(schema), List.of(identity));
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        var confirmations = new ArrayList<CanonicalRecordConfirmation>();
        for (var fields : List.of(Map.of("mask", "multiple.example", "description", "one", "source", "seed"),
                Map.of("mask", "multiple.example", "description", "two", "source", "seed"),
                Map.of("mask", "stable.example", "description", "initial", "source", "seed"))) {
            var row = ArtifactRow.ordered(new LinkedHashMap<>(fields));
            var key = new CanonicalArtifactKeyResolver(List.of(identity)).recordKeyOf("masks", row).orElseThrow();
            confirmations.add(new CanonicalRecordConfirmation(new ArtifactRowKey(key.keyHash()), new PreparedArtifactRow(row, Optional.of("id"))));
        }
        backend.confirmations().confirm(new CanonicalArtifactConfirmation(new ObservationId("alternative-seed"), "alternative-source",
                new ConfirmationReceiptContext(new ConfirmationReceiptId("alternative-receipt"), "alternative-policy", 1, Duration.ofDays(30)),
                "masks", schema.columns().stream().map(DataframeColumn::name).toList(), confirmations));
        var before = typedRows(canonical.dataSource, "masks");
        var command = canonical.stage("alternative-import", ImportPromotionPolicy.defaults(), List.of(
                row(2, branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "multiple.example", "description", "third"), OptionalLong.empty())),
                row(3, branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "stable.example", "description", "changed"), OptionalLong.empty()))), List.of());
        var result = backend.imports().promote(command);
        assertThat(result.rejectedRows()).isEqualTo(2);
        assertThat(result.publicMutations()).isZero();
        assertThat(typedRows(canonical.dataSource, "masks")).isEqualTo(before);
        var evidence = backend.importEvidence().find(command.deliveryId()).orElseThrow();
        assertThat(evidence.issues()).extracting(ImportRowIssue::code)
                .containsExactly("IMPORT.STABLE_IDENTITY_CONFLICT", "IMPORT.STABLE_IDENTITY_CONFLICT");
    }

    @Test
    @Timeout(30)
    void stagedEmptyConfirmationCompletesReceiptWithoutAllocatingOrConfirmingRows() {
        var canonical = environment("empty-staged-confirmation");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        var command = new CanonicalArtifactConfirmation(new ObservationId("empty-staged"), "empty-source",
                new ConfirmationReceiptContext(new ConfirmationReceiptId("empty-receipt"), "empty-policy", 1, Duration.ofDays(30)),
                "masks", canonical.schemas.getFirst().columns().stream().map(DataframeColumn::name).toList(), List.of());
        assertThat(backend.confirmations().confirm(command).created()).isZero();
        assertThat(backend.confirmations().confirm(command).replayed()).isTrue();
        assertThat(backend.receipts(Duration.ofDays(30)).findComplete("empty-source", "empty-policy", EffectiveTime.at(NOW))).isPresent();
        assertThat(backend.expiry().nearestDeadline()).isEmpty();
        assertThat(canonical.count("masks")).isZero();
        assertThat(canonical.queryLong("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'")).isOne();
    }

    @Test
    @Timeout(30)
    void stagedOrdinaryOrderedFieldsInitializeFromEmptyValuesAndAdvanceOriginForEqualValues() {
        var canonical = environment("ordered-ordinary-empty");
        var configuration = stagedConfiguration(canonical, CLOCK);
        var backend = JdbcStagedCanonicalBackend.createEmpty(configuration, JdbcPublicationObserver.NOOP);
        var registrations = new JdbcObservationRegistrationStore(canonical.dataSource, CLOCK);
        var keys = new CanonicalArtifactKeyResolver(canonical.identities);
        var blanks = java.util.Arrays.asList(null, "", " ");
        for (int index = 0; index < blanks.size(); index++) {
            String mask = "empty-origin-" + index + ".example";
            var values = java.util.Arrays.asList(blanks.get(index), "latest", "latest");
            for (int step = 0; step < values.size(); step++) {
                var observation = new ObservationId("ordered-empty-" + index + "-" + step);
                var registered = registrations.registerNew(observation, ObservationOrigin.DOCUMENT);
                var fields = new LinkedHashMap<String, String>();
                for (var column : canonical.schemas.getFirst().columns()) { fields.put(column.name(), null); }
                fields.put("mask", mask); fields.put("source", values.get(step));
                var template = ArtifactRow.ordered(fields);
                var prepared = new PreparedArtifactRow(template, Optional.of("id"),
                        Map.of("source", new com.iocextractor.application.observation.OccurrencePosition(1)));
                var command = new CanonicalArtifactConfirmation(observation, "ordered-source-" + index + "-" + step,
                        new ConfirmationReceiptContext(new ConfirmationReceiptId("ordered-receipt-" + index + "-" + step),
                                "ordered-policy", 1, Duration.ofDays(30)), "masks", List.copyOf(fields.keySet()),
                        List.of(new CanonicalRecordConfirmation(new ArtifactRowKey(keys.recordKeyOf("masks", template).orElseThrow().keyHash()), prepared)), registered);
                backend.confirmations().confirm(command);
                if (step == 0) { assertThat(canonical.count("canonical_lifecycle_field_origin")).isEqualTo(index); }
            }
        }
        assertThat(canonical.count("masks")).isEqualTo(3);
        assertThat(canonical.queryLong("SELECT COUNT(*) FROM masks WHERE source='latest'")).isEqualTo(3);
        assertThat(canonical.count("canonical_lifecycle_field_origin")).isEqualTo(3);
        assertThat(canonical.queryLong("SELECT COUNT(*) FROM canonical_lifecycle_field_origin WHERE occurrence_id LIKE '%-2'")).isEqualTo(3);
    }

    @Test
    @Timeout(30)
    void unsafePublicationClockCancelsHiddenWorkAndReleasesOwnershipBeforeAHealthyRetry() {
        var canonical = environment("unsafe-publication-clock");
        var utc = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return utc.get(); }
        };
        var rollBackClock = new java.util.concurrent.atomic.AtomicBoolean(true);
        var configuration = stagedConfiguration(canonical, clock);
        var backend = JdbcStagedCanonicalBackend.createEmpty(configuration, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.BEFORE_PUBLICATION && rollBackClock.getAndSet(false)) {
                utc.set(NOW.minusSeconds(60));
            }
        });
        var command = canonical.stage("unsafe-clock", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY, values("mask", "clock.example"), OptionalLong.empty()))), List.of());
        assertThatThrownBy(() -> backend.imports().promote(command)).hasMessageContaining("System UTC clock is behind");
        assertThat(canonical.count("masks")).isZero();
        assertThat(canonical.count("import_commit")).isZero();
        assertThat(canonical.queryLong("SELECT COUNT(*) FROM publication_unit WHERE state='CANCELLED'")).isOne();
        assertThat(canonical.queryString("SELECT unit FROM publication_logical_owner")).isNull();
        assertThat(canonical.count("publication_owner_failure")).isOne();
        assertThat(canonical.queryLong("SELECT safe_time_high_water_ms FROM canonical_lifecycle_control")).isEqualTo(NOW.toEpochMilli());
        assertThat(canonical.queryLong("SELECT failed_at_ms FROM publication_owner_failure")).isEqualTo(NOW.minusSeconds(60).toEpochMilli());
        utc.set(NOW);
        assertThat(backend.imports().promote(command).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        assertThat(canonical.count("masks")).isOne();
        assertThat(canonical.queryLong("SELECT id FROM masks")).isGreaterThan(1);
        assertThat(configuration.workspaces().pressure().leasedWorkspaces()).isZero();
    }

    @Test
    @Timeout(30)
    void stagedPreflightRejectsUnsealedIncompatibleOrAmbiguousNativeMetadataBeforeOwnership() throws Exception {
        var corruptions = List.of("DELETE FROM stage_meta", "UPDATE stage_meta SET schema_version=999",
                "UPDATE stage_meta SET sealed_at_ms=NULL", "UPDATE stage_meta SET snapshot_size=snapshot_size+1",
                "UPDATE stage_meta SET slot_profile='unpaired-profile'",
                "ALTER TABLE stage_meta RENAME TO saved_meta; CREATE TABLE stage_meta AS SELECT * FROM saved_meta; INSERT INTO stage_meta SELECT * FROM saved_meta; DROP TABLE saved_meta");
        for (int index = 0; index < corruptions.size(); index++) {
            var canonical = environment("invalid-metadata-" + index);
            var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
            var command = canonical.stage("invalid-metadata-" + index, ImportPromotionPolicy.defaults(), List.of(row(2,
                    branch(canonical, "masks", ImportArtifactRole.PRIMARY, values("mask", "metadata.example"), OptionalLong.empty()))), List.of());
            var repinned = rewriteStage(canonical, command, corruptions.get(index));
            String expected = index < 3 ? "Import stage metadata is missing or unsealed"
                    : index < 5 ? "Import stage metadata does not match promotion evidence" : "Import stage contains duplicate metadata";
            assertThatThrownBy(() -> backend.imports().promote(repinned)).hasMessage(expected);
            assertThat(canonical.count("masks")).isZero();
            assertThat(canonical.count("import_commit")).isZero();
            assertThat(canonical.count("publication_unit")).isZero();
            assertThat(canonical.queryLong("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'")).isOne();
        }
    }

    @Test
    @Timeout(30)
    void stagedNativePlannerRejectsInvalidCarriersAndIdentityDriftWithCompleteReceiptEvidence() throws Exception {
        var mutations = List.of("UPDATE stage_cell SET target_column='unknown_column' WHERE target_column='mask'",
                "UPDATE stage_cell SET target_column='id' WHERE target_column='mask'",
                "DELETE FROM stage_cell WHERE target_column='mask'",
                "UPDATE stage_cell SET value='another.example' WHERE target_column='mask'");
        var reasons = List.of("IMPORT.STAGE_COLUMN_INVALID", "IMPORT.STAGE_COLUMN_INVALID", "IMPORT.RECORD_KEY_MISSING", "IMPORT.STAGED_IDENTITY_MISMATCH");
        for (int index = 0; index < mutations.size(); index++) {
            var canonical = environment("invalid-carrier-" + index);
            var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
            var command = canonical.stage("invalid-carrier-" + index, ImportPromotionPolicy.defaults(), List.of(row(2,
                    branch(canonical, "masks", ImportArtifactRole.PRIMARY, values("mask", "original.example"), OptionalLong.empty()))), List.of());
            var repinned = rewriteStage(canonical, command, mutations.get(index));
            var result = backend.imports().promote(repinned);
            assertThat(result.acceptedRows()).isZero();
            assertThat(result.rejectedRows()).isOne();
            assertThat(result.publicMutations()).isZero();
            assertThat(canonical.count("masks")).isZero();
            String reason = reasons.get(index);
            assertThat(backend.importEvidence().find(repinned.deliveryId())).hasValueSatisfying(receipt ->
                    assertThat(receipt.issues()).extracting(ImportRowIssue::code).containsExactly(reason));
            canonical.deleteStage(repinned);
            assertThat(backend.imports().promote(repinned).outcome()).isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        }
    }

    private CanonicalImportCommand rewriteStage(Environment canonical, CanonicalImportCommand command, String sql) throws Exception {
        Path path = canonical.stagePath(command);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            for (String change : sql.split(";")) { statement.execute(change); }
        }
        var stage = new ImportStage(command.stage().reference(), ImportFileDigests.sha256(path), command.stage().sourceRows(),
                command.stage().acceptedRows(), command.stage().rejectedRows());
        return new CanonicalImportCommand(command.deliveryId(), command.sequence(), command.sourceId(), command.snapshot(), command.contract(), stage);
    }

    @Test
    @Timeout(30)
    void stagedSnapshotRejectsStaleIdentityAndUnknownColumnsWithoutExportingCanonicalValues() {
        var canonical = environment("snapshot-plan-guards");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        var command = canonical.stage("snapshot-seed", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY, values("mask", "snapshot.example"), OptionalLong.empty()))), List.of());
        backend.imports().promote(command);
        var actual = canonical.identities.getFirst();
        var wrongEpoch = new ArtifactIdentityDefinition("masks", actual.recordKey(), actual.matchKeys(), actual.epoch() + 1);
        var wrongHash = identity("masks", "source");
        for (var mismatched : List.of(wrongEpoch, wrongHash)) {
            assertThatThrownBy(() -> snapshot(backend, canonical.schemas.getFirst(), mismatched, "invalid-plan"))
                    .isInstanceOf(com.iocextractor.diagnostics.DiagnosticException.class);
        }
        var unknownColumn = new DataframeArtifactSchema("masks", List.of(new DataframeColumn("id"), new DataframeColumn("unknown")));
        assertThatThrownBy(() -> snapshot(backend, unknownColumn, actual, "invalid-columns"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown public columns");
        assertThat(snapshot(backend, canonical.schemas.getFirst(), actual, "valid-plan"))
                .singleElement().satisfies(row -> assertThat(row.value("mask")).isEqualTo("snapshot.example"));
        assertThat(canonical.count("import_commit")).isOne();
        assertThat(canonical.count("masks")).isOne();
    }

    private static List<ArtifactRow> snapshot(JdbcStagedCanonicalBackend backend, DataframeArtifactSchema schema,
                                             ArtifactIdentityDefinition identity, String profile) {
        var rows = new ArrayList<ArtifactRow>();
        var plan = new com.iocextractor.application.export.ExportPlan(1,
                new com.iocextractor.application.export.ExportProfile(profile, com.iocextractor.application.export.ExportMode.COMPLETE, List.of(schema.artifactName())),
                new com.iocextractor.application.export.ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new com.iocextractor.application.export.ExportArtifactSpec(schema.artifactName(), schema.artifactName() + ".csv",
                        schema.columns().stream().map(DataframeColumn::name).toList(), identity.epoch(), identity.identityHash(), DIGEST, FINGERPRINT)));
        backend.snapshots().stream(new com.iocextractor.application.export.SnapshotRequest(plan),
                new com.iocextractor.application.port.out.export.SnapshotRowConsumer() {
                    public void begin(com.iocextractor.application.export.SnapshotMetadata metadata) { }
                    public void beginArtifact(com.iocextractor.application.export.SnapshotArtifactMetadata metadata) { }
                    public void row(ArtifactRow row) { rows.add(row); }
                    public void endArtifact() { }
                    public void end() { }
                });
        return rows;
    }

    @Test
    @Timeout(30)
    void stagedCleanupBoundsScannedPrefixesAndDrainsTransferJournalsWithoutLosingReceiptOrAnchors() throws Exception {
        Environment canonical = environment("bounded-cleanup");
        var configuration = stagedConfiguration(canonical, CLOCK);
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var backend = JdbcStagedCanonicalBackend.createEmpty(configuration, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.HIDDEN_CHUNK && fail.getAndSet(false)) {
                throw new IllegalStateException("cancel tail");
            }
        });
        var rows = new ArrayList<ImportLogicalRow>();
        for (int index = 0; index < 260; index++) {
            rows.add(row(index + 2, branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                    values("mask", "cleanup-" + index + ".example", "source", "feed"), OptionalLong.empty())));
        }
        var committed = canonical.stage("cleanup-live", ImportPromotionPolicy.defaults(), rows, List.of());
        backend.imports().promote(committed);
        assertThat(snapshot(backend, canonical.schemas.getFirst(), canonical.identities.getFirst(), "bounded-profile")).hasSize(260);
        assertThat(canonical.count("export_slot_assignment")).isEqualTo(260);
        var cancelled = canonical.stage("cleanup-cancelled", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "cancelled.example", "source", "feed"), OptionalLong.empty()))), List.of());
        fail.set(true);
        assertThatThrownBy(() -> backend.imports().promote(cancelled)).isInstanceOf(IllegalStateException.class);

        var cleanup = backend.cleanup();
        var first = cleanup.scanVersions("masks", 256);
        var tail = cleanup.scanVersions("masks", 256);

        assertThat(first).isEqualTo(new JdbcPublicationVersionCleanup.Page(256, 0, false));
        assertThat(tail).isEqualTo(new JdbcPublicationVersionCleanup.Page(5, 1, true));
        try (var pinned = canonical.dataSource.getConnection()) {
            pinned.setAutoCommit(false);
            long facts = visibleCount(pinned, "publication_fact");
            assertThat(facts).isGreaterThan(256);
            for (int pass = 0; pass < 40; pass++) {
                var page = cleanup.purgeJournal(256);
                assertThat(page.scanned()).isBetween(0, 1);
                assertThat(page.removed()).isBetween(0, 256);
            }
            assertThat(visibleCount(pinned, "publication_fact")).isEqualTo(facts);
            pinned.rollback();
        }
        assertThat(canonical.count("publication_fact")).isZero();
        assertThat(canonical.count("publication_chunk")).isZero();
        assertThat(canonical.count("publication_dependency")).isZero();
        assertThat(canonical.count("publication_attempt")).isEqualTo(3);
        assertThat(backend.expiry().nearestDeadline().orElseThrow().validUntil()).isEqualTo(NOW.plus(TTL));
        canonical.deleteStage(committed);
        assertThat(JdbcStagedCanonicalBackend.reopen(configuration, JdbcPublicationObserver.NOOP).imports().promote(committed).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        assertThat(backend.imports().promote(cancelled).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        assertThat(canonical.count("masks")).isEqualTo(261);
    }

    @Test
    @Timeout(30)
    void stagedEpochInvalidationStopsAfterThreeAttemptsAndLeavesRetryablePinnedWork() {
        Environment canonical = environment("bounded-rebuild");
        var holder = new java.util.concurrent.atomic.AtomicReference<JdbcStagedCanonicalBackend>();
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        var nested = new java.util.concurrent.atomic.AtomicBoolean();
        var rebuilds = new AtomicInteger();
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
            if (phase == JdbcPublicationObserver.Phase.BEFORE_PUBLICATION && enabled.get() && nested.compareAndSet(false, true)) {
                try {
                    holder.get().projections().recordFailure("masks",
                            holder.get().projections().load("masks").requiredGeneration(), "PROJECTION.FAILED");
                } finally { nested.set(false); }
            }
        });
        holder.set(backend);
        var command = canonical.stage("epoch-invalidated", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "epoch.example", "source", "feed"), OptionalLong.empty()))), List.of());

        assertThatThrownBy(() -> backend.imports().promote(command)).isInstanceOf(IocExtractorException.class)
                .hasCauseInstanceOf(SQLException.class).satisfies(failure ->
                        assertThat(failure.getCause()).hasMessage("Publication rebuild budget exhausted"));

        assertThat(rebuilds).hasValue(3);
        assertThat(canonical.count("masks")).isZero();
        assertThat(backend.importEvidence().find(command.deliveryId())).isEmpty();
        assertThat(canonical.count("publication_owner_failure")).isOne();
        enabled.set(false);
        assertThat(backend.imports().promote(command).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        assertThat(canonical.queryLong("SELECT id FROM masks")).isGreaterThan(3);
        assertThat(canonical.count("masks_sources")).isOne();
    }

    @Test
    @Timeout(15)
    void canonicalWorkspaceAdmissionIsNonblockingWeightedAndRestoresPermitsAfterFailure() throws Exception {
        Environment canonical = environment("canonical-admission");
        var configuration = stagedConfiguration(canonical, CLOCK);
        var factory = configuration.workspaces();
        try (var first = JdbcPrivatePublicationWorkspace.create(factory, "first", "pins");
             var second = JdbcPrivatePublicationWorkspace.create(factory, "second", "pins");
             var third = JdbcPrivatePublicationWorkspace.create(factory, "third", "pins")) {
            second.checkQuota(); third.checkQuota();
            assertThat(factory.pressure().leasedWorkspaces()).isEqualTo(3);
            assertThatThrownBy(() -> JdbcPrivatePublicationWorkspace.create(factory, "exhausted", "pins"))
                    .isInstanceOf(IocExtractorException.class).hasMessageContaining("admission exhausted");
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var rejected = executor.submit(() -> assertThatThrownBy(() -> first.dataSource().getConnection())
                        .isInstanceOf(SQLException.class).hasMessageContaining("another thread"));
                rejected.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(factory.pressure().leasedWorkspaces()).isZero();
        try (var fresh = JdbcPrivatePublicationWorkspace.create(factory, "first", "changed")) {
            fresh.checkQuota();
        }
        try (var recovered = JdbcPrivatePublicationWorkspace.create(factory, "recovered", "pins")) {
            recovered.checkQuota();
            try (var connection = recovered.dataSource().getConnection()) {
                assertThat(pragma(connection, "cache_size")).isEqualTo(-64);
                assertThat(pragma(connection, "temp_store")).isOne();
                assertThat(pragma(connection, "mmap_size")).isZero();
                long pages = pragma(connection, "temp.max_page_count");
                var template = new LinkedHashMap<String, String>();
                template.put("id", null); template.put("mask", "bounded.example");
                var confirmation = new CanonicalArtifactConfirmation(new ObservationId("bounded-validation"), "bounded-source",
                        new ConfirmationReceiptContext(new ConfirmationReceiptId("bounded-receipt"), "bounded-policy", 1, Duration.ofDays(1)),
                        "masks", List.of("id", "mask"), List.of(new CanonicalRecordConfirmation(new ArtifactRowKey("key"),
                        new PreparedArtifactRow(ArtifactRow.ordered(template), Optional.of("id")))));
                connection.setAutoCommit(false);
                JdbcConfirmationValidator.validate(connection, confirmation, true);
                connection.rollback(); connection.setAutoCommit(true);
                assertThat(pragma(connection, "temp.max_page_count")).isEqualTo(pages);
            }
        }
    }

    private static long pragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + name)) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        }
    }

    @Test
    @Timeout(15)
    void changedPinsBeforeOwnershipCannotCancelAnotherPendingAttempt() throws Exception {
        Environment canonical = environment("pending-pins");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        try (var connection = canonical.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            new JdbcPublicationProtocol().begin(connection, "pending", "original-pins",
                    new JdbcPublicationProtocol.Manifest(0, 0, Long.MAX_VALUE, Map.of("artifact:masks", 0L)));
            connection.commit();
        }
        var unexpected = new JdbcStagedPublication.NativeTask() {
            public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base, JdbcPublicationSlotBase slots,
                                EffectiveTime asOf) { throw new AssertionError("Changed pins must fail before hydration"); }
            public void prepare(javax.sql.DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) {
                throw new AssertionError("Changed pins must fail before mutation");
            }
        };

        assertThatThrownBy(() -> backend.publication().execute(new JdbcStagedPublication.Unit("pending", "changed-pins",
                java.util.Set.of("artifact:masks"), true), unexpected))
                .isInstanceOf(IocExtractorException.class).hasRootCauseMessage("Canonical publication pins changed");

        assertThat(canonical.queryString("SELECT state FROM publication_unit")).isEqualTo("PENDING");
        assertThat(backend.cleanup().purgeJournal(256).removed()).isZero();
        assertThat(canonical.count("publication_dependency")).isOne();
        assertThat(canonical.count("publication_owner_failure")).isZero();
    }

    @Test
    @Timeout(30)
    void stagedIdlessArtifactsNeverReuseInternalRowOrHistoryIdsAcrossExpiry() {
        var schema = new DataframeArtifactSchema("addresses", List.of(new DataframeColumn("value"), new DataframeColumn("source")));
        var identity = identity("addresses", "value");
        var canonical = environment("idless-publication", List.of(schema), List.of(identity));
        var utc = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock wall = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return utc.get(); }
        };
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, wall), JdbcPublicationObserver.NOOP);
        var fields = Map.of("value", "idless.example", "source", "feed");
        var prepared = new PreparedArtifactRow(ArtifactRow.ordered(fields), Optional.empty());
        var resolver = new CanonicalArtifactKeyResolver(canonical.identities);
        var rowKey = new ArtifactRowKey(resolver.recordKeyOf("addresses", prepared.template()).orElseThrow().keyHash());
        long previousId = 0;
        for (int cycle = 0; cycle < 2; cycle++) {
            var command = new CanonicalArtifactConfirmation(new ObservationId("idless-" + cycle), "idless-source",
                    new ConfirmationReceiptContext(new ConfirmationReceiptId("idless-receipt-" + cycle), "idless-policy", 1, Duration.ofDays(30)),
                    "addresses", List.of("value", "source"), List.of(new CanonicalRecordConfirmation(rowKey, prepared)));
            backend.confirmations().confirm(command);
            long id = canonical.queryLong("SELECT id FROM addresses");
            assertThat(id).isGreaterThan(previousId); previousId = id;
            var streamed = new ArrayList<ArtifactRow>();
            backend.streamReader().stream("addresses", streamed::add);
            assertThat(streamed).hasSize(1).allSatisfy(row -> assertThat(row.values()).doesNotContainKey("id"));
            assertThat(snapshot(backend, schema, identity, "idless-profile")).hasSize(1)
                    .allSatisfy(row -> assertThat(row.values()).doesNotContainKey("id"));
            utc.set(utc.get().plus(TTL));
            assertThat(backend.expiry().expireDue("addresses", EffectiveTime.at(utc.get()), 1).expired()).isOne();
        }
        assertThat(canonical.queryLong("SELECT COUNT(DISTINCT history_id) FROM addresses_history")).isEqualTo(2);
        assertThat(canonical.count("addresses_history")).isEqualTo(2);
        assertThat(canonical.count("addresses")).isZero();
    }

    @Test
    @Timeout(15)
    void selectiveHydrationRejectsOversizedCommittedFieldsAndRestoresBorrowedCachePolicy() throws Exception {
        Environment canonical = environment("hydration-bound");
        Environment working = environment("hydration-working");
        var command = canonical.stage("oversize-base", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "bound.example", "source", "feed"), OptionalLong.empty()))), List.of());
        canonical.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        try (var connection = canonical.dataSource.getConnection(); var update = connection.prepareStatement("UPDATE masks SET source=?")) {
            update.setString(1, "x".repeat(3000)); update.executeUpdate();
        }
        var limits = new DocumentPreparationLimits(4 * 1024 * 1024, 64, 4096, 2048, 16 * 1024 * 1024, 256 * 1024 * 1024, 256);
        try (var base = canonical.dataSource.getConnection(); var target = working.dataSource.getConnection()) {
            long cache = pragma(base, "cache_size"), tempStore = pragma(base, "temp_store"), tempCache = pragma(base, "temp.cache_size");
            var table = JdbcPublicationTable.inspect(base, "masks");
            try (var ignored = new JdbcPublicationReadLimits(base, limits)) {
                base.setAutoCommit(false); target.setAutoCommit(false);
                var hydrator = new JdbcPublicationHydrator(base, target, List.of(table), () -> { }, limits);
                assertThatThrownBy(() -> hydrator.copy("masks", "1=1", List.of()))
                        .isInstanceOf(SQLException.class).hasMessageContaining("preparation limits");
                target.rollback();
            }
            assertThat(base.getAutoCommit()).isTrue();
            assertThat(pragma(base, "cache_size")).isEqualTo(cache);
            assertThat(pragma(base, "temp_store")).isEqualTo(tempStore);
            assertThat(pragma(base, "temp.cache_size")).isEqualTo(tempCache);
        }
        assertThat(working.count("masks")).isZero();
    }

    @Test
    @Timeout(30)
    void stagedBackendUsesNativeImportAndRebuildsExpiredNoopAtPublicationWithoutResurrection() {
        Environment canonical = environment("staged-backend");
        var utc = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock wall = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return utc.get(); }
        };
        var configuration = stagedConfiguration(canonical, wall);
        var rebuilds = new AtomicInteger();
        var advance = new java.util.concurrent.atomic.AtomicBoolean();
        var backend = JdbcStagedCanonicalBackend.createEmpty(configuration, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.BEFORE_PUBLICATION && advance.compareAndSet(true, false)) {
                utc.set(NOW.plus(TTL));
            }
            if (phase == JdbcPublicationObserver.Phase.REBUILD) { rebuilds.incrementAndGet(); }
        });
        var seed = canonical.stage("staged-seed", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "staged.example", "source", "feed"), OptionalLong.empty()))), List.of());

        assertThat(backend.imports().promote(seed).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        long initialId = canonical.queryLong("SELECT id FROM masks");
        String initialLifecycle = canonical.queryString("SELECT _lifecycle_id FROM masks");
        utc.set(NOW.plus(TTL).minusMillis(1));
        var repeated = canonical.stage("staged-expiry", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "staged.example", "source", "feed"), OptionalLong.empty()))), List.of());
        advance.set(true);

        var result = backend.imports().promote(repeated);

        assertThat(rebuilds).hasValue(1);
        assertThat(result.effectiveAt()).isEqualTo(NOW.plus(TTL));
        assertThat(canonical.queryLong("SELECT id FROM masks")).isGreaterThan(initialId);
        assertThat(canonical.queryString("SELECT _lifecycle_id FROM masks")).isNotEqualTo(initialLifecycle);
        assertThat(canonical.queryLong("SELECT _valid_until_epoch_ms FROM masks")).isEqualTo(NOW.plus(TTL.multipliedBy(2)).toEpochMilli());
        assertThat(canonical.count("masks_history")).isOne();
        assertThat(canonical.count("masks_sources")).isOne();
        canonical.deleteStage(repeated);
        assertThat(JdbcStagedCanonicalBackend.reopen(configuration, JdbcPublicationObserver.NOOP).imports().promote(repeated).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        assertThat(canonical.count("import_commit")).isEqualTo(2);
    }

    private JdbcStagedCanonicalConfiguration stagedConfiguration(Environment environment, Clock wall) {
        var admission = new JdbcWriterAdmission();
        var workspaces = new JdbcDocumentPreparationWorkspaceFactory(environment.workspaceRoot.resolve("canonical"),
                new DocumentPreparationLimits(8 * 1024 * 1024, 64, 4096, 2048, 16 * 1024 * 1024, 256 * 1024 * 1024, 256),
                new com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver(environment.identities), "staged-test");
        return new JdbcStagedCanonicalConfiguration(environment.dataSource, environment.schemas,
                environment.schemas.stream().filter(schema -> schema.columns().stream().anyMatch(column -> column.name().equals("id")))
                        .map(schema -> new ArtifactIdAllocatorDefinition(schema.artifactName(), ArtifactIdStrategy.ASCENDING, 1, 1)).toList(),
                environment.identities, new JdbcLifecycleClock(environment.dataSource, wall,
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission), wall,
                new FixedRecordValidityPolicy(TTL), admission, workspaces, environment.workspaceRoot, Map.of(), Duration.ofDays(90));
    }

    @Test
    @Timeout(30)
    void stagedOrdinaryArtifactsMatchNativeOracleCompleteReceiptAndRetainSnapshotsDuringCleanup() throws Exception {
        Environment canonical = environment("staged-ordinary");
        Environment oracle = environment("staged-ordinary-oracle");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        var receipt = new ConfirmationReceiptContext(new ConfirmationReceiptId("staged-ordinary-receipt"), "ordinary-policy", 2, Duration.ofDays(30));
        var commands = new ArrayList<CanonicalArtifactConfirmation>();
        for (int index = 0; index < canonical.schemas.size(); index++) {
            var schema = canonical.schemas.get(index);
            var fields = new LinkedHashMap<String, String>();
            schema.columns().forEach(column -> fields.put(column.name(), null));
            fields.put(schema.artifactName().equals("masks") ? "mask" : "hash", "staged-ordinary-" + index);
            fields.put("source", "feed");
            var row = ArtifactRow.ordered(fields);
            var key = new CanonicalArtifactKeyResolver(canonical.identities).recordKeyOf(schema.artifactName(), row).orElseThrow();
            var command = new CanonicalArtifactConfirmation(new ObservationId("staged-ordinary-observation"), "staged-source", receipt,
                    schema.artifactName(), List.copyOf(fields.keySet()), List.of(new CanonicalRecordConfirmation(
                    new ArtifactRowKey(key.keyHash()), new PreparedArtifactRow(row, Optional.of("id")))));
            commands.add(command);
            backend.confirmations().confirm(command);
            ordinaryWriter(oracle, NOW).confirm(command);
            for (var table : backend.store().tables()) {
                assertThat(typedRows(canonical.dataSource, table.name())).as("ordinary native oracle: %s", table.name())
                        .isEqualTo(typedRows(oracle.dataSource, table.name()));
            }
        }
        var receipts = backend.receipts(Duration.ofDays(30));
        assertThat(receipts.findComplete("staged-source", "ordinary-policy", EffectiveTime.at(NOW)).orElseThrow().artifacts()).hasSize(2);
        assertThat(backend.confirmations().confirm(commands.getFirst()).replayed()).isTrue();
        try (var pinned = canonical.dataSource.getConnection()) {
            pinned.setAutoCommit(false);
            assertThat(visibleCount(pinned, "canonical_observation_commit")).isEqualTo(2);
            receipts.markTerminal(commands.getFirst().observationId(), EffectiveTime.at(NOW), Duration.ofDays(1));
            assertThat(receipts.purgeExpired(EffectiveTime.at(NOW.plus(Duration.ofDays(31))), 1).purged()).isOne();
            for (var table : backend.store().tables()) { backend.cleanup().purgeVersions(table.name(), 256); }
            assertThat(visibleCount(pinned, "canonical_observation_commit")).isEqualTo(2);
            pinned.rollback();
        }
        assertThat(receipts.findComplete("staged-source", "ordinary-policy", EffectiveTime.at(NOW))).isEmpty();
        assertThat(canonical.count("canonical_observation_commit")).isZero();
        var commits = backend.store().tables().stream().filter(table -> table.name().equals("canonical_observation_commit")).findFirst().orElseThrow();
        assertThat(canonical.count(commits.versions())).isZero();
        assertThat(canonical.count("masks")).isOne();
        assertThatThrownBy(() -> backend.confirmations().confirm(commands.getFirst()))
                .isInstanceOf(IocExtractorException.class).hasMessageContaining("native confirmation marker");
        assertThat(canonical.queryLong("SELECT _valid_until_epoch_ms FROM masks")).isEqualTo(NOW.plus(TTL).toEpochMilli());
        assertThatThrownBy(() -> backend.cleanup().purgeVersions("masks", 257)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(30)
    void stagedFailureCancelsHiddenFactsBurnsIdsAndRetriesThePinnedDelivery() {
        Environment canonical = environment("staged-failure");
        var configuration = stagedConfiguration(canonical, CLOCK);
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var backend = JdbcStagedCanonicalBackend.createEmpty(configuration, (phase, attempt, chunk) -> {
            if (phase == JdbcPublicationObserver.Phase.HIDDEN_CHUNK && fail.getAndSet(false)) {
                throw new IllegalStateException("injected hidden chunk failure");
            }
        });
        var command = canonical.stage("failed-hidden", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "hidden.example", "source", "feed"), OptionalLong.empty()))), List.of());

        assertThatThrownBy(() -> backend.imports().promote(command)).isInstanceOf(IllegalStateException.class)
                .hasMessage("injected hidden chunk failure");
        assertThat(canonical.count("masks")).isZero();
        assertThat(backend.importEvidence().find(command.deliveryId())).isEmpty();
        assertThat(canonical.queryString("SELECT state FROM publication_unit")).isEqualTo("CANCELLED");
        assertThat(canonical.count("publication_owner_failure")).isOne();
        assertThat(backend.cleanup().purgeVersions("masks", 256)).isOne();
        assertThat(backend.imports().promote(command).outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        assertThat(canonical.queryLong("SELECT id FROM masks")).isGreaterThan(1);
        assertThat(canonical.count("masks_sources")).isOne();
        assertThat(canonical.count("import_commit")).isOne();
        assertThatThrownBy(() -> JdbcStagedCanonicalBackend.createEmpty(configuration, JdbcPublicationObserver.NOOP))
                .isInstanceOf(IocExtractorException.class);
    }

    @Test
    @Timeout(30)
    void stagedNativeControlsPreserveSnapshotSlotsExpiryHistoryProjectionAndReceiptVisibility() throws Exception {
        Environment canonical = environment("staged-controls");
        var backend = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(canonical, CLOCK), JdbcPublicationObserver.NOOP);
        var command = canonical.stage("controls-import", ImportPromotionPolicy.defaults(), List.of(row(2,
                branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "controls.example", "source", "feed"), OptionalLong.empty()))),
                List.of(), List.of(new ImportRowWarning(2, "masks", "PROCESSING.FALLBACK_ORIGINAL")));
        backend.imports().promote(command);
        assertThat(backend.activeRows().loadActive("masks", EffectiveTime.at(NOW)).records()).hasSize(1);
        var streamed = new ArrayList<ArtifactRow>();
        backend.streamReader().stream("masks", streamed::add);
        assertThat(streamed).hasSize(1).first().satisfies(row -> assertThat(row.values().get("mask")).isEqualTo("controls.example"));
        var requested = new CanonicalArtifactKeyResolver(canonical.identities).matchKeysOf("masks", streamed.getFirst());
        assertThat(backend.matching().plan("masks", EffectiveTime.at(NOW),
                List.of(new com.iocextractor.application.artifact.CanonicalMatchRequest("read", requested))))
                .hasSize(1).first().satisfies(plan -> assertThat(plan.exactCandidate()).isPresent());
        assertThat(backend.expiry().nearestDeadline().orElseThrow().validUntil()).isEqualTo(NOW.plus(TTL));
        var generation = backend.projections().load("masks").requiredGeneration();
        assertThat(backend.projections().recordFailure("masks", generation, "PROJECTION.FAILED")).isTrue();
        assertThat(backend.projections().acknowledge(new com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement("masks", generation))).isTrue();
        assertThat(backend.projections().load("masks").projectedGeneration()).isEqualTo(generation);
        var consumed = new ArrayList<ArtifactRow>();
        var consumer = new com.iocextractor.application.port.out.export.SnapshotRowConsumer() {
            public void begin(com.iocextractor.application.export.SnapshotMetadata metadata) { }
            public void beginArtifact(com.iocextractor.application.export.SnapshotArtifactMetadata metadata) { }
            public void row(ArtifactRow row) { consumed.add(row); }
            public void endArtifact() { }
            public void end() { }
        };
        var identity = canonical.identities.getFirst();
        var plan = new com.iocextractor.application.export.ExportPlan(1,
                new com.iocextractor.application.export.ExportProfile("staged-profile", com.iocextractor.application.export.ExportMode.COMPLETE, List.of("masks")),
                new com.iocextractor.application.export.ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new com.iocextractor.application.export.ExportArtifactSpec("masks", "masks.csv",
                        canonical.schemas.getFirst().columns().stream().map(DataframeColumn::name).toList(),
                        identity.epoch(), identity.identityHash(), DIGEST, FINGERPRINT)));
        backend.snapshots().stream(new com.iocextractor.application.export.SnapshotRequest(plan), consumer);
        assertThat(consumed).hasSize(1);
        assertThat(canonical.count("export_slot_assignment")).isOne();
        assertThat(backend.importEvidence().find(command.deliveryId()).orElseThrow().warnings()).hasSize(1);
        try (var pinned = canonical.dataSource.getConnection()) {
            pinned.setAutoCommit(false);
            assertThat(visibleCount(pinned, "masks")).isOne();
            var expired = backend.expiry().expireDue("masks", EffectiveTime.at(NOW.plus(TTL)), 1);
            assertThat(expired.expired()).isOne();
            assertThat(expired.moreDue()).isFalse();
            assertThat(visibleCount(pinned, "masks")).isOne();
            assertThat(canonical.count("masks")).isZero();
            assertThat(canonical.count("masks_sources")).isZero();
            assertThat(canonical.count("canonical_match_alias")).isZero();
            assertThat(canonical.count("masks_history")).isOne();
            pinned.rollback();
        }
        assertThat(backend.history().purge("masks", EffectiveTime.at(NOW.plus(TTL)), 1).purged()).isOne();
        assertThat(canonical.count("masks_history_sources")).isZero();
        assertThat(backend.expiry().nearestDeadline()).isEmpty();
        backend.importEvidence().purge(command.deliveryId());
        assertThat(backend.importEvidence().find(command.deliveryId())).isEmpty();
        assertThat(canonical.count("import_row_warning")).isZero();
        assertThatThrownBy(() -> backend.imports().promote(command)).isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("native import receipt");
    }

    @Test
    @Timeout(30)
    void sharedTypedStorePublishesCapturedNativeFanOutAndRecoversWithoutInputStage() throws Exception {
        Environment canonical = environment("shared-typed-publication");
        Environment working = environment("shared-typed-private");
        Environment oracle = environment("shared-typed-oracle");
        var admission = new JdbcWriterAdmission();
        var transactions = new JdbcPublicationTransactions(canonical.dataSource, admission);
        var store = transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL,
                c -> JdbcTypedPublicationStore.initializeEmpty(c, canonical.schemas));
        var rows = new ArrayList<ImportLogicalRow>();
        for (int index = 0; index < 60; index++) {
            rows.add(row(index + 2, branch(working, "masks", ImportArtifactRole.PRIMARY,
                    values("mask", "captured-" + index + ".example", "source", "feed"), OptionalLong.empty()),
                    branch(working, "hashes", ImportArtifactRole.RELATED,
                            values("hash", "CAPTURED" + index, "source", "feed"), OptionalLong.empty())));
        }
        rows.add(row(100, branch(working, "masks", ImportArtifactRole.PRIMARY,
                values("mask", "captured-0.example", "source", "feed"), OptionalLong.empty())));
        CanonicalImportCommand command = working.stage("shared-captured", ImportPromotionPolicy.defaults(), rows,
                List.of(new ImportRejectedLogicalRow(101, List.of(new ImportRowIssue(101, "masks", "IMPORT.INVALID_VALUE")))),
                List.of(new ImportRowWarning(100, "masks", "PROCESSING.FALLBACK_ORIGINAL")));
        try (var privateConnection = working.dataSource.getConnection()) {
            privateConnection.setAutoCommit(false);
            JdbcPrivateChangeCapture.install(privateConnection, store.tables());
            privateConnection.commit();
        }
        working.preparingWriter(JdbcCanonicalImportObserver.NOOP, canonical, admission, command).promote(command);
        var protocol = new JdbcPublicationProtocol();
        var transfer = new JdbcPublicationTransfer(store);
        try (var snapshot = working.dataSource.getConnection(); var pinned = canonical.dataSource.getConnection()) {
            snapshot.setAutoCommit(false);
            pinned.setAutoCommit(false);
            assertThat(visibleCount(pinned, "masks")).isZero();
            var description = transfer.describe(snapshot, true, NOW.toEpochMilli(), Long.MAX_VALUE);
            assertThat(description.chunks()).isGreaterThan(1);
            var attempt = transfer.stage(snapshot, command.deliveryId().value(), command.stage().digest().value(),
                    Map.of("artifact:masks", 0L, "artifact:hashes", 0L), description, true,
                    new JdbcPublicationTransfer.Writer() {
                        public JdbcPublicationProtocol.Attempt begin(String identity, String pins, JdbcPublicationProtocol.Manifest manifest)
                                throws SQLException {
                            return transactions.execute(JdbcWriterAdmission.OperationClass.PROMOTION,
                                    c -> protocol.begin(c, identity, pins, manifest));
                        }
                        public void append(JdbcPublicationProtocol.Attempt unit, String scope, int ordinal, JdbcTypedPublicationChunk chunk)
                                throws SQLException {
                            transactions.execute(JdbcWriterAdmission.OperationClass.PROMOTION, c -> {
                                chunk.append(c, protocol, unit, scope, ordinal); return null;
                            });
                            assertThat(canonical.count("masks")).isZero();
                            assertThat(canonical.count("canonical_match_alias")).isZero();
                            assertThat(new JdbcImportCommitEvidenceStore(canonical.dataSource).find(command.deliveryId())).isEmpty();
                            transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
                                assertThat(protocol.receipt(c, unit.unitId())).isEmpty(); return null;
                            });
                        }
                        public void seal(JdbcPublicationProtocol.Attempt unit) throws SQLException {
                            transactions.execute(JdbcWriterAdmission.OperationClass.PROMOTION, c -> { protocol.seal(c, unit); return null; });
                        }
                    });
            var publicationUtc = NOW.plusSeconds(3);
            transactions.execute(JdbcWriterAdmission.OperationClass.PROMOTION, c -> protocol.publish(c, attempt,
                    publicationUtc.toEpochMilli(), command.stage().digest().value(), new FixedRecordValidityPolicy(TTL)));
            assertThat(visibleCount(pinned, "masks")).isZero();
            pinned.rollback();
            snapshot.rollback();
            new JdbcCanonicalImportWriter(oracle.dataSource, oracle.schemas,
                    List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    oracle.identities, working.workspaceRoot, ignored -> EffectiveTime.at(publicationUtc),
                    new FixedRecordValidityPolicy(TTL), CLOCK, new JdbcWriterAdmission(),
                    JdbcCanonicalImportObserver.NOOP, Duration.ofDays(90)).promote(command);
            for (var table : store.tables()) {
                assertThat(typedRows(canonical.dataSource, table.name())).as("captured native oracle: %s", table.name())
                        .isEqualTo(typedRows(oracle.dataSource, table.name()));
            }
        }
        try (var connection = canonical.dataSource.getConnection()) {
            assertThat(JdbcTypedPublicationStore.reopen(connection, canonical.schemas).tables())
                    .containsExactlyInAnyOrderElementsOf(store.tables());
        }
        working.deleteStage(command);
        var replay = new JdbcCanonicalImportWriter(canonical.dataSource, canonical.schemas,
                List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                        new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                canonical.identities, working.workspaceRoot, ignored -> EffectiveTime.at(NOW.plusSeconds(9)),
                new FixedRecordValidityPolicy(TTL), CLOCK, admission, JdbcCanonicalImportObserver.NOOP,
                Duration.ofDays(90)).promote(command);
        assertThat(replay.outcome()).isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        assertThat(new JdbcImportCommitEvidenceStore(canonical.dataSource).find(command.deliveryId()).orElseThrow().warnings())
                .containsExactly(new ImportRowWarning(100, "masks", "PROCESSING.FALLBACK_ORIGINAL"));
        assertThat(canonical.count("masks_sources")).isEqualTo(60);
        assertThat(admission.snapshot().get(JdbcWriterAdmission.OperationClass.CONTROL).completed()).isGreaterThan(2);
    }
    private static final Duration TTL = Duration.ofHours(12);
    private static final String DIGEST = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);

    @TempDir
    Path tempDir;

    private final AtomicInteger databases = new AtomicInteger();
    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void closeDataSources() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Override
    protected Fixture createFixture() {
        Environment environment = environment("tck");
        CanonicalImportCommand command = environment.stage(
                "delivery-tck", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example", "source", "feed-a"), OptionalLong.empty()))),
                List.of());
        var writer = JdbcStagedCanonicalBackend.createEmpty(stagedConfiguration(environment, CLOCK), JdbcPublicationObserver.NOOP).imports();
        return new Fixture(writer, command, () -> {
            assertThat(environment.count("masks")).isOne();
            assertThat(environment.count("masks_sources")).isOne();
            assertThat(environment.count("canonical_match_alias")).isOne();
            assertThat(environment.count("import_commit")).isOne();
            assertThat(environment.queryLong(
                    "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                    .isOne();
        });
    }

    @Test
    void acceptedWarningSurvivesCanonicalCommitAndReceiptReplay() {
        Environment environment = environment("accepted-warning");
        ImportRowWarning warning = new ImportRowWarning(2, "masks", "IMPORT.VIEW_FALLBACK");
        CanonicalImportCommand command = environment.stage(
                "delivery-accepted-warning", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example"), OptionalLong.empty()))),
                List.of(), List.of(warning));

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        var evidence = new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId());

        assertThat(evidence).hasValueSatisfying(receipt -> {
            assertThat(receipt.acceptedRows()).isOne();
            assertThat(receipt.rejectedRows()).isZero();
            assertThat(receipt.issues()).isEmpty();
            assertThat(receipt.warnings()).containsExactly(warning);
        });
        assertThat(environment.count("import_row_rejection")).isZero();
        assertThat(environment.count("import_row_warning")).isOne();
    }

    @Test
    void coalescedMemberWarningSurvivesCanonicalCommitAndReceiptReplay() {
        Environment environment = environment("coalesced-warning");
        ImportRowWarning warning = new ImportRowWarning(3, "masks", "IMPORT.VIEW_FALLBACK");
        CanonicalImportCommand command = environment.stage(
                "delivery-coalesced-warning", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                cells("mask", ImportCell.value("evil.example"),
                                        "source", ImportCell.absent()), OptionalLong.empty())),
                        row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "evil.example", "source", "second-feed"),
                                OptionalLong.empty()))),
                List.of(), List.of(warning));

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        environment.deleteStage(command);
        var evidence = new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId());

        assertThat(evidence).hasValueSatisfying(receipt -> {
            assertThat(receipt.acceptedRows()).isOne();
            assertThat(receipt.warnings()).containsExactly(warning);
        });
        assertThat(environment.queryLong("SELECT COUNT(*) FROM masks WHERE source = 'second-feed'"))
                .isOne();
        assertThat(environment.count("import_row_warning")).isOne();
    }

    @Test
    void promotesPinnedVersionTwoStageWithoutAWarningTable() throws Exception {
        Environment environment = environment("version-two-stage");
        CanonicalImportCommand command = environment.stage(
                "delivery-version-two", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "old.example"), OptionalLong.empty()))), List.of());
        Path sealed = environment.stagePath(command);
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sealed);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE stage_row_warning");
            statement.execute("PRAGMA user_version=2");
            statement.execute("""
                    UPDATE stage_meta SET schema_version=2,
                    plan_hash='9ab1998b7e0d179935dfedf374f6011d609d6db6bafed535deee771626524d64'
                    """);
        }
        ImportStage oldStage = new ImportStage(command.stage().reference(),
                ImportFileDigests.sha256(sealed), command.stage().sourceRows(),
                command.stage().acceptedRows(), command.stage().rejectedRows());
        CanonicalImportCommand resumed = new CanonicalImportCommand(
                command.deliveryId(), command.sequence(), command.sourceId(),
                command.snapshot(), command.contract(), oldStage);

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(resumed);

        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_row_warning")).isZero();
    }

    @Test
    @Timeout(20)
    void pendingFanOutIsInvisibleAndReceiptPublicationPreservesAnExistingReadSnapshot() throws Exception {
        Environment environment = environment("pending-visibility");
        var mask = branch(environment, "masks", ImportArtifactRole.PRIMARY,
                values("mask", "evil.example"), OptionalLong.of(7));
        var matches = new JdbcCanonicalMatchPlanner(environment.dataSource, environment.schemas);
        var requests = List.of(new CanonicalMatchRequest("mask", mask.matchKeys()));
        ImportPromotionPolicy slotPolicy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        ImportRowWarning warning = new ImportRowWarning(2, "masks", "IMPORT.VIEW_FALLBACK");
        CanonicalImportCommand command = environment.stage(
                "delivery-pending-visibility", slotPolicy,
                List.of(row(2, mask,
                        branch(environment, "hashes", ImportArtifactRole.RELATED,
                                values("hash", "ABCDEF"), OptionalLong.empty()))),
                List.of(new ImportRejectedLogicalRow(3, List.of(
                        new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE")))), List.of(warning));
        var publicationReady = new java.util.concurrent.CountDownLatch(1);
        var releaseCommit = new java.util.concurrent.CountDownLatch(1);
        JdbcCanonicalImportWriter writer = environment.writer(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.BEFORE_COMMIT) {
                publicationReady.countDown();
                try {
                    if (!releaseCommit.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Canonical publication was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Canonical publication interrupted", interrupted);
                }
            }
        });
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection reader = environment.dataSource.getConnection()) {
            reader.setAutoCommit(false);
            var promotion = workers.submit(() -> writer.promote(command));
            assertThat(publicationReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            List<String> publishedTables = List.of("masks", "hashes", "masks_sources", "hashes_sources",
                    "canonical_match_alias", "import_commit", "import_commit_artifact",
                    "export_slot_assignment", "import_slot_resolution",
                    "import_row_warning", "import_row_rejection",
                    "artifact_revision", "artifact_projection_state");
            for (String table : publishedTables) {
                assertThat(visibleCount(reader, table)).as("pending %s", table).isZero();
            }
            assertThat(new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId()))
                    .isEmpty();
            assertThat(matches.plan("masks", EffectiveTime.at(NOW), requests).getFirst().cardinality())
                    .isEqualTo(CanonicalMatchCardinality.ZERO);

            releaseCommit.countDown();
            assertThat(promotion.get(5, java.util.concurrent.TimeUnit.SECONDS).outcome().name())
                    .isEqualTo("COMMITTED");
            for (String table : publishedTables) {
                assertThat(visibleCount(reader, table)).as("pinned snapshot %s", table).isZero();
            }
            assertThat(matches.plan("masks", EffectiveTime.at(NOW), requests).getFirst().cardinality())
                    .isEqualTo(CanonicalMatchCardinality.ONE);
            reader.rollback();
            reader.setAutoCommit(true);
            assertThat(visibleCount(reader, "masks")).isOne();
            assertThat(visibleCount(reader, "hashes")).isOne();
            assertThat(visibleCount(reader, "canonical_match_alias")).isEqualTo(2);
            assertThat(visibleCount(reader, "import_commit")).isOne();
            assertThat(visibleCount(reader, "import_commit_artifact")).isEqualTo(2);
            assertThat(visibleCount(reader, "export_slot_assignment")).isOne();
            assertThat(visibleCount(reader, "import_slot_resolution")).isOne();
            assertThat(environment.queryLong("SELECT revision FROM artifact_revision WHERE artifact='masks'"))
                    .isOne();
            assertThat(environment.queryLong("SELECT revision FROM artifact_revision WHERE artifact='hashes'"))
                    .isOne();
            environment.deleteStage(command);
            assertThat(environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command)
                    .outcome().name()).isEqualTo("ALREADY_COMMITTED");
            assertThat(visibleCount(reader, "masks_sources")).isOne();
            assertThat(visibleCount(reader, "hashes_sources")).isOne();
            assertThat(environment.queryLong("SELECT SUM(occurrences) FROM masks_sources")).isOne();
            assertThat(environment.queryLong("SELECT SUM(occurrences) FROM hashes_sources")).isOne();
            assertThat(new JdbcImportCommitEvidenceStore(environment.dataSource).find(command.deliveryId()))
                    .hasValueSatisfying(receipt -> {
                        assertThat(receipt.acceptedRows()).isOne();
                        assertThat(receipt.rejectedRows()).isOne();
                        assertThat(receipt.warnings()).containsExactly(warning);
                        assertThat(receipt.issues()).containsExactly(
                                new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE"));
                    });
        } finally {
            releaseCommit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static long visibleCount(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + JdbcSql.quote(table));
             var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    @Timeout(30)
    void privatePreparationBurnsReservationsOnlyInTheAuthoritativeDatabase() {
        Environment working = environment("private-reservations-working");
        Environment authority = environment("private-reservations-authority");
        var command = working.stage("private-reservations", ImportPromotionPolicy.defaults(),
                List.of(row(2,
                        branch(working, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "reserved.example"), OptionalLong.empty()),
                        branch(working, "hashes", ImportArtifactRole.RELATED,
                                values("hash", "HASH-RESERVED"), OptionalLong.empty()))), List.of());
        var admission = new JdbcWriterAdmission();
        var failing = working.preparingWriter(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.BEFORE_COMMIT) {
                throw new IocExtractorException("Discard private preparation");
            }
        }, authority, admission, command);

        assertThatThrownBy(() -> failing.promote(command))
                .isInstanceOf(IocExtractorException.class).hasMessage("Discard private preparation");
        assertThat(working.count("masks")).isZero();
        assertThat(authority.count("masks")).isZero();
        assertThat(authority.queryLong("SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'"))
                .isEqualTo(2);
        assertThat(authority.queryLong("SELECT next_value FROM lifecycle_id_allocator")).isEqualTo(3);
        assertThat(working.count("artifact_id_allocator")).isZero();
        assertThat(working.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE name='private_mutation_effect'"))
                .as("journal DDL and effects roll back with private mutations").isZero();

        working.preparingWriter(JdbcCanonicalImportObserver.NOOP, authority, admission, command).promote(command);
        assertThat(working.count("private_mutation_effect")).isEqualTo(2);
        assertThat(working.queryLong("SELECT id FROM masks")).isEqualTo(2);
        assertThat(working.queryLong("SELECT id FROM hashes")).isEqualTo(2);
        assertThat(working.queryLong("SELECT MIN(_lifecycle_id) FROM masks")).isEqualTo(3);
        assertThat(working.queryLong("SELECT MIN(_lifecycle_id) FROM hashes")).isEqualTo(4);
        assertThat(authority.queryLong("SELECT next_value FROM lifecycle_id_allocator")).isEqualTo(5);
        assertThat(authority.count("import_commit")).isZero();
    }

    @Test
    @Timeout(30)
    void privateJournalPreservesSameInstantRenewalThroughTypedPublication() throws Exception {
        Environment working = environment("journal-working");
        Environment canonical = environment("journal-canonical");
        Environment oracle = environment("journal-oracle");
        var initial = working.stage("journal-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "unchanged.example"), OptionalLong.empty()))), List.of());
        working.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var canonicalWriter = new Environment(canonical.dataSource, working.workspaceRoot, canonical.schemas, canonical.identities);
        var oracleWriter = new Environment(oracle.dataSource, working.workspaceRoot, oracle.schemas, oracle.identities);
        canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var admission = new JdbcWriterAdmission();
        var bridge = new SqliteImportPublicationExperiment(canonical.dataSource, admission, List.of("masks", "hashes"));
        var repeated = working.stage("journal-repeat", new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, true, Optional.empty()),
                List.of(row(2, branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "unchanged.example"), OptionalLong.empty()))), List.of());
        var before = typedRows(working.dataSource, "masks");

        working.preparingWriter(JdbcCanonicalImportObserver.NOOP, canonical, admission, repeated).promote(repeated);

        assertThat(typedRows(working.dataSource, "masks"))
                .as("equal-time native renewal cannot be detected by comparing public or internal timestamps").isEqualTo(before);
        var entries = journalEntries(working.dataSource);
        assertThat(entries).hasSize(1).allSatisfy(entry -> {
            assertThat(entry.artifact()).isEqualTo("masks");
            assertThat(entry.rowId()).isOne();
            assertThat(entry.lifecycleId()).isOne();
            assertThat(entry.renewsValidity()).isTrue();
        });
        Instant publicationUtc = NOW.plusSeconds(3);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP, publicationUtc).promote(repeated);
        var renewals = new java.util.LinkedHashSet<String>();
        entries.stream().filter(JdbcPrivateMutationJournal.Entry::renewsValidity)
                .forEach(entry -> renewals.add(entry.artifact() + ":" + entry.rowId()));
        var attempt = bridge.stage(working.dataSource, repeated.deliveryId().value(), repeated.stage().digest().value(), renewals,
                ordinal -> assertThat(canonical.queryLong("SELECT _last_confirmed_at_epoch_ms FROM masks"))
                        .isEqualTo(NOW.toEpochMilli()));
        bridge.publish(attempt, new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(publicationUtc, ZoneOffset.UTC),
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                new FixedRecordValidityPolicy(TTL), repeated.stage().digest().value());
        for (String table : bridge.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("same-instant renewal: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }
        assertThat(canonical.queryLong("SELECT _valid_until_epoch_ms FROM masks"))
                .isEqualTo(publicationUtc.plus(TTL).toEpochMilli());
        working.deleteStage(repeated);
        assertThat(canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(repeated).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        assertThat(working.count("private_mutation_effect")).isOne();
        assertThat(canonical.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE name='private_mutation_effect'"))
                .as("private journal is never canonical receipt authority").isZero();
    }

    private static List<JdbcPrivateMutationJournal.Entry> journalEntries(HikariDataSource source) throws SQLException {
        var entries = new ArrayList<JdbcPrivateMutationJournal.Entry>();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            long ordinal = 0;
            while (true) {
                var chunk = JdbcPrivateMutationJournal.readChunk(connection, ordinal, 256, entries::add);
                if (chunk.count() == 0) { break; }
                ordinal = chunk.lastOrdinal();
            }
            connection.rollback();
        }
        return List.copyOf(entries);
    }

    @Test
    @Timeout(30)
    void typedHiddenChunksPublishRealFanOutSlotsAndReceiptForStageFreeRecovery() throws Exception {
        Environment privateBase = environment("typed-import-private");
        Environment publicBase = environment("typed-import-public");
        var rows = new ArrayList<ImportLogicalRow>();
        for (int index = 0; index < 60; index++) {
            rows.add(row(index + 2,
                    branch(privateBase, "masks", ImportArtifactRole.PRIMARY,
                            cells("mask", ImportCell.value("host-" + index + ".example"),
                                    "source", ImportCell.absent()),
                            index == 0 ? OptionalLong.of(7) : OptionalLong.empty()),
                    branch(privateBase, "hashes", ImportArtifactRole.RELATED,
                            cells("hash", ImportCell.value("HASH-" + index),
                                    "source", ImportCell.absent()), OptionalLong.empty())));
        }
        rows.add(row(62,
                branch(privateBase, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "host-0.example", "source", "coalesced-feed"), OptionalLong.of(7)),
                branch(privateBase, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "HASH-0", "source", "coalesced-feed"), OptionalLong.empty())));
        var warning = new ImportRowWarning(62, "masks", "IMPORT.VIEW_FALLBACK");
        var issue = new ImportRowIssue(63, "masks", "IMPORT.INVALID_VALUE");
        var policy = new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy("reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        var command = privateBase.stage("typed-fan-out", policy, rows,
                List.of(new ImportRejectedLogicalRow(63, List.of(issue))), List.of(warning));
        var nativeResult = privateBase.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        assertThat(nativeResult.acceptedRows()).isEqualTo(60);
        assertThat(nativeResult.rejectedRows()).isOne();
        var admission = new JdbcWriterAdmission();
        var experiment = new SqliteImportPublicationExperiment(publicBase.dataSource, admission,
                List.of("masks", "hashes"));
        var chunkCount = new AtomicInteger();
        var attempt = experiment.stage(privateBase.dataSource, command.deliveryId().value(),
                command.stage().digest().value(), ordinal -> {
                    chunkCount.incrementAndGet();
                    assertThat(publicBase.count("masks")).isZero();
                    assertThat(publicBase.count("hashes")).isZero();
                    assertThat(publicBase.count("canonical_match_alias")).isZero();
                    assertThat(publicBase.count("export_slot_assignment")).isZero();
                    assertThat(new JdbcImportCommitEvidenceStore(publicBase.dataSource).find(command.deliveryId()))
                            .isEmpty();
                });
        assertThat(chunkCount.get()).isGreaterThan(1);
        Instant publicationUtc = NOW.plusSeconds(3);
        Environment corrected = environment("typed-import-corrected-oracle");
        Environment correctedStage = new Environment(corrected.dataSource, privateBase.workspaceRoot,
                corrected.schemas, corrected.identities);
        correctedStage.writer(JdbcCanonicalImportObserver.NOOP, publicationUtc).promote(command);
        var clock = new JdbcLifecycleClock(publicBase.dataSource, Clock.fixed(publicationUtc, ZoneOffset.UTC),
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission);
        try (var pinned = publicBase.dataSource.getConnection()) {
            pinned.setAutoCommit(false);
            assertThat(visibleCount(pinned, "masks")).isZero();
            var publication = experiment.publish(attempt, clock, new FixedRecordValidityPolicy(TTL),
                    command.stage().digest().value());
            assertThat(publication.effectiveMillis()).isEqualTo(publicationUtc.toEpochMilli());
            assertThat(visibleCount(pinned, "masks")).isZero();
            assertThat(visibleCount(pinned, "import_commit")).isZero();
            pinned.rollback();
            assertThat(visibleCount(pinned, "masks")).isEqualTo(60);
            assertThat(visibleCount(pinned, "hashes")).isEqualTo(60);
            assertThat(visibleCount(pinned, "canonical_match_alias")).isEqualTo(120);
        }
        assertThat(publicBase.queryLong("SELECT COUNT(*) FROM export_slot_assignment WHERE profile='reputation'"))
                .isEqualTo(60);
        assertThat(publicBase.queryLong("SELECT assigned_slot FROM import_slot_resolution")).isOne();
        assertThat(publicBase.queryLong("SELECT requested_slot FROM import_slot_resolution")).isEqualTo(7);
        assertThat(publicBase.queryString("SELECT outcome FROM import_slot_resolution"))
                .isEqualTo("OCCUPIED_FALLBACK");
        assertThat(publicBase.queryLong("SELECT COUNT(DISTINCT slot) FROM export_slot_assignment"))
                .isEqualTo(60);
        assertThat(publicBase.queryLong("SELECT revision FROM artifact_revision WHERE artifact='masks'"))
                .isOne();
        assertThat(publicBase.queryLong("SELECT required_generation FROM artifact_projection_state WHERE artifact='hashes'"))
                .isOne();
        assertThat(publicBase.queryString("SELECT source FROM masks WHERE mask='host-0.example'"))
                .isEqualTo("coalesced-feed");
        assertThat(publicBase.queryLong("SELECT _valid_until_epoch_ms FROM masks WHERE mask='host-0.example'"))
                .isEqualTo(publicationUtc.plus(TTL).toEpochMilli());
        var request = new CanonicalMatchRequest("typed-match", new CanonicalArtifactKeyResolver(publicBase.identities)
                .matchKeysOf("masks", ArtifactRow.ordered(Map.of("mask", "host-0.example"))));
        assertThat(new JdbcCanonicalMatchPlanner(publicBase.dataSource, publicBase.schemas)
                .plan("masks", EffectiveTime.at(publicationUtc), List.of(request)).getFirst().cardinality())
                .isEqualTo(CanonicalMatchCardinality.ONE);
        var evidence = new JdbcImportCommitEvidenceStore(publicBase.dataSource).find(command.deliveryId()).orElseThrow();
        assertThat(evidence.acceptedRows()).isEqualTo(60);
        assertThat(evidence.rejectedRows()).isOne();
        assertThat(evidence.warnings()).containsExactly(warning);
        assertThat(evidence.issues()).containsExactly(issue);
        for (String table : experiment.tables()) {
            assertThat(typedRows(publicBase.dataSource, table)).as("corrected canonical oracle: %s", table)
                    .isEqualTo(typedRows(corrected.dataSource, table));
        }
        privateBase.deleteStage(command);
        // Use the real canonical port and service journal; no replacement recovery algorithm.
        var replay = publicBase.writer(JdbcCanonicalImportObserver.NOOP).promote(command);
        assertThat(replay.outcome()).isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        assertThat(replay.effectiveAt()).isEqualTo(publicationUtc);
        var ledger = stagedLedger(command);
        var service = new DataframeImportPromotionService(ledger,
                publicBase.writer(JdbcCanonicalImportObserver.NOOP), CLOCK);
        service.processNext();
        assertThat(ledger.find(command.deliveryId()).orElseThrow().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
        assertThat(publicBase.queryLong("SELECT SUM(occurrences) FROM masks_sources")).isEqualTo(60);
        assertThat(new JdbcImportCommitEvidenceStore(publicBase.dataSource).find(command.deliveryId()))
                .contains(evidence);
    }

    private static List<List<Object>> typedRows(HikariDataSource database, String table) throws SQLException {
        try (var c = database.getConnection(); var s = c.createStatement()) {
            var columns = new ArrayList<String>();
            try (var meta = s.executeQuery("PRAGMA table_info(" + JdbcSql.quote(table) + ")")) {
                while (meta.next()) { columns.add(meta.getString("name")); }
            }
            String projection = JdbcSql.joinedQuoted(columns);
            var values = new ArrayList<List<Object>>();
            try (var rows = s.executeQuery("SELECT " + projection + " FROM " + JdbcSql.quote(table)
                    + " ORDER BY " + projection)) {
                while (rows.next()) {
                    var row = new ArrayList<Object>();
                    for (int index = 1; index <= columns.size(); index++) { row.add(rows.getObject(index)); }
                    values.add(java.util.Collections.unmodifiableList(row));
                }
            }
            return List.copyOf(values);
        }
    }

    @Test
    @Timeout(30)
    void populatedTypedPublicationPreservesClearsCoalescingSlotsAndExpiryHistory() throws Exception {
        Environment working = environment("populated-working");
        Environment canonical = environment("populated-canonical");
        Environment oracle = environment("populated-oracle");
        var policy = new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy("reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        var initial = working.stage("populated-initial", policy, List.of(row(2,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "existing.example", "description", "old"), OptionalLong.of(1)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "EXISTING-HASH"), OptionalLong.empty()))), List.of());
        working.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var canonicalWriter = new Environment(canonical.dataSource, working.workspaceRoot,
                canonical.schemas, canonical.identities);
        var oracleWriter = new Environment(oracle.dataSource, working.workspaceRoot, oracle.schemas, oracle.identities);
        canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var admission = new JdbcWriterAdmission();
        var experiment = new SqliteImportPublicationExperiment(canonical.dataSource, admission, List.of("masks", "hashes"));
        for (String table : experiment.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("legacy absolute values: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }

        var clear = row(2,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        cells("mask", ImportCell.value("existing.example"), "description", ImportCell.nullValue()),
                        OptionalLong.of(9)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "EXISTING-HASH"), OptionalLong.empty()));
        var added = row(3,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "new.example", "description", "new"), OptionalLong.of(1)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "NEW-HASH"), OptionalLong.empty()));
        var duplicate = row(4,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "new.example", "description", "new"), OptionalLong.of(1)),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "NEW-HASH"), OptionalLong.empty()));
        var warning = new ImportRowWarning(4, "masks", "IMPORT.VIEW_FALLBACK");
        var update = working.stage("populated-update", policy, List.of(clear, added, duplicate), List.of(), List.of(warning));
        var renewals = new java.util.LinkedHashSet<String>();
        var observer = new JdbcCanonicalImportObserver() {
            public void after(Phase phase) { }
            public void afterMutation(String artifact, long canonicalRowId, boolean renewsValidity) {
                if (renewsValidity) { renewals.add(artifact + ":" + canonicalRowId); }
            }
        };
        working.reservedWriter(observer, canonical, admission).promote(update);
        Instant utc = NOW.plusSeconds(3);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP, utc).promote(update);
        var attempt = experiment.stage(working.dataSource, update.deliveryId().value(), update.stage().digest().value(), renewals, n -> {
            assertThat(canonical.count("masks")).isOne();
            assertThat(canonical.queryString("SELECT description FROM masks")).isEqualTo("old");
            assertThat(new JdbcImportCommitEvidenceStore(canonical.dataSource).find(update.deliveryId())).isEmpty();
        });
        experiment.publish(attempt, new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(utc, ZoneOffset.UTC),
                new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                new FixedRecordValidityPolicy(TTL), update.stage().digest().value());
        for (String table : experiment.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("populated delta: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }
        assertThat(canonical.queryObject("SELECT description FROM masks WHERE mask='existing.example'")).isNull();
        assertThat(canonical.queryLong("SELECT id FROM masks WHERE mask='new.example'")).isEqualTo(2);
        assertThat(canonical.queryLong("SELECT slot FROM export_slot_assignment WHERE lifecycle_id=1")).isOne();
        assertThat(new JdbcImportCommitEvidenceStore(canonical.dataSource).find(update.deliveryId()).orElseThrow().warnings())
                .containsExactly(warning);
        working.deleteStage(update);
        assertThat(canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(update).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);

        copyVisibleFixture(canonical.dataSource, working.dataSource, experiment.tables());
        assertThat(experiment.nearest("masks", NOW.toEpochMilli())).hasValue(NOW.plus(TTL).plusSeconds(3).toEpochMilli());
        Instant expiredUtc = NOW.plus(TTL).plusSeconds(3);
        var expired = working.stage("populated-expired", policy, List.of(row(2,
                branch(working, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "existing.example", "description", "returned"), OptionalLong.empty()),
                branch(working, "hashes", ImportArtifactRole.RELATED,
                        values("hash", "EXISTING-HASH"), OptionalLong.empty()))), List.of());
        working.reservedWriter(JdbcCanonicalImportObserver.NOOP, canonical, admission, expiredUtc).promote(expired);
        oracleWriter.writer(JdbcCanonicalImportObserver.NOOP, expiredUtc).promote(expired);
        var expiredAttempt = experiment.stage(working.dataSource, expired.deliveryId().value(),
                expired.stage().digest().value(), n -> {
                    assertThat(canonical.queryLong("SELECT id FROM masks WHERE mask='existing.example'")).isOne();
                    assertThat(canonical.count("masks_history")).isZero();
                });
        experiment.publish(expiredAttempt,
                new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(expiredUtc, ZoneOffset.UTC),
                        new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                new FixedRecordValidityPolicy(TTL), expired.stage().digest().value());
        for (String table : experiment.tables()) {
            assertThat(typedRows(canonical.dataSource, table)).as("expired reappearance: %s", table)
                    .isEqualTo(typedRows(oracle.dataSource, table));
        }
        assertThat(canonical.queryLong("SELECT id FROM masks WHERE mask='existing.example'")).isEqualTo(4);
        assertThat(canonical.queryLong("SELECT former_row_id FROM masks_history")).isOne();
        assertThat(canonical.count("masks")).isEqualTo(2);
        assertThat(experiment.nearest("masks", expiredUtc.toEpochMilli()))
                .hasValue(expiredUtc.plus(TTL).toEpochMilli());
        working.deleteStage(expired);
        assertThat(canonicalWriter.writer(JdbcCanonicalImportObserver.NOOP).promote(expired).outcome())
                .isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
    }

    /** Finite C2 oracle hydration only; C3 must use selective streamed hydration. */
    private static void copyVisibleFixture(HikariDataSource source, HikariDataSource target, List<String> tables)
            throws SQLException {
        try (var c = target.getConnection(); var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            c.setAutoCommit(false);
            Throwable failure = null;
            try {
                for (String table : tables.reversed()) { s.executeUpdate("DELETE FROM " + JdbcSql.quote(table)); }
                for (String table : tables) {
                    var rows = typedRows(source, table);
                    if (rows.isEmpty()) { continue; }
                    try (var insert = c.prepareStatement("INSERT INTO " + JdbcSql.quote(table) + " VALUES ("
                            + JdbcSql.placeholders(rows.getFirst().size()) + ")")) {
                        for (var row : rows) {
                            for (int i = 0; i < row.size(); i++) { insert.setObject(i + 1, row.get(i)); }
                            insert.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException | Error caught) {
                failure = caught;
                try { c.rollback(); } catch (SQLException rollback) { caught.addSuppressed(rollback); }
                throw caught;
            } finally {
                try {
                    c.setAutoCommit(true);
                    s.execute("PRAGMA foreign_keys=ON");
                } catch (SQLException restoration) {
                    if (failure == null) { throw restoration; }
                    failure.addSuppressed(restoration);
                }
            }
        }
    }

    @Test
    @Timeout(30)
    void ordinaryPerArtifactPublicationCompletesAndReplaysTheRealTypedConfirmationReceipt() throws Exception {
        Environment working = environment("ordinary-private");
        Environment canonical = environment("ordinary-public");
        Environment oracle = environment("ordinary-oracle");
        var admission = new JdbcWriterAdmission();
        var experiment = new SqliteImportPublicationExperiment(canonical.dataSource, admission, List.of("masks", "hashes"));
        var context = new ConfirmationReceiptContext(new ConfirmationReceiptId("ordinary-receipt"),
                "ordinary-policy", 2, Duration.ofDays(30));
        var commands = new ArrayList<CanonicalArtifactConfirmation>();
        for (int index = 0; index < working.schemas.size(); index++) {
            var schema = working.schemas.get(index);
            var values = new LinkedHashMap<String, String>();
            for (var column : schema.columns()) { values.put(column.name(), null); }
            values.put(schema.artifactName().equals("masks") ? "mask" : "hash", "ordinary-" + index);
            values.put("source", "ordinary-feed");
            var row = ArtifactRow.ordered(values);
            var key = new CanonicalArtifactKeyResolver(working.identities).recordKeyOf(schema.artifactName(), row).orElseThrow();
            var command = new CanonicalArtifactConfirmation(new ObservationId("ordinary-observation"), "ordinary-source",
                    context, schema.artifactName(), List.copyOf(values.keySet()), List.of(new CanonicalRecordConfirmation(
                    new ArtifactRowKey(key.keyHash()), new PreparedArtifactRow(row, Optional.of("id")))));
            commands.add(command);
            new JdbcCanonicalLifecycleWriter(working.dataSource, working.schemas,
                    List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    ignored -> EffectiveTime.at(NOW), new FixedRecordValidityPolicy(TTL),
                    JdbcLifecycleTransactionObserver.NOOP, working.identities, new JdbcWriterAdmission(),
                    new JdbcCanonicalPreparationContext(
                            new JdbcCanonicalReservationAuthority(canonical.dataSource, CLOCK, admission),
                            index == 0 ? c -> JdbcPrivateMutationJournal.open(c, "ordinary-unit-0", "ordinary-pins")
                                    : JdbcMutationEffectSink.Factory.NONE)).confirm(command);
            if (index == 0) {
                assertThat(journalEntries(working.dataSource)).hasSize(1).allSatisfy(entry -> {
                    assertThat(entry.artifact()).isEqualTo(schema.artifactName());
                    assertThat(entry.renewsValidity()).isTrue();
                });
            }
            Instant utc = NOW.plusSeconds(2L * (index + 1));
            ordinaryWriter(oracle, utc).confirm(command);
            var attempt = experiment.stage(working.dataSource, "ordinary-unit-" + index, "ordinary-pins", n -> {
                assertThat(new JdbcConfirmationReceiptStore(canonical.dataSource, canonical.schemas, Duration.ofDays(30))
                        .findComplete("ordinary-source", "ordinary-policy", EffectiveTime.at(utc))).isEmpty();
            });
            experiment.publish(attempt, new JdbcLifecycleClock(canonical.dataSource, Clock.fixed(utc, ZoneOffset.UTC),
                    new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30)), admission),
                    new FixedRecordValidityPolicy(TTL), "ordinary-pins");
            for (String table : experiment.tables()) {
                assertThat(typedRows(canonical.dataSource, table)).as("ordinary unit %s: %s", index, table)
                        .isEqualTo(typedRows(oracle.dataSource, table));
            }
            copyVisibleFixture(canonical.dataSource, working.dataSource, experiment.tables());
        }
        var receipt = new JdbcConfirmationReceiptStore(canonical.dataSource, canonical.schemas, Duration.ofDays(30))
                .findComplete("ordinary-source", "ordinary-policy", EffectiveTime.at(NOW.plusSeconds(4))).orElseThrow();
        assertThat(receipt.artifacts()).hasSize(2);
        var before = typedRows(canonical.dataSource, "masks_sources");
        ordinaryWriter(canonical, NOW.plusSeconds(9)).confirm(commands.getFirst());
        assertThat(typedRows(canonical.dataSource, "masks_sources")).isEqualTo(before);
    }

    private JdbcCanonicalLifecycleWriter ordinaryWriter(Environment environment, Instant utc) {
        return new JdbcCanonicalLifecycleWriter(environment.dataSource, environment.schemas,
                List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                        new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                () -> EffectiveTime.at(utc), new FixedRecordValidityPolicy(TTL), CLOCK, environment.identities);
    }

    @Test
    @Timeout(30)
    void typedDeadlineIndexesFindTheNextLegacyDeadlineAtReferenceSize() throws Exception {
        Environment canonical = environment("deadline-reference");
        var initial = canonical.stage("deadline-reference-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(canonical, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "seed.example"), OptionalLong.empty()))), List.of());
        canonical.writer(JdbcCanonicalImportObserver.NOOP).promote(initial);
        var experiment = new SqliteImportPublicationExperiment(canonical.dataSource, new JdbcWriterAdmission(),
                List.of("masks", "hashes"));
        long deadline = NOW.plus(TTL).toEpochMilli();
        try (var c = canonical.dataSource.getConnection(); var s = c.createStatement()) {
            var names = new ArrayList<String>();
            try (var metadata = s.executeQuery("PRAGMA table_info(masks_import_probe)")) {
                while (metadata.next()) { names.add(metadata.getString("name")); }
            }
            var expressions = new ArrayList<String>();
            for (String name : names) {
                expressions.add(switch (name) {
                    case "id", "_lifecycle_id" -> "n+100";
                    case "mask" -> "'host-'||n||'.example'";
                    case "_row_key" -> "printf('%064x',n)";
                    case "address" -> "'reference:'||n";
                    case "absolute_deadline", "_valid_until_epoch_ms" -> deadline + "+n";
                    default -> "v." + JdbcSql.quote(name);
                });
            }
            s.executeUpdate("WITH RECURSIVE numbers(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM numbers WHERE n<100000)"
                    + " INSERT INTO masks_import_probe(" + JdbcSql.joinedQuoted(names) + ") SELECT "
                    + String.join(",", expressions) + " FROM numbers CROSS JOIN masks_import_probe v WHERE v.id=1");
            s.execute("ANALYZE");
            var plan = new ArrayList<String>();
            try (var query = c.prepareStatement("EXPLAIN QUERY PLAN " + experiment.nearestSql("masks"))) {
                query.setLong(1, deadline);
                query.setLong(2, deadline);
                try (var rows = query.executeQuery()) { while (rows.next()) { plan.add(rows.getString("detail")); } }
            }
            assertThat(plan).anyMatch(detail -> detail.contains("masks_import_probe_absolute_deadline"));
            assertThat(plan).anyMatch(detail -> detail.contains("masks_import_probe_deadline_anchor"));
            assertThat(plan).anyMatch(detail -> detail.contains("masks_import_probe_address"));
            assertThat(plan).anyMatch(detail -> detail.contains("publication_unit_deadline"));
            long before = System.nanoTime();
            assertThat(canonical.queryLong("SELECT MIN(_valid_until_epoch_ms) FROM masks WHERE _valid_until_epoch_ms>"
                    + deadline)).isEqualTo(deadline + 1);
            long baseline = System.nanoTime() - before;
            long after = System.nanoTime();
            assertThat(experiment.nearest("masks", deadline)).hasValue(deadline + 1);
            long indexed = System.nanoTime() - after;
            System.out.println("C2_TYPED_DEADLINE rows=100001 baseline_ns=" + baseline
                    + " indexed_ns=" + indexed + " plan=" + plan);
            assertThat(experiment.nearest("masks", deadline + 100000)).isEmpty();
        }
    }

    @Test
    void everyPreCommitFailureRollsBackAllFanOutArtifactsAndReceiptEvidence() {
        EnumSet<JdbcCanonicalImportObserver.Phase> phases = EnumSet.allOf(
                JdbcCanonicalImportObserver.Phase.class);
        phases.remove(JdbcCanonicalImportObserver.Phase.AFTER_COMMIT);
        for (JdbcCanonicalImportObserver.Phase phase : phases) {
            Environment environment = environment("rollback-" + phase.name());
            CanonicalImportCommand command = environment.stage(
                    "delivery-" + phase.name(), ImportPromotionPolicy.defaults(),
                    List.of(row(2,
                            branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                    values("mask", "evil.example"), OptionalLong.empty()),
                            branch(environment, "hashes", ImportArtifactRole.RELATED,
                                    values("hash", "ABCDEF"), OptionalLong.empty()))),
                    List.of());
            JdbcCanonicalImportWriter writer = environment.writer(observed -> {
                if (observed == phase) {
                    throw new InjectedFailure(phase);
                }
            });

            assertThatThrownBy(() -> writer.promote(command))
                    .hasRootCauseInstanceOf(InjectedFailure.class);
            assertThat(environment.count("masks")).as(phase.name()).isZero();
            assertThat(environment.count("hashes")).as(phase.name()).isZero();
            assertThat(environment.count("canonical_match_alias")).as(phase.name()).isZero();
            assertThat(environment.count("import_commit")).as(phase.name()).isZero();
            assertThat(environment.count("import_commit_artifact")).as(phase.name()).isZero();
        }
    }

    @Test
    void crashAfterCommitResumesFromReceiptWithoutReapplyingCanonicalEffects() {
        Environment environment = environment("after-commit");
        CanonicalImportCommand command = environment.stage(
                "delivery-after-commit", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example"), OptionalLong.empty()))), List.of());
        JdbcCanonicalImportWriter crashing = environment.writer(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.AFTER_COMMIT) {
                throw new InjectedFailure(phase);
            }
        });

        assertThatThrownBy(() -> crashing.promote(command)).hasRootCauseInstanceOf(InjectedFailure.class);
        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_commit")).isOne();
        environment.deleteStage(command);

        var replay = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(replay.outcome().name()).isEqualTo("ALREADY_COMMITTED");
        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("masks_sources")).isOne();
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isOne();
    }

    @Test
    void runtimeWriterSamplesLifecycleTimeInsideItsOwnedTransaction() {
        Environment environment = environment("runtime-clock");
        CanonicalImportCommand command = environment.stage(
                "delivery-runtime-clock", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "clock.example"), OptionalLong.empty()))), List.of());

        environment.runtimeWriter().promote(command);

        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_commit")).isOne();
    }

    @Test
    void orderedImportFieldUsesSharedRegistrationRankAcrossReverseCompletion() {
        Environment environment = environment("ordered-import");
        JdbcObservationRegistrationStore registrations =
                new JdbcObservationRegistrationStore(environment.dataSource, CLOCK);
        CanonicalImportCommand older = environment.stage(
                "ordered-older", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "ordered.example", "source", "older"),
                        OptionalLong.empty()))), List.of());
        CanonicalImportCommand newer = environment.stage(
                "ordered-newer", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "ordered.example", "source", "newer"),
                        OptionalLong.empty()))), List.of());
        RegisteredObservation olderRegistration = registrations.registerNew(
                new ObservationId(older.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
        RegisteredObservation newerRegistration = registrations.registerNew(
                new ObservationId(newer.deliveryId().value()), ObservationOrigin.MANAGED_IMPORT);
        JdbcCanonicalImportWriter writer = environment.orderedWriter();

        writer.promote(withRegistration(newer, newerRegistration));
        writer.promote(withRegistration(older, olderRegistration));

        assertThat(environment.queryString("SELECT source FROM masks"))
                .isEqualTo("newer");
        assertThat(environment.queryLong("""
                SELECT admission_order FROM canonical_lifecycle_field_origin
                WHERE artifact = 'masks' AND field_name = 'source'
                """)).isEqualTo(newerRegistration.admissionOrder().value());
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isOne();
    }

    @Test
    void serviceLedgerRemainsPromotingAcrossPostCommitCrashThenAdvancesFromReceipt() {
        Environment environment = environment("saga-resume");
        CanonicalImportCommand command = environment.stage(
                "delivery-saga", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "saga.example"), OptionalLong.empty()))), List.of());
        JdbcImportDeliveryLedger ledger = stagedLedger(command);
        JdbcCanonicalImportWriter crashing = environment.writer(phase -> {
            if (phase == JdbcCanonicalImportObserver.Phase.AFTER_COMMIT) {
                throw new InjectedFailure(phase);
            }
        });

        assertThatThrownBy(() -> new DataframeImportPromotionService(
                ledger, crashing, CLOCK).processNext())
                .hasRootCauseInstanceOf(InjectedFailure.class);
        assertThat(ledger.find(command.deliveryId()).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.PROMOTING);
        assertThat(environment.count("masks")).isOne();

        var resumed = new DataframeImportPromotionService(
                ledger, environment.writer(JdbcCanonicalImportObserver.NOOP), CLOCK).processNext();

        assertThat(resumed.workPerformed()).isTrue();
        assertThat(ledger.find(command.deliveryId()).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
        assertThat(environment.count("masks")).isOne();
        assertThat(environment.count("import_commit")).isOne();
    }

    @Test
    void mergeConflictRejectsEveryFanOutBranchBeforeCanonicalMutation() {
        Environment environment = environment("fanout-reject");
        CanonicalImportCommand seed = environment.stage(
                "delivery-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "hashes", ImportArtifactRole.PRIMARY,
                        values("hash", "ABCDEF", "description", "known"), OptionalLong.empty()))),
                List.of());
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(seed);

        Map<String, ImportMergePolicy> rejecting = Map.of(
                "hash", ImportMergePolicy.AUTHORITATIVE,
                "description", ImportMergePolicy.REJECT_CONFLICT);
        CanonicalImportCommand command = environment.stage(
                "delivery-conflict", ImportPromotionPolicy.defaults(),
                List.of(row(3,
                        branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "new.example"), OptionalLong.empty()),
                        branch(environment, "hashes", ImportArtifactRole.RELATED,
                                values("hash", "ABCDEF", "description", "different"),
                                rejecting, OptionalLong.empty()))), List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(result.acceptedRows()).isZero();
        assertThat(result.rejectedRows()).isOne();
        assertThat(result.publicMutations()).isZero();
        assertThat(environment.count("masks")).isZero();
        assertThat(environment.count("hashes")).isOne();
        assertThat(environment.queryString("SELECT description FROM hashes"))
                .isEqualTo("known");
        assertThat(environment.queryString("""
                SELECT diagnostic_code FROM import_row_rejection
                WHERE delivery_id = 'delivery-conflict'
                """)).isEqualTo("IMPORT.MERGE_CONFLICT");
    }

    @Test
    void rejectDeliveryPolicyCommitsOnlyReceiptWhenAnyStagedRowIsInvalid() {
        Environment environment = environment("reject-delivery");
        ImportPromotionPolicy policy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.REJECT_DELIVERY, false, Optional.empty());
        CanonicalImportCommand command = environment.stage(
                "delivery-rejected", policy,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "would-not-commit.example"), OptionalLong.empty()))),
                List.of(new ImportRejectedLogicalRow(3, List.of(
                        new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE")))));

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(result.acceptedRows()).isZero();
        assertThat(result.rejectedRows()).isEqualTo(2);
        assertThat(environment.count("masks")).isZero();
        assertThat(environment.count("import_commit")).isOne();
        assertThat(environment.count("import_row_rejection")).isEqualTo(2);
    }

    @Test
    void authoritativeClearMutatesPublicBytesOnceWhileNoOpRenewPolicyControlsTtlOnly() {
        Environment environment = environment("ttl-merge");
        CanonicalImportCommand seed = environment.stage(
                "delivery-ttl-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example", "description", "old"), OptionalLong.empty()))),
                List.of());
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(seed);
        long firstDeadline = environment.queryLong("SELECT _valid_until_epoch_ms FROM masks");

        CanonicalImportCommand clear = environment.stage(
                "delivery-clear", new ImportPromotionPolicy(
                        ImportRowFailurePolicy.ACCEPT_VALID, true, Optional.empty()),
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        cells("mask", ImportCell.value("evil.example"),
                                "description", ImportCell.nullValue()), OptionalLong.empty()))),
                List.of());
        Instant clearTime = NOW.plus(Duration.ofHours(1));
        var cleared = environment.writer(JdbcCanonicalImportObserver.NOOP, clearTime).promote(clear);

        assertThat(cleared.publicMutations()).isOne();
        assertThat(environment.queryString("SELECT description FROM masks")).isNull();
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isEqualTo(2);
        long renewedDeadline = environment.queryLong("SELECT _valid_until_epoch_ms FROM masks");
        assertThat(renewedDeadline).isEqualTo(firstDeadline + Duration.ofHours(1).toMillis());

        CanonicalImportCommand noOp = environment.stage(
                "delivery-noop", ImportPromotionPolicy.defaults(),
                List.of(row(4, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evil.example"), OptionalLong.empty()))), List.of());
        var unchanged = environment.writer(
                JdbcCanonicalImportObserver.NOOP, NOW.plus(Duration.ofHours(2))).promote(noOp);

        assertThat(unchanged.publicMutations()).isZero();
        assertThat(unchanged.affectedArtifacts()).isEmpty();
        assertThat(unchanged.observedArtifacts()).isEmpty();
        assertThat(environment.queryLong(
                "SELECT revision FROM artifact_revision WHERE artifact = 'masks'"))
                .isEqualTo(2);
        assertThat(environment.queryLong("SELECT _valid_until_epoch_ms FROM masks"))
                .isEqualTo(renewedDeadline);
    }

    @Test
    void requestedSlotUsesExactPreferenceThenLowestAvailableFallbackAtomically() {
        Environment environment = environment("preferred-slot");
        ImportPromotionPolicy slotPolicy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        CanonicalImportCommand first = environment.stage(
                "delivery-slot-1", slotPolicy,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "one.example"), OptionalLong.of(7)))), List.of());
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(first);
        CanonicalImportCommand second = environment.stage(
                "delivery-slot-2", slotPolicy,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "two.example"), OptionalLong.of(7)))), List.of());

        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(second);

        assertThat(environment.queryLong("""
                SELECT slot FROM export_slot_assignment
                WHERE profile = 'reputation' AND lifecycle_id = 1
                """)).isEqualTo(7);
        assertThat(environment.queryLong("""
                SELECT slot FROM export_slot_assignment
                WHERE profile = 'reputation' AND lifecycle_id = 2
                """)).isOne();
        assertThat(environment.count("export_slot_assignment")).isEqualTo(2);
        assertThat(environment.queryString("""
                SELECT outcome FROM import_slot_resolution
                WHERE delivery_id = 'delivery-slot-2'
                """)).isEqualTo("OCCUPIED_FALLBACK");
    }

    @Test
    void requestedSlotsAreScopedByProfileAndArtifact() {
        Environment environment = environment("preferred-slot-scope");
        ImportPromotionPolicy slotPolicy = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        CanonicalImportCommand command = environment.stage(
                "delivery-slot-scope", slotPolicy,
                List.of(
                        row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                                values("mask", "one.example"), OptionalLong.of(7))),
                        row(3, branch(environment, "hashes", ImportArtifactRole.PRIMARY,
                                values("hash", "AABB"), OptionalLong.of(7)))),
                List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(command);

        assertThat(result.acceptedRows()).isEqualTo(2);
        assertThat(environment.queryLong("""
                SELECT COUNT(*) FROM export_slot_assignment
                WHERE profile = 'reputation' AND slot = 7
                """)).isEqualTo(2);
    }

    @Test
    void preserveExistingSlotKeepsSurvivorAssignmentAndPersistsSafeMismatchEvidence() {
        Environment environment = environment("survivor-slot-preserve");
        ImportPromotionPolicy preserve = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(environment.stage(
                "delivery-survivor-seed", preserve,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "survivor.example", "description", "old"),
                        OptionalLong.of(7)))), List.of()));
        CanonicalImportCommand update = environment.stage(
                "delivery-survivor-preserve", preserve,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "survivor.example", "description", "new"),
                        OptionalLong.of(8)))), List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(update);

        assertThat(result.acceptedRows()).isOne();
        assertThat(environment.queryString("SELECT description FROM masks")).isEqualTo("new");
        assertThat(environment.queryLong("SELECT slot FROM export_slot_assignment")).isEqualTo(7);
        assertThat(environment.queryString("""
                SELECT outcome FROM import_slot_resolution
                WHERE delivery_id = 'delivery-survivor-preserve'
                """)).isEqualTo("SURVIVOR_MISMATCH_PRESERVED");
    }

    @Test
    void rejectMismatchPolicyRejectsWholeLogicalRowBeforeBusinessMutation() {
        Environment environment = environment("survivor-slot-reject");
        ImportPromotionPolicy preserve = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.PRESERVE_EXISTING)));
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(environment.stage(
                "delivery-strict-seed", preserve,
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "strict.example", "description", "old"),
                        OptionalLong.of(7)))), List.of()));
        ImportPromotionPolicy strict = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, false,
                Optional.of(new ImportRequestedSlotPolicy(
                        "reputation", ImportExistingSlotPolicy.REJECT_MISMATCH)));
        CanonicalImportCommand update = environment.stage(
                "delivery-survivor-reject", strict,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "strict.example", "description", "new"),
                        OptionalLong.of(8)))), List.of());

        var result = environment.writer(JdbcCanonicalImportObserver.NOOP).promote(update);

        assertThat(result.acceptedRows()).isZero();
        assertThat(result.rejectedRows()).isOne();
        assertThat(environment.queryString("SELECT description FROM masks")).isEqualTo("old");
        assertThat(environment.queryLong("SELECT slot FROM export_slot_assignment")).isEqualTo(7);
        assertThat(environment.queryString("""
                SELECT diagnostic_code FROM import_row_rejection
                WHERE delivery_id = 'delivery-survivor-reject'
                """)).isEqualTo("IMPORT.EXISTING_SLOT_MISMATCH");
    }

    @Test
    void sealedStageIntegrityRejectsMissingOrChangedBytes() throws IOException {
        Environment missingEnvironment = environment("stage-missing");
        CanonicalImportCommand missing = missingEnvironment.stage(
                "delivery-stage-missing", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(missingEnvironment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "missing.example"), OptionalLong.empty()))), List.of());
        missingEnvironment.deleteStage(missing);

        assertThatThrownBy(() -> missingEnvironment.writer(JdbcCanonicalImportObserver.NOOP).promote(missing))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Pinned import stage is not a regular sealed file");

        Environment changedEnvironment = environment("stage-changed");
        CanonicalImportCommand changed = changedEnvironment.stage(
                "delivery-stage-changed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(changedEnvironment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "changed.example"), OptionalLong.empty()))), List.of());
        Files.writeString(changedEnvironment.stagePath(changed), "tampered", StandardOpenOption.APPEND);

        assertThatThrownBy(() -> changedEnvironment.writer(JdbcCanonicalImportObserver.NOOP).promote(changed))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Pinned import stage digest does not match sealed bytes");
    }

    @Test
    void pinnedStageMetadataRejectsEveryPromotionEvidenceMismatch() {
        Environment environment = environment("stage-evidence");
        CanonicalImportCommand command = environment.stage(
                "delivery-stage-evidence", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "evidence.example"), OptionalLong.empty()))),
                List.of(new ImportRejectedLogicalRow(3, List.of(
                        new ImportRowIssue(3, "masks", "IMPORT.INVALID_VALUE")))));
        ImportSnapshot snapshot = command.snapshot();
        ImportContractPin contract = command.contract();
        ImportStage stage = command.stage();
        List<CanonicalImportCommand> mismatches = List.of(
                commandWith(command, new ImportSnapshot(
                        snapshot.reference(), new ImportSha256("c".repeat(64)), snapshot.size()), contract, stage),
                commandWith(command, new ImportSnapshot(
                        snapshot.reference(), snapshot.digest(), snapshot.size() + 1), contract, stage),
                commandWith(command, snapshot, new ImportContractPin(
                        new ImportContractId("other-v1"), contract.version(), contract.fingerprint()), stage),
                commandWith(command, snapshot, new ImportContractPin(
                        contract.id(), contract.version() + 1, contract.fingerprint()), stage),
                commandWith(command, snapshot, new ImportContractPin(
                        contract.id(), contract.version(), new ImportContractFingerprint("c".repeat(64))), stage),
                commandWith(command, snapshot, contract, new ImportStage(
                        stage.reference(), stage.digest(), stage.sourceRows() + 1,
                        stage.acceptedRows(), stage.rejectedRows())),
                commandWith(command, snapshot, contract, new ImportStage(
                        stage.reference(), stage.digest(), stage.sourceRows(),
                        stage.acceptedRows() - 1, stage.rejectedRows())),
                commandWith(command, snapshot, contract, new ImportStage(
                        stage.reference(), stage.digest(), stage.sourceRows(),
                        stage.acceptedRows(), stage.rejectedRows() - 1)));

        for (CanonicalImportCommand mismatch : mismatches) {
            assertThatThrownBy(() -> environment.writer(JdbcCanonicalImportObserver.NOOP).promote(mismatch))
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessage("Import stage metadata does not match promotion evidence");
        }
        assertThat(environment.count("import_commit")).isZero();
        assertThat(environment.count("masks")).isZero();
    }

    @Test
    void canonicalReceiptRejectsEveryIdentityMismatch() {
        Environment environment = environment("receipt-identity");
        CanonicalImportCommand command = environment.stage(
                "delivery-receipt-identity", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "receipt.example"), OptionalLong.empty()))), List.of());
        JdbcCanonicalImportWriter writer = environment.writer(JdbcCanonicalImportObserver.NOOP);
        writer.promote(command);
        List<ReceiptMutation> mutations = List.of(
                new ReceiptMutation("sequence_no", command.sequence().value() + 1),
                new ReceiptMutation("observation_id", "import:other-delivery"),
                new ReceiptMutation("source_id", "other-source"),
                new ReceiptMutation("snapshot_sha256", "c".repeat(64)),
                new ReceiptMutation("snapshot_size", command.snapshot().size() + 1),
                new ReceiptMutation("contract_id", "other-v1"),
                new ReceiptMutation("contract_version", command.contract().version() + 1),
                new ReceiptMutation("contract_fingerprint", "c".repeat(64)),
                new ReceiptMutation("stage_sha256", "c".repeat(64)));

        for (ReceiptMutation mutation : mutations) {
            Object original = environment.queryObject(
                    "SELECT " + mutation.column() + " FROM import_commit");
            try {
                environment.updateReceipt(command.deliveryId(), mutation.column(), mutation.value());
                assertThatThrownBy(() -> writer.promote(command))
                        .as(mutation.column())
                        .isInstanceOf(IocExtractorException.class)
                        .hasMessage("Import delivery identity conflicts with canonical receipt");
            } finally {
                environment.updateReceipt(command.deliveryId(), mutation.column(), original);
            }
        }
        assertThat(writer.promote(command).outcome().name()).isEqualTo("ALREADY_COMMITTED");
        assertThat(environment.count("masks")).isOne();
    }

    @Test
    void unchangedRenewalPersistsAndReplaysDeadlineOnlyArtifactEvidence() {
        Environment environment = environment("deadline-only-evidence");
        environment.writer(JdbcCanonicalImportObserver.NOOP).promote(environment.stage(
                "delivery-deadline-seed", ImportPromotionPolicy.defaults(),
                List.of(row(2, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "renew.example"), OptionalLong.empty()))), List.of()));
        ImportPromotionPolicy renewUnchanged = new ImportPromotionPolicy(
                ImportRowFailurePolicy.ACCEPT_VALID, true, Optional.empty());
        CanonicalImportCommand renewal = environment.stage(
                "delivery-deadline-only", renewUnchanged,
                List.of(row(3, branch(environment, "masks", ImportArtifactRole.PRIMARY,
                        values("mask", "renew.example"), OptionalLong.empty()))), List.of());
        JdbcCanonicalImportWriter writer = environment.writer(
                JdbcCanonicalImportObserver.NOOP, NOW.plus(Duration.ofHours(1)));

        var committed = writer.promote(renewal);
        var replayed = writer.promote(renewal);

        assertThat(committed.publicMutations()).isZero();
        assertThat(committed.affectedArtifacts()).isEmpty();
        assertThat(committed.observedArtifacts()).containsExactly("masks");
        assertThat(committed.projectionGenerations()).isEmpty();
        assertThat(replayed)
                .usingRecursiveComparison()
                .ignoringFields("outcome")
                .isEqualTo(committed);
        assertThat(replayed.outcome().name()).isEqualTo("ALREADY_COMMITTED");
        assertThat(environment.queryObject(
                """
                SELECT projection_generation FROM import_commit_artifact
                WHERE delivery_id = 'delivery-deadline-only'
                """)).isNull();
    }

    @Test
    void constructorRejectsAmbiguousDefinitionsAndNonPositiveReceiptRetention() {
        Environment environment = environment("writer-configuration");
        List<ArtifactIdAllocatorDefinition> allocators = List.of(
                new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1));

        assertThatThrownBy(() -> constructWriter(
                environment, List.of(schema("masks", "mask"), schema("masks", "mask")),
                allocators, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate dataframe artifact schema: masks");
        assertThatThrownBy(() -> constructWriter(
                environment, environment.schemas,
                List.of(allocators.getFirst(), allocators.getFirst()), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate public id allocator definition: masks");
        assertThatThrownBy(() -> constructWriter(
                environment, environment.schemas, allocators, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("receiptRetention must be positive");
        assertThatThrownBy(() -> constructWriter(
                environment, environment.schemas, allocators, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("receiptRetention must be positive");
    }

    private JdbcCanonicalImportWriter constructWriter(
            Environment environment,
            List<DataframeArtifactSchema> schemas,
            List<ArtifactIdAllocatorDefinition> allocators,
            Duration receiptRetention) {
        return new JdbcCanonicalImportWriter(
                environment.dataSource, schemas, allocators, environment.identities,
                environment.workspaceRoot, ignored -> EffectiveTime.at(NOW),
                new FixedRecordValidityPolicy(TTL), CLOCK, new JdbcWriterAdmission(),
                JdbcCanonicalImportObserver.NOOP, receiptRetention);
    }

    private CanonicalImportCommand commandWith(
            CanonicalImportCommand command,
            ImportSnapshot snapshot,
            ImportContractPin contract,
            ImportStage stage) {
        return new CanonicalImportCommand(
                command.deliveryId(), command.sequence(), command.sourceId(), snapshot, contract, stage);
    }

    private CanonicalImportCommand withRegistration(
            CanonicalImportCommand command, RegisteredObservation registration) {
        return new CanonicalImportCommand(
                command.deliveryId(), command.sequence(), command.sourceId(), command.snapshot(),
                command.contract(), command.stage(), registration);
    }

    private Environment environment(String name) {
        return environment(name, List.of(schema("masks", "mask"), schema("hashes", "hash")),
                List.of(identity("masks", "mask"), identity("hashes", "hash")));
    }

    private Environment environment(String name, List<DataframeArtifactSchema> schemas, List<ArtifactIdentityDefinition> identities) {
        Path database = tempDir.resolve(databases.incrementAndGet() + "-" + name + ".db");
        HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "dataframe", "jdbc:sqlite:" + database, "low-memory", 4, 4));
        dataSources.add(dataSource);
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        new JdbcArtifactIdentityStore(dataSource, CLOCK).ensureAll(identities);
        activate(dataSource, schemas);
        return new Environment(dataSource, tempDir.resolve("stages-" + name), schemas, identities);
    }

    private JdbcImportDeliveryLedger stagedLedger(CanonicalImportCommand command) {
        Path database = tempDir.resolve(databases.incrementAndGet() + "-service.db");
        HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + database, "low-memory", 4, 4));
        dataSources.add(dataSource);
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        JdbcImportDeliveryLedger ledger = new JdbcImportDeliveryLedger(dataSource);
        ledger.reserveClaim(new ImportClaimReservation(
                command.deliveryId(), command.sourceId(), "candidate-saga", NOW));
        transition(ledger, command, ImportDeliveryState.DETECTED,
                ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        transition(ledger, command, ImportDeliveryState.CLAIMING,
                ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
        transition(ledger, command, ImportDeliveryState.CLAIMED,
                ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryCheckpoint.snapshot(command.snapshot()));
        transition(ledger, command, ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryCheckpoint.contract(command.contract()));
        transition(ledger, command, ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
        transition(ledger, command, ImportDeliveryState.STAGING,
                ImportDeliveryState.STAGED,
                ImportDeliveryCheckpoint.stage(command.stage()));
        return ledger;
    }

    private void transition(JdbcImportDeliveryLedger ledger,
                            CanonicalImportCommand command,
                            ImportDeliveryState expected,
                            ImportDeliveryState next,
                            ImportDeliveryCheckpoint checkpoint) {
        var delivery = ledger.find(command.deliveryId()).orElseThrow();
        assertThat(delivery.state()).isEqualTo(expected);
        assertThat(ledger.transition(new ImportDeliveryTransition(
                command.deliveryId(), expected, delivery.version(), next,
                Optional.empty(), checkpoint, Optional.empty(), NOW)).name())
                .isEqualTo("APPLIED");
    }

    private DataframeArtifactSchema schema(String artifact, String keyColumn) {
        return new DataframeArtifactSchema(artifact, List.of(
                new DataframeColumn("id", "INTEGER"),
                new DataframeColumn(keyColumn, "TEXT"),
                new DataframeColumn("source", "TEXT"),
                new DataframeColumn("description", "TEXT")));
    }

    private ArtifactIdentityDefinition identity(String artifact, String keyColumn) {
        return new ArtifactIdentityDefinition(
                artifact,
                new CanonicalKeyDefinition(
                        artifact + "-row-v1", CanonicalKeyMode.COMPOSITE, List.of(keyColumn)),
                List.of(new CanonicalKeyDefinition(
                        artifact + "-match-v1", CanonicalKeyMode.COMPOSITE, List.of(keyColumn))),
                1);
    }

    private void activate(HikariDataSource dataSource, List<DataframeArtifactSchema> schemas) {
        var control = new JdbcLifecycleControlStore(dataSource, schemas);
        var disabled = control.load();
        var activating = disabled.beginActivation("import-test-fixed-12h-v1");
        assertThat(control.compareAndSet(disabled, activating)).isTrue();
        assertThat(control.compareAndSet(
                activating, activating.completeActivation(EffectiveTime.at(NOW)))).isTrue();
    }

    private ImportLogicalRow row(long sourceRow, ImportArtifactBranch... branches) {
        return new ImportLogicalRow(sourceRow, List.of(branches));
    }

    private ImportArtifactBranch branch(Environment environment,
                                        String artifact,
                                        ImportArtifactRole role,
                                        Map<String, ImportCell> cells,
                                        OptionalLong requestedSlot) {
        Map<String, ImportMergePolicy> policies = new LinkedHashMap<>();
        cells.keySet().forEach(column -> policies.put(column, ImportMergePolicy.AUTHORITATIVE));
        return branch(environment, artifact, role, cells, policies, requestedSlot);
    }

    private ImportArtifactBranch branch(Environment environment,
                                        String artifact,
                                        ImportArtifactRole role,
                                        Map<String, ImportCell> cells,
                                        Map<String, ImportMergePolicy> policies,
                                        OptionalLong requestedSlot) {
        Map<String, String> keyValues = new LinkedHashMap<>();
        cells.forEach((column, cell) -> keyValues.put(column,
                cell.presence() == ImportCell.Presence.VALUE ? cell.value() : null));
        CanonicalArtifactKeyResolver resolver = new CanonicalArtifactKeyResolver(environment.identities());
        ArtifactRow keyRow = ArtifactRow.ordered(keyValues);
        CanonicalKeyMaterial recordKey = resolver.recordKeyOf(artifact, keyRow).orElseThrow();
        return new ImportArtifactBranch(
                artifact, role, cells, policies, requestedSlot, Optional.of(recordKey),
                resolver.matchKeysOf(artifact, keyRow));
    }

    private Map<String, ImportCell> values(String... pairs) {
        Map<String, ImportCell> cells = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            cells.put(pairs[index], ImportCell.value(pairs[index + 1]));
        }
        return cells;
    }

    private Map<String, ImportCell> cells(Object... pairs) {
        Map<String, ImportCell> cells = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            cells.put((String) pairs[index], (ImportCell) pairs[index + 1]);
        }
        return cells;
    }

    private final class Environment {
        private final HikariDataSource dataSource;
        private final Path workspaceRoot;
        private final List<DataframeArtifactSchema> schemas;
        private final List<ArtifactIdentityDefinition> identities;
        private long sequence;

        private Environment(HikariDataSource dataSource,
                            Path workspaceRoot,
                            List<DataframeArtifactSchema> schemas,
                            List<ArtifactIdentityDefinition> identities) {
            this.dataSource = dataSource;
            this.workspaceRoot = workspaceRoot;
            this.schemas = schemas;
            this.identities = identities;
        }

        private List<ArtifactIdentityDefinition> identities() {
            return identities;
        }

        private JdbcCanonicalImportWriter writer(JdbcCanonicalImportObserver observer) {
            return writer(observer, NOW);
        }

        private JdbcCanonicalImportWriter reservedWriter(JdbcCanonicalImportObserver observer,
                                                          Environment authority,
                                                          JdbcWriterAdmission admission) {
            return reservedWriter(observer, authority, admission, NOW);
        }

        private JdbcCanonicalImportWriter reservedWriter(JdbcCanonicalImportObserver observer,
                                                          Environment authority,
                                                          JdbcWriterAdmission admission,
                                                          Instant utc) {
            return new JdbcCanonicalImportWriter(dataSource, schemas,
                    List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot, ignored -> EffectiveTime.at(utc),
                    new FixedRecordValidityPolicy(TTL), new JdbcWriterAdmission(),
                    observer, Duration.ofDays(90), Map.of(), new JdbcCanonicalReservationAuthority(authority.dataSource, CLOCK, admission));
        }

        private JdbcCanonicalImportWriter preparingWriter(JdbcCanonicalImportObserver observer,
                                                          Environment authority,
                                                          JdbcWriterAdmission admission,
                                                          CanonicalImportCommand command) {
            return new JdbcCanonicalImportWriter(dataSource, schemas,
                    List.of(new ArtifactIdAllocatorDefinition("masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition("hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot, ignored -> EffectiveTime.at(NOW),
                    new FixedRecordValidityPolicy(TTL), new JdbcWriterAdmission(), observer, Duration.ofDays(90), Map.of(),
                    new JdbcCanonicalPreparationContext(new JdbcCanonicalReservationAuthority(authority.dataSource, CLOCK, admission),
                            c -> JdbcPrivateMutationJournal.open(c, command.deliveryId().value(), command.stage().digest().value())));
        }

        private JdbcCanonicalImportWriter writer(JdbcCanonicalImportObserver observer,
                                                  Instant effectiveAt) {
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(
                            new ArtifactIdAllocatorDefinition(
                                    "masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition(
                                    "hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    ignored -> EffectiveTime.at(effectiveAt),
                    new FixedRecordValidityPolicy(TTL), CLOCK,
                    new JdbcWriterAdmission(), observer, Duration.ofDays(90));
        }

        private JdbcCanonicalImportWriter runtimeWriter() {
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(
                            new ArtifactIdAllocatorDefinition(
                                    "masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition(
                                    "hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    new JdbcLifecycleClock(dataSource, CLOCK,
                            new LifecycleClockPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30))),
                    new FixedRecordValidityPolicy(TTL), CLOCK, new JdbcWriterAdmission());
        }

        private JdbcCanonicalImportWriter orderedWriter() {
            ArtifactWritePolicy policy = new ArtifactWritePolicy(
                    ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST,
                    null,
                    Map.of("source",
                            ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
            return policyWriter(Map.of("masks", policy));
        }

        private JdbcCanonicalImportWriter policyWriter(Map<String, ArtifactWritePolicy> policies) {
            return new JdbcCanonicalImportWriter(
                    dataSource, schemas,
                    List.of(
                            new ArtifactIdAllocatorDefinition(
                                    "masks", ArtifactIdStrategy.ASCENDING, 1, 1),
                            new ArtifactIdAllocatorDefinition(
                                    "hashes", ArtifactIdStrategy.ASCENDING, 1, 1)),
                    identities, workspaceRoot,
                    ignored -> EffectiveTime.at(NOW),
                    new FixedRecordValidityPolicy(TTL), CLOCK,
                    new JdbcWriterAdmission(), JdbcCanonicalImportObserver.NOOP,
                    Duration.ofDays(90), policies);
        }

        private void deleteStage(CanonicalImportCommand command) {
            try {
                Files.delete(stagePath(command));
            } catch (IOException failure) {
                throw new AssertionError("Cannot remove sealed stage for receipt replay test", failure);
            }
        }

        private Path stagePath(CanonicalImportCommand command) {
            return new ImportWorkspaceLayout(workspaceRoot).paths(command.deliveryId()).sealed();
        }

        private CanonicalImportCommand stage(String delivery,
                                             ImportPromotionPolicy policy,
                                             List<ImportLogicalRow> rows,
                                             List<ImportRejectedLogicalRow> rejectedRows) {
            return stage(delivery, policy, rows, rejectedRows, List.of());
        }

        private CanonicalImportCommand stage(String delivery,
                                             ImportPromotionPolicy policy,
                                             List<ImportLogicalRow> rows,
                                             List<ImportRejectedLogicalRow> rejectedRows,
                                             List<ImportRowWarning> warnings) {
            ImportDeliveryId deliveryId = new ImportDeliveryId(delivery);
            ImportSnapshot snapshot = new ImportSnapshot(
                    new ImportSnapshotReference("snapshot:" + delivery),
                    new ImportSha256(DIGEST), 100);
            ImportContractPin contract = new ImportContractPin(
                    new ImportContractId("contract-v1"), 1,
                    new ImportContractFingerprint(FINGERPRINT));
            CreateImportWorkspaceCommand create = new CreateImportWorkspaceCommand(
                    deliveryId, snapshot, contract, ImportDuplicatePolicy.COALESCE, policy);
            JdbcImportWorkspace workspace = new JdbcImportWorkspace(
                    workspaceRoot, workspaceLimits(), CLOCK);
            ImportStage stage;
            try (ImportWorkspaceWriter writer = workspace.create(create)) {
                rows.forEach(row -> writer.append(row,
                        warnings.stream().filter(warning -> warning.sourceRowNumber() == row.sourceRowNumber())
                                .toList()));
                rejectedRows.forEach(writer::reject);
                stage = writer.seal();
            }
            workspace.verifySealed(create, stage);
            return new CanonicalImportCommand(
                    deliveryId, new ImportDeliverySequence(++sequence),
                    new ImportSourceId("local-feed"), snapshot, contract, stage);
        }

        private long count(String table) {
            return queryLong("SELECT COUNT(*) FROM " + table);
        }

        private long queryLong(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        private String queryString(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        private Object queryObject(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1);
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        private void updateReceipt(ImportDeliveryId deliveryId, String column, Object value) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE import_commit SET " + column + " = ? WHERE delivery_id = ?")) {
                statement.setObject(1, value);
                statement.setString(2, deliveryId.value());
                assertThat(statement.executeUpdate()).isOne();
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private ImportWorkspaceLimits workspaceLimits() {
        return new ImportWorkspaceLimits(
                10_000, 8, 64, 10_000, 64L * 1024 * 1024,
                256L * 1024 * 1024, 192L * 1024 * 1024, 128L * 1024 * 1024,
                100,
                new com.iocextractor.application.dataframeimport.model.DelimitedInputLimits(
                        10_000, 64, 64 * 1024, 8 * 1024 * 1024));
    }

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(JdbcCanonicalImportObserver.Phase phase) {
            super(phase.name());
        }
    }

    private record ReceiptMutation(String column, Object value) {
    }
}
