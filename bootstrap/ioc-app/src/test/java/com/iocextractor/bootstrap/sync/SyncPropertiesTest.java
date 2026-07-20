package com.iocextractor.bootstrap.sync;

import com.iocextractor.bootstrap.IocProperties;
import com.iocextractor.bootstrap.SyncTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPropertiesTest {

    @Test
    void defaultSyncConfigurationBindsWithoutActivatingTransport() throws Exception {
        IocProperties properties = defaults();

        assertThat(properties.sync().enabled()).isFalse();
        assertThat(properties.sync().endpoints()).isEmpty();
        assertThat(properties.sync().fetch().sources()).isEmpty();
        assertThat(properties.sync().publish().targets()).isEmpty();
    }

    @Test
    void bindsChangeNotifyDefaultsAndExplicitDebounce() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Sync.Fetch.Source disabled = source("disabled", "known");
        IocProperties.Sync.Fetch.Source enabled = new IocProperties.Sync.Fetch.Source(
                "enabled", "known", "/incoming", List.of("*.htm"), List.of("*.part"),
                new IocProperties.Sync.Fetch.Source.ChangeNotify(true, Duration.ofSeconds(5)));

        IocProperties.Sync sync = sync(List.of(endpoint("known")), List.of(disabled, enabled), List.of());

        IocProperties.Sync.Fetch fetch = withSync(properties, sync).sync().fetch();
        assertThat(fetch.sources().getFirst().changeNotify().enabled()).isFalse();
        assertThat(fetch.sources().getFirst().changeNotify().debounce()).isEqualTo(Duration.ofSeconds(3));
        assertThat(fetch.sources().get(1).changeNotify().enabled()).isTrue();
        assertThat(fetch.sources().get(1).changeNotify().debounce()).isEqualTo(Duration.ofSeconds(5));
    }

    private IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        ApplicationConversionService conversionService = new ApplicationConversionService();
        conversionService.addConverter(String.class, com.iocextractor.bootstrap.IdStart.class,
                com.iocextractor.bootstrap.IdStart::parse);
        conversionService.addConverter(Number.class, com.iocextractor.bootstrap.IdStart.class,
                com.iocextractor.bootstrap.IdStart::from);
        return new Binder(ConfigurationPropertySources.from(source), null, conversionService)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }

    private IocProperties.Sync sync(List<IocProperties.Sync.Endpoint> endpoints,
                                    List<IocProperties.Sync.Fetch.Source> sources,
                                    List<IocProperties.Sync.Publish.Target> targets) {
        return new IocProperties.Sync(false,
                new IocProperties.Sync.Retry(3, java.time.Duration.ofSeconds(1), 2.0d,
                        java.time.Duration.ofSeconds(30), true),
                endpoints,
                new IocProperties.Sync.Fetch(false, java.time.Duration.ofMinutes(1), sources),
                new IocProperties.Sync.Publish(false, java.time.Duration.ofMinutes(5), targets));
    }

    private IocProperties.Sync.Endpoint endpoint(String name) {
        return new IocProperties.Sync.Endpoint(name, SyncTransport.SMB,
                new IocProperties.Sync.Endpoint.Smb("server", "share", null,
                        "user", "secret", true, null, null, null));
    }

    private IocProperties.Sync.Fetch.Source source(String name, String endpoint) {
        return new IocProperties.Sync.Fetch.Source(name, endpoint, "/incoming",
                List.of("*.htm"), List.of("*.part"), null);
    }

    private IocProperties withSync(IocProperties source, IocProperties.Sync sync) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), source.sink(), source.pipeline(), source.ingestion(),
                source.artifactIdentity(), source.export(), sync, source.maintenance(), source.observability());
    }
}
