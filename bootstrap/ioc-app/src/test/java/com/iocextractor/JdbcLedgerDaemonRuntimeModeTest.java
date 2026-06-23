package com.iocextractor;

import com.iocextractor.adapter.in.cli.CliRunner;
import com.iocextractor.adapter.out.store.jdbc.JdbcIngestionLedger;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.dsl.IntegrationFlow;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-mode smoke test for daemon ingestion backed by the service JDBC
 * ledger. The datasource must be explicitly selected; oneshot and file-ledger
 * daemon contexts stay storage-free.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ioc.runtime.mode=daemon",
        "ioc.lookup.path=target/test-daemon-jdbc/no-lookup.csv",
        "ioc.ingestion.ledger.type=jdbc",
        "ioc.ingestion.ledger.path=target/test-daemon-jdbc/legacy-ledger",
        "ioc.storage.service.url=jdbc:sqlite:target/test-daemon-jdbc/ioc-service.db",
        "ioc.ingestion.dirs.inbox=target/test-daemon-jdbc/inbox",
        "ioc.ingestion.dirs.processing=target/test-daemon-jdbc/processing",
        "ioc.ingestion.dirs.done=target/test-daemon-jdbc/done",
        "ioc.ingestion.dirs.failed=target/test-daemon-jdbc/failed",
        "ioc.ingestion.output.partitions-dir=target/test-daemon-jdbc/partitions",
        "spring.main.banner-mode=off"
})
class JdbcLedgerDaemonRuntimeModeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    IngestSourceUseCase ingestSourceUseCase;

    @Autowired
    HikariDataSource serviceStorageDataSource;

    @Test
    void daemon_context_can_select_jdbc_ingestion_ledger() throws SQLException {
        assertThat(ingestSourceUseCase).isNotNull();
        assertThat(context.getBeansOfType(CliRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(IntegrationFlow.class))
                .containsKey("iocIngestionFlow");
        assertThat(context.getBean(IngestionLedger.class)).isInstanceOf(JdbcIngestionLedger.class);
        assertThat(context.getBeansOfType(HikariDataSource.class))
                .containsOnlyKeys("serviceStorageDataSource");

        try (var connection = serviceStorageDataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA user_version")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }
}
