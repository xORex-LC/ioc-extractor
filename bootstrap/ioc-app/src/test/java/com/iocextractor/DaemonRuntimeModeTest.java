package com.iocextractor;

import com.iocextractor.adapter.in.cli.CliRunner;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.dsl.IntegrationFlow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-mode smoke test: daemon wiring must start without activating the CLI
 * runner that exits oneshot processes.
 */
@SpringBootTest(properties = {
        "ioc.runtime.mode=daemon",
        "ioc.lookup.path=target/test-daemon/no-lookup.csv",
        "ioc.ingestion.dirs.inbox=target/test-daemon/inbox",
        "ioc.ingestion.dirs.processing=target/test-daemon/processing",
        "ioc.ingestion.dirs.done=target/test-daemon/done",
        "ioc.ingestion.dirs.failed=target/test-daemon/failed",
        "ioc.ingestion.ledger.path=target/test-daemon/ledger",
        "ioc.ingestion.output.partitions-dir=target/test-daemon/partitions",
        "spring.main.banner-mode=off"
})
class DaemonRuntimeModeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    IngestSourceUseCase ingestSourceUseCase;

    @Test
    void daemon_context_has_ingest_flow_without_cli_runner() {
        assertThat(ingestSourceUseCase).isNotNull();
        assertThat(context.getBeansOfType(CliRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(IntegrationFlow.class))
                .containsKey("iocIngestionFlow");
    }
}
