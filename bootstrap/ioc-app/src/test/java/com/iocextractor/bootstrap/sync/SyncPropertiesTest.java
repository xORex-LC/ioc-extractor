package com.iocextractor.bootstrap.sync;

import com.iocextractor.bootstrap.IocProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsDuplicateEndpointNames() throws Exception {
        defaults();

        assertThatThrownBy(() -> sync(List.of(endpoint("share"), endpoint("share")),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate sync endpoint name");
    }

    @Test
    void rejectsUnknownEndpointReferences() throws Exception {
        IocProperties properties = defaults();

        assertThatThrownBy(() -> withSync(properties, sync(List.of(endpoint("known")),
                List.of(source("src", "missing")), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references unknown endpoint");

        assertThatThrownBy(() -> withSync(properties, sync(List.of(endpoint("known")),
                List.of(), List.of(target("target", "missing", "reputation-lists")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references unknown endpoint");
    }

    @Test
    void rejectsUnknownExportProfileTargets() throws Exception {
        IocProperties properties = defaults();
        IocProperties.Sync sync = sync(List.of(endpoint("known")), List.of(),
                List.of(target("target", "known", "missing-profile")));

        assertThatThrownBy(() -> withSync(properties, sync))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown sync publish export profile");
    }

    @Test
    void rejectsInvalidTriggerAndRetryNumbers() throws Exception {
        assertThatThrownBy(() -> new IocProperties.Sync.Publish(true, "watch",
                java.time.Duration.ofMinutes(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported sync publish trigger");

        assertThatThrownBy(() -> new IocProperties.Sync.Retry(0,
                java.time.Duration.ofSeconds(1), 2.0d, java.time.Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void rejectsIncompleteSmbCredentialsAfterBinding() {
        assertThatThrownBy(() -> new IocProperties.Sync.Endpoint.Smb(
                "server", "share", null, "user", "", true, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    private IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        return new Binder(ConfigurationPropertySources.from(source))
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
                new IocProperties.Sync.Publish(false, "on-new-output",
                        java.time.Duration.ofMinutes(5), targets));
    }

    private IocProperties.Sync.Endpoint endpoint(String name) {
        return new IocProperties.Sync.Endpoint(name, "smb",
                new IocProperties.Sync.Endpoint.Smb("server", "share", null,
                        "user", "secret", true, null, null, null));
    }

    private IocProperties.Sync.Fetch.Source source(String name, String endpoint) {
        return new IocProperties.Sync.Fetch.Source(name, endpoint, "/incoming",
                List.of("*.htm"), List.of("*.part"));
    }

    private IocProperties.Sync.Publish.Target target(String name, String endpoint, String profile) {
        return new IocProperties.Sync.Publish.Target(name, endpoint, "/out", profile);
    }

    private IocProperties withSync(IocProperties source, IocProperties.Sync sync) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), source.sink(), source.lookup(), source.ingestion(),
                source.artifactIdentity(), source.export(), sync, source.maintenance(), source.observability());
    }
}
