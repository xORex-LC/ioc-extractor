package com.iocextractor;

import com.iocextractor.adapter.in.cli.CliRunner;
import com.iocextractor.adapter.in.ingest.FileIngestionLedger;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.export.StandaloneSliceRetentionGuard;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.bootstrap.DaemonFetchScheduler;
import com.iocextractor.bootstrap.DaemonPublishScheduler;
import com.iocextractor.bootstrap.IngestionLedgerHealthIndicator;
import com.iocextractor.bootstrap.IngestionLifecycleHealthIndicator;
import com.iocextractor.bootstrap.JdbcStorageHealthIndicator;
import com.iocextractor.bootstrap.DaemonExportScheduler;
import com.iocextractor.bootstrap.DaemonSliceRetentionScheduler;
import com.iocextractor.bootstrap.ExportHealthIndicator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.Lifecycle;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-mode smoke test: daemon wiring must start without activating the CLI
 * runner that exits oneshot processes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ioc.runtime.mode=daemon",
        "ioc.ingestion.dirs.inbox=target/test-daemon/inbox",
        "ioc.ingestion.dirs.processing=target/test-daemon/processing",
        "ioc.ingestion.dirs.done=target/test-daemon/done",
        "ioc.ingestion.dirs.failed=target/test-daemon/failed",
        "ioc.ingestion.ledger.path=target/test-daemon/ledger",
        "ioc.storage.service.url=jdbc:sqlite:target/test-daemon/ioc-service.db",
        "ioc.storage.dataframe.url=jdbc:sqlite:target/test-daemon/ioc-dataframe.db",
        "ioc.lifecycle.validity.mode=fixed",
        "ioc.dataframe-import.enabled=true",
        "ioc.dataframe-import.sources[0].id=runtime-local",
        "ioc.dataframe-import.sources[0].transport=local",
        "ioc.dataframe-import.sources[0].location=target/test-daemon/import/inbox",
        "ioc.dataframe-import.sources[0].contracts[0]=ip-list-v1",
        "ioc.dataframe-import.sources[0].authority=runtime-standard",
        "ioc.dataframe-import.authority-profiles[0].id=runtime-standard",
        "ioc.dataframe-import.authority-profiles[0].artifacts[0]=ip_list",
        "ioc.dataframe-import.authority-profiles[0].maximum-merge-policy=fill-missing",
        "ioc.dataframe-import.contracts[0].id=ip-list-v1",
        "ioc.dataframe-import.contracts[0].version=1",
        "ioc.dataframe-import.contracts[0].charset=UTF-8",
        "ioc.dataframe-import.contracts[0].dialect.delimiter=;",
        "ioc.dataframe-import.contracts[0].dialect.quote=\"",
        "ioc.dataframe-import.contracts[0].dialect.record-separator=crlf-or-lf",
        "ioc.dataframe-import.contracts[0].dialect.header-required=true",
        "ioc.dataframe-import.contracts[0].mode=processed",
        "ioc.dataframe-import.contracts[0].routing=target-only",
        "ioc.dataframe-import.contracts[0].row-failure-policy=accept-valid",
        "ioc.dataframe-import.contracts[0].duplicate-policy=coalesce",
        "ioc.dataframe-import.contracts[0].renew-unchanged=true",
        "ioc.dataframe-import.contracts[0].formula-policy=reject",
        "ioc.dataframe-import.contracts[0].merge-default=fill-missing",
        "ioc.dataframe-import.contracts[0].recognition.required-columns[0]=ip",
        "ioc.dataframe-import.contracts[0].artifacts[0].name=ip_list",
        "ioc.dataframe-import.contracts[0].artifacts[0].role=primary",
        "ioc.dataframe-import.contracts[0].artifacts[0].record-key=ip-row-v1",
        "ioc.dataframe-import.contracts[0].artifacts[0].match-keys[0]=ip-v1",
        "ioc.dataframe-import.contracts[0].artifacts[0].columns[0].target=ip",
        "ioc.dataframe-import.contracts[0].artifacts[0].columns[0].source=ip",
        "spring.main.banner-mode=off"
})
class DaemonRuntimeModeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    IngestSourceUseCase ingestSourceUseCase;

    @Test
    void daemon_context_has_ingest_flow_without_cli_runner() {
        var beanFactory = ((ConfigurableApplicationContext) context).getBeanFactory();
        assertThat(beanFactory.containsSingleton("dataframeStorageDataSource")).isTrue();
        assertThat(beanFactory.containsSingleton("dataframeFormatSchemaMigration")).isTrue();
        // Boot JDBC auto-configuration is intentionally absent; project-owned indicators below
        // expose the service and dataframe checks without a duplicate aggregate contributor.
        assertThat(context.containsBean("dbHealthContributor")).isFalse();
        assertThat(ingestSourceUseCase).isNotNull();
        assertThat(context.getBeansOfType(CliRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(IntegrationFlow.class))
                .containsKey("iocIngestionFlow");
        assertThat(context.getBean("iocIngestionFlow", Lifecycle.class).isRunning()).isTrue();
        assertThat(context.getBean(IngestionLedger.class)).isInstanceOf(FileIngestionLedger.class);
        assertThat(context.getBeansOfType(HikariDataSource.class))
                .containsOnlyKeys("dataframeStorageDataSource", "serviceStorageDataSource");
        assertThat(context.getBeansOfType(IngestionLedgerHealthIndicator.class))
                .containsOnlyKeys("ingestionLedgerHealthIndicator");
        assertThat(context.getBeansOfType(IngestionLifecycleHealthIndicator.class))
                .containsOnlyKeys("ingestionLifecycleHealthIndicator");
        assertThat(context.getBean(IngestionLifecycleHealthIndicator.class).health().getStatus())
                .isEqualTo(Status.UP);
        assertThat(context.getBeansOfType(JdbcStorageHealthIndicator.class))
                .containsOnlyKeys("jdbcStorageHealthIndicator", "dataframeStorageHealthIndicator");
        assertThat(context.getBeansOfType(DaemonExportScheduler.class))
                .containsOnlyKeys("daemonExportScheduler");
        assertThat(context.getBeansOfType(DaemonSliceRetentionScheduler.class))
                .containsOnlyKeys("daemonSliceRetentionScheduler");
        assertThat(context.getBeansOfType(ExportHealthIndicator.class))
                .containsOnlyKeys("exportHealthIndicator");
        assertThat(context.getBeansOfType(DaemonFetchScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(DaemonPublishScheduler.class)).isEmpty();
        assertThat(context.containsBean("managedDataframeImportRuntime")).isTrue();
        assertThat(context.containsBean("dataframeImportHealthIndicator")).isTrue();
        assertThat(context.getBean(SliceRetentionGuard.class))
                .isSameAs(StandaloneSliceRetentionGuard.INSTANCE);
    }
}
