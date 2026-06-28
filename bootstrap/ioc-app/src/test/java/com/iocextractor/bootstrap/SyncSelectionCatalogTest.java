package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncSelectionCatalogTest {

    @Test
    void acceptsConfiguredFetchAndPublishSelectionsWithoutIo() throws Exception {
        SyncSelectionCatalog catalog = catalog(enabledSync());

        assertThatNoException().isThrownBy(() -> catalog.validateFetch(
                new RemoteFetchCommand(Optional.of("incoming"), Optional.of("primary"), true)));
        assertThatNoException().isThrownBy(() -> catalog.validatePublish(
                new ArtifactPublishCommand(Optional.of("reputation-lists"),
                        Optional.of("delivery"), Optional.of("primary"), true)));
    }

    @Test
    void rejectsUnknownEndpointBeforeMatchingLogicalSelections() throws Exception {
        SyncSelectionCatalog catalog = catalog(enabledSync());

        assertThatThrownBy(() -> catalog.validateFetch(
                new RemoteFetchCommand(Optional.empty(), Optional.of("missing"), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown sync endpoint: missing");
    }

    @Test
    void rejectsKnownEndpointThatDoesNotOwnSelectedTarget() throws Exception {
        SyncSelectionCatalog catalog = catalog(enabledSync());

        assertThatThrownBy(() -> catalog.validatePublish(
                new ArtifactPublishCommand(Optional.of("reputation-lists"),
                        Optional.of("delivery"), Optional.of("secondary"), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No sync publish target matches selection");
    }

    @Test
    void rejectsDisabledOperation() throws Exception {
        IocProperties.Sync enabled = enabledSync();
        IocProperties.Sync fetchDisabled = new IocProperties.Sync(
                true, enabled.retry(), enabled.endpoints(),
                new IocProperties.Sync.Fetch(false, Duration.ofMinutes(1), enabled.fetch().sources()),
                enabled.publish());
        SyncSelectionCatalog catalog = catalog(fetchDisabled);

        assertThatThrownBy(() -> catalog.validateFetch(new RemoteFetchCommand(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote sync fetch is disabled");
    }

    @Test
    void rejectsAllSelectionsWhenSyncIsDisabled() throws Exception {
        IocProperties.Sync enabled = enabledSync();
        IocProperties.Sync disabled = new IocProperties.Sync(
                false, enabled.retry(), enabled.endpoints(), enabled.fetch(), enabled.publish());
        SyncSelectionCatalog catalog = catalog(disabled);

        assertThatThrownBy(() -> catalog.validatePublish(new ArtifactPublishCommand(Optional.empty(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote sync is disabled (ioc.sync.enabled=false)");
    }

    private SyncSelectionCatalog catalog(IocProperties.Sync sync) throws Exception {
        IocProperties defaults = defaults();
        IocProperties properties = new IocProperties(
                defaults.engine(), defaults.runtime(), defaults.storage(), defaults.source(), defaults.refang(),
                defaults.patterns(), defaults.classify(), defaults.sink(), defaults.lookup(), defaults.ingestion(),
                defaults.artifactIdentity(), defaults.export(), sync,
                defaults.maintenance(), defaults.observability());
        return new SyncSelectionCatalog(properties);
    }

    private IocProperties.Sync enabledSync() {
        return new IocProperties.Sync(
                true,
                new IocProperties.Sync.Retry(
                        3, Duration.ofSeconds(1), 2.0d, Duration.ofSeconds(30), false),
                List.of(endpoint("primary"), endpoint("secondary")),
                new IocProperties.Sync.Fetch(true, Duration.ofMinutes(1), List.of(
                        new IocProperties.Sync.Fetch.Source(
                                "incoming", "primary", "/incoming", List.of("*.htm"), List.of("*.part")))),
                new IocProperties.Sync.Publish(true, "both", Duration.ofMinutes(5), List.of(
                        new IocProperties.Sync.Publish.Target(
                                "delivery", "primary", "/out", "reputation-lists"))));
    }

    private IocProperties.Sync.Endpoint endpoint(String name) {
        return new IocProperties.Sync.Endpoint(name, "smb",
                new IocProperties.Sync.Endpoint.Smb(
                        "server", "share", null, "user", "secret", true,
                        null, null, null));
    }

    private IocProperties defaults() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }
}
