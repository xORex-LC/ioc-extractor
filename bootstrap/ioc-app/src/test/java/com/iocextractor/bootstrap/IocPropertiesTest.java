package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IocPropertiesTest {

    @Test
    void defaultConfigurationBindsPipelineDedupWithoutLegacyLookup() throws Exception {
        IocProperties properties = bind(Map.of());

        assertThat(properties.pipeline().deduplicate()).isTrue();
        assertThat(properties.lookup()).isNull();
    }

    @Test
    void rejectsLegacyLookupDeduplicateWithMovedMessage() {
        assertThatThrownBy(() -> bind(Map.of("ioc.lookup.deduplicate", "false")))
                .satisfies(thrown -> assertThat(rootCause(thrown))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("ioc.lookup.deduplicate moved to ioc.pipeline.deduplicate"));
    }

    @Test
    void rejectsLegacyLookupStorageKeysWithRemovedMessage() {
        assertThatThrownBy(() -> bind(Map.of("ioc.lookup.path", "./dataframe/masks_list.csv")))
                .satisfies(thrown -> assertThat(rootCause(thrown))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("legacy ioc.lookup.* removed; SQLite/JDBC dataframe storage is the only runtime truth"));
    }

    @Test
    void rejectsNonJdbcDataframeStorageBeforeBeanWiring() {
        assertThatThrownBy(() -> bind(Map.of("ioc.storage.dataframe.type", "disabled")))
                .satisfies(thrown -> assertThat(rootCause(thrown))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("ioc.storage.dataframe.type must be jdbc; legacy CSV dataframe storage was removed"));
    }

    @Test
    void rejectsExplicitIdStartForArtifactWithoutPublicIdColumn() throws Exception {
        IocProperties properties = bind(Map.of());
        IocProperties.Sink.Artifact addressBlacklist = properties.sink().artifacts().get(2);
        IocProperties.Sink.Artifact invalid = new IocProperties.Sink.Artifact(
                addressBlacklist.name(),
                addressBlacklist.enabled(),
                addressBlacklist.path(),
                addressBlacklist.accepts(),
                addressBlacklist.include(),
                addressBlacklist.exclude(),
                new IocProperties.Sink.Artifact.Id("ascending", "42"),
                addressBlacklist.columns());

        assertThatThrownBy(() -> withSinkArtifact(properties, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Artifact address_blacklist configures id.start but has no public id column");
    }

    private IocProperties bind(Map<String, Object> overrides) throws Exception {
        var defaults = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("overrides", overrides));
        sources.addLast(defaults);
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }

    private IocProperties withSinkArtifact(IocProperties source, IocProperties.Sink.Artifact replacement) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        artifacts.set(2, replacement);
        IocProperties.Sink sink = new IocProperties.Sink(source.sink().csv(), artifacts);
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), sink, source.pipeline(), source.lookup(), source.ingestion(),
                source.artifactIdentity(), source.export(), source.sync(), source.maintenance(),
                source.observability());
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
