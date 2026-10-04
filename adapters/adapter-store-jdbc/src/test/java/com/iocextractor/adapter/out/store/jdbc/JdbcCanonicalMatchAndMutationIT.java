package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.artifact.CanonicalMatchCardinality;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.CanonicalRecordMutationKind;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.FixedRecordValidityPolicy;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcCanonicalMatchAndMutationIT {

    private static final Instant START = Instant.parse("2026-08-23T00:00:00Z");

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private DataframeArtifactSchema schema;
    private ArtifactIdentityDefinition identity;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + tempDir.resolve("match.db"),
                        "low-memory", 2, 2));
        schema = new DataframeArtifactSchema("masks", List.of(
                new DataframeColumn("mask"), new DataframeColumn("source")));
        identity = new ArtifactIdentityDefinition(
                "masks",
                new CanonicalKeyDefinition("mask-row-v1", CanonicalKeyMode.COMPOSITE, List.of("mask")),
                List.of(
                        new CanonicalKeyDefinition("mask-v1", CanonicalKeyMode.COMPOSITE, List.of("mask")),
                        new CanonicalKeyDefinition("source-v1", CanonicalKeyMode.COMPOSITE, List.of("source"))),
                1);
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(List.of(schema));
        seedRows();
        new JdbcArtifactIdentityStore(
                dataSource, Clock.fixed(START, ZoneOffset.UTC)).ensure(identity);
    }

    @AfterEach
    void close() {
        dataSource.close();
    }

    @Test
    void empty_requests_and_requests_without_usable_keys_have_no_candidates() {
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));
        var asOf = EffectiveTime.at(START.plusMillis(500));

        assertThat(planner.plan("masks", asOf, List.of())).isEmpty();
        assertThat(planner.plan("masks", asOf, List.of(new CanonicalMatchRequest("empty", List.of()))))
                .singleElement().satisfies(plan -> {
                    assertThat(plan.requestId()).isEqualTo("empty");
                    assertThat(plan.cardinality()).isEqualTo(CanonicalMatchCardinality.ZERO);
                });
    }

    @Test
    void unions_multiple_keys_without_repeating_candidates_and_preserves_request_order() {
        var resolver = new CanonicalArtifactKeyResolver(List.of(identity));
        var keys = resolver.matchKeysOf("masks", row("one.example", "shared"));
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));

        var plans = planner.plan("masks", EffectiveTime.at(START.plusMillis(500)), List.of(
                new CanonicalMatchRequest("z-last-name-first", List.of(keys.get(0), keys.get(1), keys.get(0))),
                new CanonicalMatchRequest("a-first-name-last", List.of(keys.get(0)))));

        assertThat(plans).extracting(plan -> plan.requestId())
                .containsExactly("z-last-name-first", "a-first-name-last");
        assertThat(plans.get(0).candidates()).extracting(candidate -> candidate.canonicalRowId())
                .containsExactly(11L, 12L);
        assertThat(plans.get(1).candidates()).extracting(candidate -> candidate.canonicalRowId())
                .containsExactly(11L);
    }

    @Test
    void ignores_other_definitions_artifacts_and_stale_lifecycle_aliases() throws Exception {
        var resolver = new CanonicalArtifactKeyResolver(List.of(identity));
        var key = resolver.matchKeysOf("masks", row("one.example", null)).getFirst();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO canonical_match_definition(
                        artifact, definition_id, definition_fingerprint, identity_epoch, activated_at_ms)
                    VALUES ('other_artifact', 'mask-v1', 'test', 1, 0),
                           ('masks', 'other-definition', 'test', 1, 0)
                    """);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO canonical_match_alias
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (var values : List.of(
                    List.<Object>of("other_artifact", key.definitionId(), key.keyHash(), key.keyCanonical(), 102L, 12L),
                    List.<Object>of("masks", "other-definition", key.keyHash(), key.keyCanonical(), 102L, 12L),
                    List.<Object>of("masks", key.definitionId(), key.keyHash(), key.keyCanonical(), 999L, 12L))) {
                JdbcSql.bind(statement, values);
                statement.executeUpdate();
            }
        }
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));
        var plan = planner.plan("masks", EffectiveTime.at(START.plusMillis(500)),
                List.of(new CanonicalMatchRequest("isolated", List.of(key)))).getFirst();

        assertThat(plan.candidates()).extracting(candidate -> candidate.canonicalRowId()).containsExactly(11L);
        var otherDefinition = new CanonicalKeyMaterial("missing-definition", key.keyHash(), key.keyCanonical());
        assertThat(planner.plan("masks", EffectiveTime.at(START.plusMillis(500)),
                List.of(new CanonicalMatchRequest("missing", List.of(otherDefinition))))
                .getFirst().cardinality()).isEqualTo(CanonicalMatchCardinality.ZERO);
    }

    @Test
    void later_requests_see_alias_changes_in_the_same_transaction_and_rollback_restores_them() throws Exception {
        var engine = new JdbcCanonicalMutationEngine(dataSource, List.of(schema), List.of(identity));
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));
        var resolver = new CanonicalArtifactKeyResolver(List.of(identity));
        var asOf = EffectiveTime.at(START.plusMillis(300));
        var validity = new FixedRecordValidityPolicy(Duration.ofHours(1)).decide(asOf);
        var changed = request("changed", resolver, row("one.example", "new-alias"), false);
        var old = request("old", resolver, row("unused.example", "shared"), false);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            engine.mutateExisting(connection, schema, 11L, row("one.example", "new-alias"), false, asOf, validity);
            var plans = planner.plan(connection, schema, asOf, List.of(changed, old));
            assertThat(plans.get(0).candidates()).extracting(candidate -> candidate.canonicalRowId()).containsExactly(11L);
            assertThat(plans.get(1).candidates()).extracting(candidate -> candidate.canonicalRowId()).containsExactly(12L);
            connection.rollback();
        }
        assertThat(planner.plan("masks", asOf, List.of(changed)).getFirst().cardinality())
                .isEqualTo(CanonicalMatchCardinality.ZERO);
        assertThat(planner.plan("masks", asOf, List.of(old)).getFirst().candidates())
                .extracting(candidate -> candidate.canonicalRowId()).containsExactly(11L, 12L);
    }

    @Test
    void later_requests_see_new_aliases_before_commit_without_exposing_them_to_another_connection()
            throws Exception {
        var engine = new JdbcCanonicalMutationEngine(dataSource, List.of(schema), List.of(identity));
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));
        var resolver = new CanonicalArtifactKeyResolver(List.of(identity));
        var incoming = row("inserted.example", "insert-source");
        var asOf = EffectiveTime.at(START.plusMillis(300));
        var validity = new FixedRecordValidityPolicy(Duration.ofHours(1)).decide(asOf);
        var request = request("inserted", resolver, incoming, true);
        var recordKey = new com.iocextractor.application.artifact.ArtifactRowKey(
                resolver.recordKeyOf("masks", incoming).orElseThrow().keyHash());
        try (var writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            var inserted = engine.insertPlanned(writer, schema, "document", incoming, recordKey,
                    new com.iocextractor.application.artifact.lifecycle.LifecycleId(104), asOf, validity);
            assertThat(planner.plan(writer, schema, asOf, List.of(request)).getFirst().candidates())
                    .extracting(candidate -> candidate.canonicalRowId())
                    .containsExactly(inserted.canonicalRowId());
            assertThat(planner.plan("masks", asOf, List.of(request)).getFirst().cardinality())
                    .isEqualTo(CanonicalMatchCardinality.ZERO);
            writer.rollback();
        }
        assertThat(planner.plan("masks", asOf, List.of(request)).getFirst().cardinality())
                .isEqualTo(CanonicalMatchCardinality.ZERO);
    }

    @Test
    void plans_active_only_zero_one_and_multiple_at_strict_expiry_boundary() {
        var resolver = new CanonicalArtifactKeyResolver(List.of(identity));
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));
        EffectiveTime asOf = EffectiveTime.at(START.plusMillis(500));

        var plans = planner.plan("masks", asOf, List.of(
                request("zero", resolver, row("missing.example", "absent"), true),
                request("one", resolver, row("one.example", "shared"), true),
                request("multiple", resolver, row("unused.example", "shared"), false),
                request("expiry-equality", resolver, row("boundary.example", "expired"), true)));

        assertThat(plans).extracting(plan -> plan.cardinality()).containsExactly(
                CanonicalMatchCardinality.ZERO,
                CanonicalMatchCardinality.ONE,
                CanonicalMatchCardinality.MULTIPLE,
                CanonicalMatchCardinality.ZERO);
        assertThat(plans.get(2).candidates()).extracting(candidate -> candidate.canonicalRowId())
                .containsExactly(11L, 12L);
    }

    @Test
    void digest_hit_without_equal_canonical_material_is_not_a_match() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE canonical_match_alias
                    SET key_canonical = '[\"different.example\"]'
                    WHERE definition_id = 'mask-v1' AND canonical_row_id = 11
                    """);
        }
        var resolver = new CanonicalArtifactKeyResolver(List.of(identity));
        var planner = new JdbcCanonicalMatchPlanner(dataSource, List.of(schema));

        var plan = planner.plan("masks", EffectiveTime.at(START.plusMillis(500)), List.of(
                request("hash-collision", resolver, row("one.example", null), true))).getFirst();

        assertThat(plan.cardinality()).isEqualTo(CanonicalMatchCardinality.ZERO);
    }

    @Test
    void mutation_kernel_reports_update_clear_noop_and_ttl_confirmation() throws Exception {
        var engine = new JdbcCanonicalMutationEngine(dataSource, List.of(schema), List.of(identity));
        EffectiveTime asOf = EffectiveTime.at(START.plusMillis(300));
        var validity = new FixedRecordValidityPolicy(Duration.ofHours(1)).decide(asOf);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            var updated = engine.mutateExisting(
                    connection, schema, 11L, row("one.example", "changed"), false, asOf, validity);
            var cleared = engine.mutateExisting(
                    connection, schema, 11L, row("one.example", null), false, asOf, validity);
            var noOp = engine.mutateExisting(
                    connection, schema, 11L, row("one.example", null), false, asOf, validity);
            var renewed = engine.mutateExisting(
                    connection, schema, 11L, row("one.example", null), true, asOf, validity);
            connection.commit();

            assertThat(updated.kind()).isEqualTo(CanonicalRecordMutationKind.UPDATED);
            assertThat(updated.updatedFields()).containsExactly("source");
            assertThat(cleared.kind()).isEqualTo(CanonicalRecordMutationKind.CLEARED);
            assertThat(cleared.clearedFields()).containsExactly("source");
            assertThat(noOp.kind()).isEqualTo(CanonicalRecordMutationKind.NO_OP);
            assertThat(renewed.kind()).isEqualTo(CanonicalRecordMutationKind.TTL_CONFIRMED);
            assertThat(queryLong(connection, """
                    SELECT COUNT(*) FROM canonical_match_alias
                    WHERE canonical_row_id = 11
                    """)).isOne();
        }
    }

    @Test
    void mutation_kernel_rejects_clearing_every_record_key_value() throws Exception {
        var engine = new JdbcCanonicalMutationEngine(dataSource, List.of(schema), List.of(identity));
        EffectiveTime asOf = EffectiveTime.at(START.plusMillis(300));
        var validity = new FixedRecordValidityPolicy(Duration.ofHours(1)).decide(asOf);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            assertThatThrownBy(() -> engine.mutateExisting(
                    connection, schema, 11L, row(null, "shared"), false, asOf, validity))
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessageContaining("record key must contain at least one value");

            connection.rollback();
        }
    }

    private CanonicalMatchRequest request(String id,
                                          CanonicalArtifactKeyResolver resolver,
                                          ArtifactRow row,
                                          boolean useMask) {
        var keys = resolver.matchKeysOf("masks", row);
        return new CanonicalMatchRequest(id, List.of(keys.get(useMask ? 0 : 1)));
    }

    private ArtifactRow row(String mask, String source) {
        var values = new LinkedHashMap<String, String>();
        values.put("mask", mask);
        values.put("source", source);
        return ArtifactRow.ordered(values);
    }

    private long queryLong(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void seedRows() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            long base = START.toEpochMilli();
            statement.execute("""
                    INSERT INTO masks(
                        id, mask, source, row_key, _created_at, _first_source_key,
                        _lifecycle_id, _first_confirmed_at_epoch_ms,
                        _last_confirmed_at_epoch_ms, _valid_until_epoch_ms)
                    VALUES (11, 'one.example', 'shared', 'old-11', '2026-08-23T00:00:00Z',
                            'feed', 101, %d, %d, %d),
                           (12, 'two.example', 'shared', 'old-12', '2026-08-23T00:00:00Z',
                            'feed', 102, %d, %d, %d),
                           (13, 'boundary.example', 'expired', 'old-13', '2026-08-23T00:00:00Z',
                            'feed', 103, %d, %d, %d)
                    """.formatted(
                    base + 100, base + 200, base + 1000,
                    base + 100, base + 200, base + 1000,
                    base + 100, base + 200, base + 500));
        }
    }
}
