package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.export.ArtifactRevision;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcArtifactRepositoriesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-24T00:00:00Z"), ZoneOffset.UTC);

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
    void canonical_repository_inserts_keep_first_rows_and_preserves_explicit_ids() {
        var schema = schema("masks", "id", "mask", "source");
        var repository = canonicalRepository(List.of(schema), List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));

        var result = repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"), List.of(
                row("id", "42", "mask", "example.com", "source", "first"),
                row("id", "43", "mask", "example.com", "source", "duplicate"),
                row("id", "44", "mask", "example.org", "source", "second"))));

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.revision()).isEqualTo(1);
        CanonicalArtifact loaded = repository.load("masks");
        assertThat(loaded.header()).containsExactly("id", "mask", "source");
        assertThat(loaded.rows())
                .extracting(row -> row.value("id") + ":" + row.value("mask") + ":" + row.value("source"))
                .containsExactly("42:example.com:first", "44:example.org:second");
        assertThat(sourceRows("masks"))
                .containsExactlyInAnyOrder(
                        "42:first:1",
                        "42:duplicate:1",
                        "44:second:1");
    }

    @Test
    void duplicate_only_write_does_not_advance_revision_and_reader_preserves_requested_order() {
        var schemas = List.of(schema("masks", "id", "mask"), schema("hashes", "id", "hash_md5"));
        var repository = canonicalRepository(schemas, List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1),
                new ArtifactIdentityDefinition("hashes", List.of("hash_md5"), false, 1)));
        var artifact = new CanonicalArtifact("masks", List.of("id", "mask"),
                List.of(row("id", "1", "mask", "example.com")));

        var first = repository.write("masks", artifact);
        var duplicate = repository.write("masks", artifact);
        List<ArtifactRevision> revisions = new JdbcArtifactRevisionReader(dataSource)
                .read(List.of("hashes", "masks"));

        assertThat(first).extracting("inserted", "revision").containsExactly(1, 1L);
        assertThat(duplicate).extracting("inserted", "revision").containsExactly(0, 1L);
        assertThat(revisions).containsExactly(
                new ArtifactRevision("hashes", 0, null),
                new ArtifactRevision("masks", 1, CLOCK.instant()));
    }

    @Test
    void duplicate_write_updates_provenance_without_advancing_revision() {
        var schema = schema("masks", "id", "mask", "source");
        var repository = canonicalRepository(List.of(schema), List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));

        var first = repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"),
                List.of(row("id", "1", "mask", "example.com", "source", "first"))));
        var duplicate = repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"),
                List.of(row("id", "2", "mask", "example.com", "source", "second"))));

        assertThat(first).extracting("inserted", "revision").containsExactly(1, 1L);
        assertThat(duplicate).extracting("inserted", "revision").containsExactly(0, 1L);
        assertThat(repository.load("masks").rows())
                .extracting(row -> row.value("id") + ":" + row.value("mask") + ":" + row.value("source"))
                .containsExactly("1:example.com:first");
        assertThat(sourceRows("masks"))
                .containsExactlyInAnyOrder(
                        "1:first:1",
                        "1:second:1");
    }

    @Test
    void duplicate_write_from_same_source_increments_occurrences_without_public_duplicate() {
        var schema = schema("masks", "id", "mask", "source");
        var repository = canonicalRepository(List.of(schema), List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));

        var first = repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"),
                List.of(row("id", "1", "mask", "example.com", "source", "same-source"))));
        var duplicate = repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"),
                List.of(row("id", "2", "mask", "example.com", "source", "same-source"))));

        assertThat(first).extracting("inserted", "revision").containsExactly(1, 1L);
        assertThat(duplicate).extracting("inserted", "revision").containsExactly(0, 1L);
        assertThat(repository.load("masks").rows())
                .extracting(row -> row.value("id") + ":" + row.value("mask") + ":" + row.value("source"))
                .containsExactly("1:example.com:same-source");
        assertThat(sourceRows("masks")).containsExactly("1:same-source:2");
    }

    @Test
    void failed_batch_rolls_back_public_rows_provenance_and_revision() {
        var schema = schema("masks", "id", "mask", "source");
        var repository = canonicalRepository(List.of(schema), List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));
        var conflictingIds = new CanonicalArtifact("masks", List.of("id", "mask", "source"), List.of(
                row("id", "1", "mask", "example.com", "source", "first"),
                row("id", "1", "mask", "example.org", "source", "second")));

        assertThatThrownBy(() -> repository.write("masks", conflictingIds))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("Failed to write JDBC artifact");

        assertThat(repository.load("masks").rows()).isEmpty();
        assertThat(sourceRows("masks")).isEmpty();
        assertThat(new JdbcArtifactRevisionReader(dataSource).read(List.of("masks")))
                .containsExactly(new ArtifactRevision("masks", 0, null));
    }

    @Test
    void artifact_id_baseline_uses_configured_public_id_columns_without_hardcoded_artifacts() {
        var schemas = List.of(
                schema("masks", "id", "mask"),
                schema("address_blacklist", "forbidden_url"),
                schema("custom_list", "id", "value"));
        var repository = canonicalRepository(schemas, List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1),
                new ArtifactIdentityDefinition("address_blacklist", List.of("forbidden_url"), false, 1),
                new ArtifactIdentityDefinition("custom_list", List.of("value"), false, 1)));
        repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask"),
                List.of(row("id", "10", "mask", "example.com"))));
        repository.write("address_blacklist", new CanonicalArtifact("address_blacklist", List.of("forbidden_url"),
                List.of(row("forbidden_url", "https://example.com/payload.exe"))));
        repository.write("custom_list", new CanonicalArtifact("custom_list", List.of("id", "value"),
                List.of(row("id", "77", "value", "custom-value"))));

        var baseline = new JdbcArtifactIdBaseline(dataSource, schemas);

        assertThat(baseline.maxId("masks")).isEqualTo(10L);
        assertThat(baseline.maxId("custom_list")).isEqualTo(77L);
        assertThat(baseline.maxId("address_blacklist")).isZero();
    }

    @Test
    void artifact_id_baseline_rejects_sql_shaped_artifact_name_before_query_generation() {
        var schemas = List.of(schema("masks", "id", "mask"));
        dataSource = dataSource("baseline-trust-boundary.db");
        var baseline = new JdbcArtifactIdBaseline(dataSource, schemas);

        assertThatThrownBy(() -> baseline.maxId("masks\"; DROP TABLE artifact_revision;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid dataframe artifact name");
    }

    @Test
    void repository_binds_sql_shaped_runtime_values_as_data() {
        var schema = schema("masks", "id", "mask", "source");
        var repository = canonicalRepository(List.of(schema), List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1)));
        String mask = "example.com'); DROP TABLE artifact_revision;--";
        String source = "source'); DROP TABLE masks;--";

        var result = repository.write("masks", new CanonicalArtifact(
                "masks",
                List.of("id", "mask", "source"),
                List.of(row("id", "1", "mask", mask, "source", source))));

        assertThat(result).extracting("inserted", "revision").containsExactly(1, 1L);
        assertThat(repository.load("masks").rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.value("mask")).isEqualTo(mask);
                    assertThat(row.value("source")).isEqualTo(source);
                });
        assertThat(new JdbcArtifactRevisionReader(dataSource).read(List.of("masks")))
                .containsExactly(new ArtifactRevision("masks", 1, CLOCK.instant()));
        assertThat(sourceRows("masks")).containsExactly("1:" + source + ":1");
    }

    private JdbcCanonicalArtifactRepository canonicalRepository(List<DataframeArtifactSchema> schemas,
                                                                List<ArtifactIdentityDefinition> identities) {
        dataSource = dataSource("artifacts-" + System.nanoTime() + ".db");
        new SqliteUserVersionSchemaMigrator(dataSource, DataframeFormatMigrations.sqlite()).migrate();
        new DataframeSchemaReconciler(dataSource).reconcile(schemas);
        return new JdbcCanonicalArtifactRepository(
                dataSource,
                schemas,
                new CanonicalArtifactIdentityResolver(identities),
                CLOCK);
    }

    private DataframeArtifactSchema schema(String name, String... columns) {
        return new DataframeArtifactSchema(name, java.util.Arrays.stream(columns)
                .map(DataframeColumn::new)
                .toList());
    }

    private ArtifactRow row(String... pairs) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return ArtifactRow.ordered(values);
    }

    private List<String> sourceRows(String artifactName) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("""
                     SELECT row_id, source_key, occurrences
                     FROM %s_sources
                     ORDER BY row_id, source_key
                     """.formatted(artifactName))) {
            var rows = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                rows.add(resultSet.getLong("row_id") + ":"
                        + resultSet.getString("source_key") + ":"
                        + resultSet.getLong("occurrences"));
            }
            return rows;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
