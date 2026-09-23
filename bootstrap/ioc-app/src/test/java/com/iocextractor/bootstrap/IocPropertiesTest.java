package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
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
    void defaultConfigurationBindsPipelineDedup() throws Exception {
        IocProperties properties = bind(Map.of());

        assertThat(properties.pipeline().deduplicate()).isTrue();
        assertThat(properties.pipeline().failurePolicy()).isEqualTo(PipelineFailurePolicy.FAIL_FAST);
        assertThat(properties.pipeline().maxDiagnosticsPerRun()).isEqualTo(10_000);
        assertThat(properties.ingestion().detect().useWatchService()).isFalse();
    }

    @Test
    void artifactPoliciesCompileLegacyAndLatestRegisteredBehavior() throws Exception {
        IocProperties defaults = bind(Map.of());

        assertThat(ArtifactPolicyCatalog.compile(defaults).get("masks"))
                .isEqualTo(ArtifactWritePolicy.legacy());

        var field = new IocProperties.Sink.Artifact.WritePolicy.Field(
                "source", "latest-registered", "keep-existing");
        var configured = new IocProperties.Sink.Artifact.WritePolicy(
                "last-nonempty", "source", List.of(field));
        IocProperties properties = withMasksPolicy(defaults, configured);

        assertThat(ArtifactPolicyCatalog.compile(properties).get("masks"))
                .satisfies(policy -> {
                    assertThat(policy.duplicateSelection())
                            .isEqualTo(ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY);
                    assertThat(policy.selectionColumn()).isEqualTo("source");
                    assertThat(policy.fields()).containsEntry(
                            "source", ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING);
                });
    }

    @Test
    void artifactPolicyCompilationReportsEveryInvalidOperatorChoice() throws Exception {
        IocProperties defaults = bind(Map.of());
        var invalidField = new IocProperties.Sink.Artifact.WritePolicy.Field(
                "mask", "replace", "erase");
        var invalid = new IocProperties.Sink.Artifact.WritePolicy(
                "newest", "mask", List.of(invalidField, invalidField));

        assertThatThrownBy(() -> ArtifactPolicyCatalog.compile(withMasksPolicy(defaults, invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(
                        "duplicate-selection must be keep-first or last-nonempty",
                        "selection-column cannot be an identity",
                        "fields[0].name cannot be an identity",
                        "fields[0].update must be latest-registered",
                        "fields[0].empty must be keep-existing",
                        "fields[1].name is duplicated");
    }

    private IocProperties withMasksPolicy(
            IocProperties source,
            IocProperties.Sink.Artifact.WritePolicy writePolicy) {
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(source.sink().artifacts());
        IocProperties.Sink.Artifact masks = artifacts.getFirst();
        artifacts.set(0, new IocProperties.Sink.Artifact(
                masks.name(), masks.enabled(), masks.path(), masks.accepts(), masks.include(),
                masks.exclude(), masks.id(), masks.columns(), writePolicy));
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), new IocProperties.Sink(source.sink().csv(), artifacts),
                source.pipeline(), source.ingestion(), source.artifactIdentity(), source.dataframeImport(),
                source.export(), source.sync(), source.maintenance(), source.lifecycle(), source.observability());
    }

    private IocProperties bind(Map<String, Object> overrides) throws Exception {
        var defaults = new YamlPropertySourceLoader()
                .load("defaults", new ClassPathResource("application.yml")).getFirst();
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("overrides", overrides));
        sources.addLast(defaults);
        ApplicationConversionService conversionService = new ApplicationConversionService();
        conversionService.addConverter(String.class, IdStart.class, IdStart::parse);
        conversionService.addConverter(Number.class, IdStart.class, IdStart::from);
        return new Binder(ConfigurationPropertySources.from(sources), null, conversionService)
                .bind("ioc", Bindable.of(IocProperties.class))
                .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
    }
}
