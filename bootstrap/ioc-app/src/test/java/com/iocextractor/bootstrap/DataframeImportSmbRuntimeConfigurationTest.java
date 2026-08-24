package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.transport.smb.SmbChangeNotifyWatcher;
import com.iocextractor.adapter.out.transport.smb.SmbFileTransport;
import com.iocextractor.adapter.out.transport.smb.SmbImportChangeSignalSource;
import com.iocextractor.adapter.out.transport.smb.SmbSessionPool;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the disabled-sync, enabled-import SMB composition seam without network I/O. */
class DataframeImportSmbRuntimeConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsSharedSmbRuntimeForImportWhenOrdinarySyncIsDisabled() {
        new ApplicationContextRunner()
                .withInitializer(DataframeImportSmbRuntimeConfigurationTest::addDefaultYaml)
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(properties(tempDir))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SmbSessionPool.class);
                    assertThat(context).hasSingleBean(SmbFileTransport.class);
                    assertThat(context).hasSingleBean(SmbChangeNotifyWatcher.class);
                    assertThat(context).doesNotHaveBean(TransportRegistry.class);

                    var configuration = new DataframeImportRuntimeConfiguration();
                    ManagedImportSourceAdapters adapters = configuration.managedImportSourceAdapters(
                            context.getBean(DataframeImportCatalog.class),
                            context.getBean(IocProperties.class),
                            context.getBean(SmbSessionPool.class),
                            context.getBean(SmbChangeNotifyWatcher.class));

                    assertThat(adapters.lifecycle()).isInstanceOf(RoutedManagedImportSourceLifecycle.class);
                    assertThat(adapters.changeSignals())
                            .singleElement()
                            .isInstanceOf(SmbImportChangeSignalSource.class);
                });
    }

    private String[] properties(Path root) {
        return new String[] {
                "ioc.dataframe-import.enabled=true",
                "ioc.dataframe-import.runtime.dirs.snapshots=" + root.resolve("snapshots"),
                "ioc.dataframe-import.sources[0].id=smb-feed",
                "ioc.dataframe-import.sources[0].transport=smb",
                "ioc.dataframe-import.sources[0].endpoint=primary",
                "ioc.dataframe-import.sources[0].location=import",
                "ioc.dataframe-import.sources[0].contracts[0]=ip-list-v1",
                "ioc.dataframe-import.sources[0].authority=standard",
                "ioc.dataframe-import.authority-profiles[0].id=standard",
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
                "ioc.sync.enabled=false",
                "ioc.sync.endpoints[0].name=primary",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.host=unused.invalid",
                "ioc.sync.endpoints[0].smb.share=test-share",
                "ioc.sync.endpoints[0].smb.username=test-user",
                "ioc.sync.endpoints[0].smb.password=test-password"
        };
    }

    private static void addDefaultYaml(ConfigurableApplicationContext context) {
        try {
            var source = new YamlPropertySourceLoader()
                    .load("defaults", new ClassPathResource("application.yml")).getFirst();
            context.getEnvironment().getPropertySources().addLast(source);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load default application.yml", failure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IocProperties.class)
    @Import({ ConfigPreflightConfiguration.class, DataframeImportConfiguration.class, SyncConfig.class })
    static class TestConfig {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
