package com.iocextractor;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.sync.PublishLedgerSliceRetentionGuard;
import com.iocextractor.bootstrap.DaemonFetchScheduler;
import com.iocextractor.bootstrap.DaemonPublishScheduler;
import com.iocextractor.bootstrap.DaemonSliceRetentionScheduler;
import com.iocextractor.bootstrap.TransportRegistry;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime wiring gate for enabled sync and managed import sharing one daemon context. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ioc.runtime.mode=daemon",
        "ioc.lifecycle.validity.mode=fixed",
        "ioc.dataframe-import.enabled=true",
        "ioc.dataframe-import.sources[0].id=runtime-local",
        "ioc.dataframe-import.sources[0].transport=local",
        "ioc.dataframe-import.sources[0].location=target/test-sync-daemon/import/inbox",
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
        "ioc.dataframe-import.contracts[0].mode=as-is",
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
        "ioc.sync.enabled=true",
        "ioc.sync.fetch.enabled=true",
        "ioc.sync.fetch.interval=1h",
        "ioc.sync.fetch.sources[0].name=source-one",
        "ioc.sync.fetch.sources[0].endpoint=share",
        "ioc.sync.fetch.sources[0].remote-path=/incoming",
        "ioc.sync.fetch.sources[0].include[0]=*.htm",
        "ioc.sync.fetch.sources[0].exclude[0]=*.part",
        "ioc.sync.publish.enabled=true",
        "ioc.sync.publish.interval=1h",
        "ioc.sync.publish.targets[0].name=target-one",
        "ioc.sync.publish.targets[0].endpoint=share",
        "ioc.sync.publish.targets[0].remote-path=/outgoing",
        "ioc.sync.publish.targets[0].export-profile=reputation-lists",
        "ioc.sync.endpoints[0].name=share",
        "ioc.sync.endpoints[0].transport=smb",
        "ioc.sync.endpoints[0].smb.host=files.example.test",
        "ioc.sync.endpoints[0].smb.share=ioc",
        "ioc.sync.endpoints[0].smb.username=sync-test",
        "ioc.sync.endpoints[0].smb.password=not-a-production-secret",
        "ioc.export.root=target/test-sync-daemon/export",
        "ioc.storage.service.url=jdbc:sqlite:target/test-sync-daemon/ioc-service.db",
        "ioc.storage.dataframe.url=jdbc:sqlite:target/test-sync-daemon/ioc-dataframe.db",
        "ioc.ingestion.dirs.inbox=target/test-sync-daemon/inbox",
        "ioc.ingestion.dirs.processing=target/test-sync-daemon/processing",
        "ioc.ingestion.dirs.done=target/test-sync-daemon/done",
        "ioc.ingestion.dirs.failed=target/test-sync-daemon/failed",
        "ioc.ingestion.ledger.path=target/test-sync-daemon/ledger",
        "spring.main.banner-mode=off"
})
@IntegrationTest
class SyncEnabledDaemonRuntimeModeIT {

    @Autowired
    ApplicationContext context;

    @Test
    void enabledSyncWiresSchedulersRegistryAndDeliveryAwareRetention() {
        assertThat(context.getBeansOfType(TransportRegistry.class))
                .containsOnlyKeys("transportRegistry");
        assertThat(context.getBeansOfType(DaemonFetchScheduler.class))
                .containsOnlyKeys("daemonFetchScheduler");
        assertThat(context.getBeansOfType(DaemonPublishScheduler.class))
                .containsOnlyKeys("daemonPublishScheduler");
        assertThat(context.getBeansOfType(KeyedSerialExecutor.class))
                .containsOnlyKeys("dataframeImportLanes", "syncKeyedExecutor");
        assertThat(context.containsBean("managedDataframeImportRuntime")).isTrue();
        assertThat(context.containsBean("syncHealthIndicator")).isTrue();
        assertThat(context.getBean(SliceRetentionGuard.class))
                .isInstanceOf(PublishLedgerSliceRetentionGuard.class);
        assertThat(context.getBean(DaemonPublishScheduler.class).getPhase())
                .isGreaterThan(context.getBean(com.iocextractor.bootstrap.DaemonExportScheduler.class).getPhase())
                .isLessThan(context.getBean(DaemonSliceRetentionScheduler.class).getPhase());
    }
}
