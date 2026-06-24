package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.ArtifactIdentityDefinition;
import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.aggregation.CanonicalArtifactIdentityResolver;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

        repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask", "source"), List.of(
                row("id", "42", "mask", "example.com", "source", "first"),
                row("id", "43", "mask", "example.com", "source", "duplicate"),
                row("id", "44", "mask", "example.org", "source", "second"))));

        CanonicalArtifact loaded = repository.load("masks");
        assertThat(loaded.header()).containsExactly("id", "mask", "source");
        assertThat(loaded.rows())
                .extracting(row -> row.value("id") + ":" + row.value("mask") + ":" + row.value("source"))
                .containsExactly("42:example.com:first", "44:example.org:second");
    }

    @Test
    void lookup_repository_uses_dataframe_tables_for_contains_and_max_id() {
        var schemas = List.of(
                schema("masks", "id", "mask"),
                schema("ip_list", "id", "ip"),
                schema("hashes", "id", "hash_md5", "hash_sha1", "hash_sha256"));
        var identities = List.of(
                new ArtifactIdentityDefinition("masks", List.of("mask"), false, 1),
                new ArtifactIdentityDefinition("ip_list", List.of("ip"), false, 1),
                new ArtifactIdentityDefinition("hashes", List.of("hash_md5", "hash_sha1", "hash_sha256"), true, 1));
        var canonical = canonicalRepository(schemas, identities);
        canonical.write("masks", new CanonicalArtifact("masks", List.of("id", "mask"),
                List.of(row("id", "10", "mask", "example.com"))));
        canonical.write("ip_list", new CanonicalArtifact("ip_list", List.of("id", "ip"),
                List.of(row("id", "692", "ip", "1.2.3.4"))));
        canonical.write("hashes", new CanonicalArtifact("hashes", List.of("id", "hash_md5", "hash_sha1", "hash_sha256"),
                List.of(row("id", "100", "hash_md5", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "hash_sha1", null, "hash_sha256", null))));

        var lookup = new JdbcLookupRepository(dataSource);

        assertThat(lookup.contains(new Indicator("example.com", IndicatorType.DOMAIN, source()))).isTrue();
        assertThat(lookup.contains(new Indicator("1.2.3.4", IndicatorType.IPV4, source()))).isTrue();
        assertThat(lookup.contains(new Indicator("1.2.3.4:8080/payload.exe", IndicatorType.IPV4, source()))).isFalse();
        assertThat(lookup.contains(new Indicator("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", IndicatorType.MD5, source()))).isTrue();
        assertThat(lookup.contains(new Indicator("example.org", IndicatorType.DOMAIN, source()))).isFalse();
        assertThat(lookup.maxId("masks")).isEqualTo(10L);
        assertThat(lookup.maxId("ip_list")).isEqualTo(692L);
        assertThat(lookup.maxId("hashes")).isEqualTo(100L);
        assertThat(lookup.maxId()).isEqualTo(692L);
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

    private SourceContext source() {
        return new SourceContext(null, null);
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("dataframe", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
