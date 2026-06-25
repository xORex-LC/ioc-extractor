package com.iocextractor;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.ingest.IngestionStatus;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end daemon ingest over the post-collapse direct-to-canonical path: a
 * source file is processed straight into JDBC canonical storage (with source
 * provenance) and a CSV projection, with no partition staging or aggregation
 * pass. Drives the use case directly for determinism (the file poller wiring is
 * covered by the runtime-mode context tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ioc.runtime.mode=daemon",
        "ioc.lookup.path=target/daemon-e2e/none.csv",
        "ioc.ingestion.dirs.inbox=target/daemon-e2e/inbox",
        "ioc.ingestion.dirs.processing=target/daemon-e2e/processing",
        "ioc.ingestion.dirs.done=target/daemon-e2e/done",
        "ioc.ingestion.dirs.failed=target/daemon-e2e/failed",
        "ioc.ingestion.ledger.path=target/daemon-e2e/ledger",
        "ioc.storage.service.url=jdbc:sqlite:target/daemon-e2e/ioc-service.db",
        "spring.main.banner-mode=off"
})
@ActiveProfiles("daemon-e2e")
class DaemonIngestE2ETest {

    @DynamicPropertySource
    static void pristineWorkdir(DynamicPropertyRegistry registry) {
        Path dir = Path.of("target/daemon-e2e");
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

    @Autowired
    IngestSourceUseCase ingestSourceUseCase;

    @Autowired
    @Qualifier("dataframeStorageDataSource")
    HikariDataSource dataframeStorageDataSource;

    @Test
    void ingests_source_directly_into_canonical_storage_and_projection() throws Exception {
        // a source staged outside the watched inbox, so the poller cannot race the manual ingest
        Path staging = Files.createDirectories(Path.of("target/daemon-e2e/staging"));
        Path source = staging.resolve("e2e-source.html");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("golden/source.html")) {
            Files.write(source, in.readAllBytes());
        }

        IngestSourceResult result = ingestSourceUseCase.ingest(
                new IngestSourceCommand(source, new SourceKey("e2e-source"), Instant.parse("2026-06-25T00:00:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(result.duplicate()).isFalse();

        // canonical truth in the dataframe DB
        assertThat(count("SELECT COUNT(*) FROM masks")).isPositive();
        // provenance recorded against the ingest source key
        assertThat(count("SELECT COUNT(*) FROM masks_sources WHERE source_key = 'e2e-source'")).isPositive();
        // CSV projection refreshed from canonical truth
        Path projection = Path.of("target/daemon-e2e/masks_list_generated.csv");
        assertThat(Files.exists(projection)).isTrue();
        assertThat(Files.readAllLines(projection).size()).isGreaterThan(1); // header + at least one row
    }

    private long count(String sql) throws Exception {
        try (Connection connection = dataframeStorageDataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }
}
