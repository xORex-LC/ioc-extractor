package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
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

    @Test
    void artifactPolicyCompilationRejectsSelectionAndFieldBoundaryCases() throws Exception {
        IocProperties defaults = bind(Map.of());
        var keepFirstWithSelection = new IocProperties.Sink.Artifact.WritePolicy(
                "keep-first", "source", null);
        var lastNonemptyWithoutSelection = new IocProperties.Sink.Artifact.WritePolicy(
                "last-nonempty", null, null);
        var unknownField = new IocProperties.Sink.Artifact.WritePolicy(
                "keep-first", null, List.of(new IocProperties.Sink.Artifact.WritePolicy.Field(
                "unknown", "latest-registered", "keep-existing")));

        assertThatThrownBy(() -> ArtifactPolicyCatalog.compile(
                withMasksPolicy(defaults, keepFirstWithSelection)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selection-column is only valid for last-nonempty");
        assertThatThrownBy(() -> ArtifactPolicyCatalog.compile(
                withMasksPolicy(defaults, lastNonemptyWithoutSelection)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selection-column must name an output column");
        assertThatThrownBy(() -> ArtifactPolicyCatalog.compile(
                withMasksPolicy(defaults, unknownField)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields[0].name must name an output column");
    }

    @Test
    void aggregateNetworkConditionsSeparateCleanHostsFromDetailedAddresses() {
        var conditions = ConfigRegistryCatalog.artifactFilters();
        var cleanDomain = classified("example.org", IndicatorType.DOMAIN, false, false, false);
        var domainPath = classified("example.org/file", IndicatorType.DOMAIN, false, true, false);
        var fullUrl = classified("https://example.org/file", IndicatorType.URL, false, true, false);
        var ipPort = classified("192.0.2.1:8443", IndicatorType.IPV4, true, false, false);
        var hashWithDetailFeatures = classified("deadbeef", IndicatorType.MD5, true, true, true);

        assertThat(conditions.get("is-clean-host").test(cleanDomain)).isTrue();
        assertThat(conditions.get("is-clean-host").test(domainPath)).isFalse();
        assertThat(conditions.get("is-clean-host").test(fullUrl)).isFalse();
        assertThat(conditions.get("is-address-with-detail").test(fullUrl)).isTrue();
        assertThat(conditions.get("is-address-with-detail").test(domainPath)).isTrue();
        assertThat(conditions.get("is-address-with-detail").test(ipPort)).isTrue();
        assertThat(conditions.get("is-address-with-detail").test(cleanDomain)).isFalse();
        assertThat(conditions.get("is-address-with-detail").test(hashWithDetailFeatures)).isFalse();
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

    private ClassifiedIndicator classified(String value, IndicatorType type,
                                            boolean hasPort, boolean hasPath, boolean hasQuery) {
        var indicator = new Indicator(value, type, new SourceContext(null, null));
        HostKind hostKind = type == IndicatorType.IPV4 ? HostKind.IP : HostKind.REGISTRABLE;
        var features = new IndicatorFeatures(value, value, hasPort, hasPath, hasQuery, hostKind);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(features, -1, List.of(), new MaskMatch(null, null)));
    }
}
