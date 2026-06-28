package com.iocextractor;

import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.sync.PublishLedgerSliceRetentionGuard;
import com.iocextractor.bootstrap.DaemonFetchScheduler;
import com.iocextractor.bootstrap.DaemonPublishScheduler;
import com.iocextractor.bootstrap.DaemonSliceRetentionScheduler;
import com.iocextractor.bootstrap.TransportRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime wiring gate for enabled sync without opening an SMB connection at startup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ioc.runtime.mode=daemon",
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
        "ioc.lookup.path=target/test-sync-daemon/no-lookup.csv",
        "ioc.storage.service.url=jdbc:sqlite:target/test-sync-daemon/ioc-service.db",
        "ioc.storage.dataframe.url=jdbc:sqlite:target/test-sync-daemon/ioc-dataframe.db",
        "ioc.ingestion.dirs.inbox=target/test-sync-daemon/inbox",
        "ioc.ingestion.dirs.processing=target/test-sync-daemon/processing",
        "ioc.ingestion.dirs.done=target/test-sync-daemon/done",
        "ioc.ingestion.dirs.failed=target/test-sync-daemon/failed",
        "ioc.ingestion.ledger.path=target/test-sync-daemon/ledger",
        "spring.main.banner-mode=off"
})
class SyncEnabledDaemonRuntimeModeTest {

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
        assertThat(context.containsBean("syncHealthIndicator")).isTrue();
        assertThat(context.getBean(SliceRetentionGuard.class))
                .isInstanceOf(PublishLedgerSliceRetentionGuard.class);
        assertThat(context.getBean(DaemonPublishScheduler.class).getPhase())
                .isGreaterThan(context.getBean(com.iocextractor.bootstrap.DaemonExportScheduler.class).getPhase())
                .isLessThan(context.getBean(DaemonSliceRetentionScheduler.class).getPhase());
    }
}
