package com.iocextractor;

import com.iocextractor.application.tck.junit.EndToEndTest;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.consumer.ReferenceArtifactConsumer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end golden regression. Runs the real pipeline over a synthetic fixture
 * covering every classification bucket (variants 1–4, bare IP, onion,
 * telegram, hashes) and compares the generated artifacts to committed golden
 * files.
 *
 * <p>Isolated: the fixture is a test resource, output is redirected to {@code target/}
 * (not {@code dataframe/}), and the lookup points at a non-existent file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.main.banner-mode=off")
@ActiveProfiles("golden")
@EndToEndTest
class GoldenPipelineIT {

    private static final List<String> ARTIFACTS = List.of(
            "masks", "ip_list", "address_blacklist", "hashes", "ioc_aggregate");
    private static final Map<String, Path> PROJECTIONS = Map.of(
            "masks", Path.of("target/golden/masks.csv"),
            "ip_list", Path.of("target/golden/ip-list.csv"),
            "address_blacklist", Path.of("target/golden/address-blacklist.csv"),
            "hashes", Path.of("target/golden/hashes.csv"),
            "ioc_aggregate", Path.of("target/golden/IOC_aggregate.csv"));
    private static final Map<String, String> GOLDEN_RESOURCES = Map.of(
            "masks", "golden/expected-masks.csv",
            "ip_list", "golden/expected-ip-list.csv",
            "address_blacklist", "golden/expected-address-blacklist.csv",
            "hashes", "golden/expected-hashes.csv",
            "ioc_aggregate", "golden/expected-ioc-aggregate.csv");
    private static final Map<String, List<String>> HEADERS = Map.of(
            "masks", List.of(
                    "id", "mask", "url_match", "host_match", "score", "time_last_seen",
                    "time_first_seen", "threat_type", "source", "description"),
            "ip_list", List.of(
                    "id", "ip", "score", "time_last_seen", "time_first_seen",
                    "threat_type", "source", "description"),
            "address_blacklist", List.of("forbidden_url", "forbidden_ip"),
            "hashes", List.of(
                    "id", "hash_md5", "hash_sha256", "hash_sha1", "score",
                    "time_last_seen", "time_first_seen", "threat_type", "source", "description"),
            "ioc_aggregate", List.of("name", "ip_address", "url_match", "host_match", "hash"));

    private final ReferenceArtifactConsumer consumer = new ReferenceArtifactConsumer();

    @Autowired
    ExtractIocsUseCase useCase;

    @Autowired
    @Qualifier("dataframeStorageDataSource")
    HikariDataSource dataframeStorageDataSource;

    @DynamicPropertySource
    static void pristineOutput(DynamicPropertyRegistry registry) {
        Path dir = Path.of("target/golden");
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void pipeline_output_matches_golden_and_repeated_extract_updates_only_provenance() throws Exception {
        useCase.extract(new ExtractionCommand(
                "golden-first", Path.of("src/test/resources/golden/source.html"), false));

        for (String artifact : ARTIFACTS) {
            Path projection = PROJECTIONS.get(artifact);
            assertThat(Files.readAllBytes(projection))
                    .as("exact public CSV bytes for %s", artifact)
                    .containsExactly(goldenCsvBytes(GOLDEN_RESOURCES.get(artifact)));
            assertThat(consumer.readCsv(projection, HEADERS.get(artifact)).rows())
                    .as("reference consumer rows for %s", artifact)
                    .isNotEmpty();
        }

        Map<String, String> firstProjection = projectionContent();
        Map<String, Long> firstRows = publicRowCounts();
        Map<String, Long> firstRevisions = revisions();
        Map<String, Map<String, Long>> firstOccurrences = sourceOccurrences();

        useCase.extract(new ExtractionCommand(
                "golden-second", Path.of("src/test/resources/golden/source.html"), false));

        assertThat(projectionContent()).isEqualTo(firstProjection);
        assertThat(publicRowCounts()).isEqualTo(firstRows);
        assertThat(revisions()).isEqualTo(firstRevisions);
        assertOccurrencesIncrementedByOne(firstOccurrences, sourceOccurrences());
    }

    @Test
    @org.junit.jupiter.api.Timeout(120)
    void legacyMappedCollisionsPreserveReservationAndProvenanceInBothWritePaths() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "legacy-accounting-");
        Path source = root.resolve("input.html");
        Files.writeString(source, "<html><head><meta charset=\"utf-8\"></head><body><p>БИБ-first</p>"
                + "<p>same.example same.example</p><p>БИБ-second</p><p>other.example</p></body></html>");
        for (boolean lifecycle : List.of(false, true)) {
            for (boolean deduplicate : List.of(false, true)) {
                Path run = Files.createDirectories(root.resolve(lifecycle + "-" + deduplicate));
                try (var context = org.springframework.boot.SpringApplication.run(IocExtractorApplication.class,
                        "--spring.profiles.active=golden", "--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off", "--golden.output-dir=" + run.toAbsolutePath(),
                        "--ioc.storage.service.url=jdbc:sqlite:" + run.resolve("service.db").toAbsolutePath(),
                        "--ioc.pipeline.deduplicate=" + deduplicate,
                        "--ioc.lifecycle.validity.mode=" + (lifecycle ? "fixed" : "disabled"),
                        "--ioc.lifecycle.validity.fixed-ttl=12h", "--ioc.lifecycle.validity.existing-records=expire",
                        "--ioc.artifact-identity.artifacts[0].name=masks",
                        "--ioc.artifact-identity.artifacts[0].key-columns[0]=mask",
                        "--ioc.artifact-identity.artifacts[0].key-mode=composite",
                        "--ioc.artifact-identity.artifacts[0].record-key=mask-row-v1",
                        "--ioc.artifact-identity.artifacts[0].match-keys[0].name=mask-v1",
                        "--ioc.artifact-identity.artifacts[0].match-keys[0].key-columns[0]=mask",
                        "--ioc.sink.artifacts[0].name=masks",
                        "--ioc.sink.artifacts[0].enabled=true",
                        "--ioc.sink.artifacts[0].path=" + run.resolve("masks.csv").toAbsolutePath(),
                        "--ioc.sink.artifacts[0].accepts[0]=DOMAIN",
                        "--ioc.sink.artifacts[0].id.strategy=ascending",
                        "--ioc.sink.artifacts[0].id.start=1",
                        "--ioc.sink.artifacts[0].columns[0].name=id",
                        "--ioc.sink.artifacts[0].columns[0].from=id",
                        "--ioc.sink.artifacts[0].columns[1].name=mask",
                        "--ioc.sink.artifacts[0].columns[1].from=const",
                        "--ioc.sink.artifacts[0].columns[1].value=same-key",
                        "--ioc.sink.artifacts[0].columns[2].name=source",
                        "--ioc.sink.artifacts[0].columns[2].from=source.label")) {
                    var useCase = context.getBean(ExtractIocsUseCase.class);
                    var command = new ExtractionCommand("legacy-accounting", source, false);
                    int expected = deduplicate ? 2 : 3;
                    if (lifecycle) {
                        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.extract(command))
                                .isInstanceOf(com.iocextractor.diagnostics.DiagnosticException.class)
                                .hasMessageContaining("Canonical confirmation contains duplicate row key");
                    } else {
                        var result = useCase.extract(command);
                        assertThat(result.extracted()).isEqualTo(3);
                        assertThat(result.retained()).isEqualTo(expected);
                    }
                    var data = context.getBean("dataframeStorageDataSource", HikariDataSource.class);
                    try (var connection = data.getConnection(); var statement = connection.createStatement()) {
                        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM masks")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isEqualTo(lifecycle ? 0 : 1);
                        }
                        try (var rows = statement.executeQuery("SELECT SUM(occurrences) FROM masks_sources")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isEqualTo(lifecycle ? 0 : expected);
                        }
                        try (var rows = statement.executeQuery(
                                "SELECT next_value FROM artifact_id_allocator WHERE artifact='masks'")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isOne();
                        }
                    }
                }
            }
        }
    }

    private byte[] goldenCsvBytes(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden resource: " + resource);
            }
            String logicalFixture = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            if (!logicalFixture.endsWith("\n")) {
                logicalFixture += "\n";
            }
            return logicalFixture.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> projectionContent() throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        for (String artifact : ARTIFACTS) {
            content.put(artifact, Files.readString(PROJECTIONS.get(artifact)));
        }
        return content;
    }

    private Map<String, Long> publicRowCounts() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = dataframeStorageDataSource.getConnection()) {
            for (String artifact : ARTIFACTS) {
                counts.put(artifact, scalar(connection, "SELECT COUNT(*) FROM \"" + artifact + "\""));
            }
        }
        return counts;
    }

    private Map<String, Long> revisions() throws Exception {
        Map<String, Long> revisions = new LinkedHashMap<>();
        try (Connection connection = dataframeStorageDataSource.getConnection()) {
            for (String artifact : ARTIFACTS) {
                revisions.put(artifact, scalar(connection,
                        "SELECT revision FROM artifact_revision WHERE artifact = ?", artifact));
            }
        }
        return revisions;
    }

    private Map<String, Map<String, Long>> sourceOccurrences() throws Exception {
        Map<String, Map<String, Long>> occurrences = new LinkedHashMap<>();
        try (Connection connection = dataframeStorageDataSource.getConnection()) {
            for (String artifact : ARTIFACTS) {
                String sql = """
                        SELECT row_id, source_key, occurrences
                        FROM "%s_sources"
                        ORDER BY row_id, source_key
                        """.formatted(artifact);
                Map<String, Long> artifactOccurrences = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        artifactOccurrences.put(
                                resultSet.getLong("row_id") + ":" + resultSet.getString("source_key"),
                                resultSet.getLong("occurrences"));
                    }
                }
                occurrences.put(artifact, artifactOccurrences);
            }
        }
        return occurrences;
    }

    private long scalar(Connection connection, String sql, String... params) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private void assertOccurrencesIncrementedByOne(Map<String, Map<String, Long>> before,
                                                   Map<String, Map<String, Long>> after) {
        assertThat(after.keySet()).containsExactlyElementsOf(before.keySet());
        for (String artifact : before.keySet()) {
            Map<String, Long> beforeArtifact = before.get(artifact);
            Map<String, Long> afterArtifact = after.get(artifact);
            assertThat(afterArtifact.keySet()).containsExactlyElementsOf(beforeArtifact.keySet());
            for (Map.Entry<String, Long> entry : beforeArtifact.entrySet()) {
                assertThat(afterArtifact.get(entry.getKey()))
                        .as("occurrences for %s %s", artifact, entry.getKey())
                        .isEqualTo(entry.getValue() + 1);
            }
        }
    }
}
