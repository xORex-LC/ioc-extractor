package com.iocextractor;

import com.iocextractor.bootstrap.JdbcStorageHealthIndicator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-mode smoke test for the opt-in dataframe schema foundation. It must
 * not imply switching the ingestion ledger to JDBC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ioc.runtime.mode=daemon",
        "ioc.lookup.path=target/test-dataframe-storage/no-lookup.csv",
        "ioc.ingestion.dirs.inbox=target/test-dataframe-storage/inbox",
        "ioc.ingestion.dirs.processing=target/test-dataframe-storage/processing",
        "ioc.ingestion.dirs.done=target/test-dataframe-storage/done",
        "ioc.ingestion.dirs.failed=target/test-dataframe-storage/failed",
        "ioc.ingestion.ledger.path=target/test-dataframe-storage/ledger",
        "ioc.ingestion.output.partitions-dir=target/test-dataframe-storage/partitions",
        "ioc.storage.dataframe.type=jdbc",
        "ioc.storage.dataframe.url=jdbc:sqlite:target/test-dataframe-storage/ioc-dataframe.db",
        "spring.main.banner-mode=off"
})
class DataframeStorageDaemonRuntimeModeTest {

    /**
     * Reconciliation runs against whatever schema the dataframe DB already has,
     * so the test must start from a pristine file; otherwise a left-over table
     * from an earlier run (with different column types) is flagged as drift.
     * Runs before the application context is refreshed.
     */
    @DynamicPropertySource
    static void pristineDataframeDatabase(DynamicPropertyRegistry registry) {
        Path dir = Path.of("target/test-dataframe-storage");
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
    ApplicationContext context;

    @Autowired
    @Qualifier("dataframeStorageDataSource")
    HikariDataSource dataframeStorageDataSource;

    @Test
    void daemon_context_can_opt_into_dataframe_schema_foundation() throws Exception {
        assertThat(context.getBeansOfType(HikariDataSource.class))
                .containsOnlyKeys("dataframeStorageDataSource");
        assertThat(context.getBeansOfType(JdbcStorageHealthIndicator.class)).isEmpty();

        assertThat(userVersion()).isEqualTo(2);
        assertThat(tableExists("masks")).isTrue();
        assertThat(tableExists("masks_sources")).isTrue();
        assertThat(viewExists("masks_last_seen")).isTrue();
        assertThat(tableExists("hashes")).isTrue();
    }

    private int userVersion() throws Exception {
        try (Connection connection = dataframeStorageDataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA user_version")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private boolean tableExists(String name) throws Exception {
        return objectExists("table", name);
    }

    private boolean viewExists(String name) throws Exception {
        return objectExists("view", name);
    }

    private boolean objectExists(String type, String name) throws Exception {
        try (Connection connection = dataframeStorageDataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT 1
                     FROM sqlite_master
                     WHERE type = ? AND name = ?
                     """)) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
