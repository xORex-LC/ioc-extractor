package com.iocextractor;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
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
class GoldenPipelineTest {

    private static final List<String> ARTIFACTS = List.of("masks", "ip_list", "address_blacklist", "hashes");
    private static final Map<String, Path> PROJECTIONS = Map.of(
            "masks", Path.of("target/golden/masks.csv"),
            "ip_list", Path.of("target/golden/ip-list.csv"),
            "address_blacklist", Path.of("target/golden/address-blacklist.csv"),
            "hashes", Path.of("target/golden/hashes.csv"));

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

        assertThat(normalize(Files.readString(Path.of("target/golden/masks.csv"))))
                .isEqualTo(goldenResource("golden/expected-masks.csv"));
        assertThat(normalize(Files.readString(Path.of("target/golden/ip-list.csv"))))
                .isEqualTo(goldenResource("golden/expected-ip-list.csv"));
        assertThat(normalize(Files.readString(Path.of("target/golden/address-blacklist.csv"))))
                .isEqualTo(goldenResource("golden/expected-address-blacklist.csv"));
        assertThat(normalize(Files.readString(Path.of("target/golden/hashes.csv"))))
                .isEqualTo(goldenResource("golden/expected-hashes.csv"));

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

    private String goldenResource(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Line-ending and trailing-whitespace insensitive comparison. */
    private String normalize(String content) {
        return content.replace("\r\n", "\n").strip();
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
