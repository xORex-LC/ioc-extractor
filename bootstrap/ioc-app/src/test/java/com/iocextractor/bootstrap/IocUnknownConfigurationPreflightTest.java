package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IocUnknownConfigurationPreflightTest {

    @Test
    void keepsIocSpellingRestrictionsWhileSharedInspectorSupportsRelaxedNames() {
        for (String name : java.util.List.of("ioc.ingestion.stability.quietPeriod", "ioc.ingestion.stability.quietperiod")) {
            MockEnvironment environment = new MockEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("input", Map.of(name, "15s")));
            assertThatThrownBy(() -> new IocUnknownConfigurationPreflight(environment).postProcessBeanFactory(null))
                    .isInstanceOf(UnboundConfigurationPropertiesException.class);
        }
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("IOC_INGESTION_STABILITY_QUIETPERIOD", "15s")));
        assertThatThrownBy(() -> new IocUnknownConfigurationPreflight(environment).postProcessBeanFactory(null))
                .isInstanceOf(UnboundConfigurationPropertiesException.class);
    }

    @Test
    void keepsCanonicalSplitEnvironmentAndServiceConvertedLeaves() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("input", Map.of(
                "ioc.sink.artifacts[0].id.start", "auto", "ioc.ingestion.stability.quiet-period", "15s",
                "ioc.patterns.IPV4", "synthetic-regex", "ioc.patterns.SHA256", "synthetic-regex",
                "ioc.patterns[DOMAIN]", "synthetic-regex")));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("IOC_INGESTION_STABILITY_QUIET_PERIOD", "15s")));
        assertThatCode(() -> new IocUnknownConfigurationPreflight(environment).postProcessBeanFactory(null))
                .doesNotThrowAnyException();
    }

    @Test
    void keepsEnumerationFailureForCompositeWithOpaqueChild() {
        CompositePropertySource composite = new CompositePropertySource("input");
        composite.addPropertySource(new MapPropertySource("known", Map.of("ioc.engine", "jdk")));
        composite.addPropertySource(new PropertySource<>("opaque", new Object()) {
            @Override
            public Object getProperty(String name) {
                throw new AssertionError("Name inspection must not read values");
            }
        });
        for (PropertySource<?> source : java.util.List.of(composite, nestedComposite(composite))) {
            MockEnvironment environment = new MockEnvironment();
            environment.getPropertySources().addFirst(source);
            assertThatThrownBy(() -> new IocUnknownConfigurationPreflight(environment).postProcessBeanFactory(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("opaque");
            IocConfigurationOverrideReporter reporter = new IocConfigurationOverrideReporter(environment);
            assertThatThrownBy(() -> reporter.effectiveOverrides(environment.getPropertySources()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("opaque");
        }
    }

    @Test
    void acceptsEnumerableCompositeAndIgnoresEmptyComposite() {
        CompositePropertySource known = new CompositePropertySource("input");
        known.addPropertySource(new MapPropertySource("known", Map.of("ioc.engine", "jdk")));
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(known);
        environment.getPropertySources().addFirst(new CompositePropertySource("empty"));

        assertThatCode(() -> new IocUnknownConfigurationPreflight(environment).postProcessBeanFactory(null))
                .doesNotThrowAnyException();
        assertThat(new IocConfigurationOverrideReporter(environment).effectiveOverrides(environment.getPropertySources()))
                .extracting(IocConfigurationOverrideReporter.Override::key, IocConfigurationOverrideReporter.Override::source)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ioc.engine", "input"));
    }

    private CompositePropertySource nestedComposite(PropertySource<?> child) {
        CompositePropertySource nested = new CompositePropertySource("nested");
        nested.addPropertySource(child);
        return nested;
    }

    @Test
    void treatsNonContainerLeafTypesAsTerminalProperties() {
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Path.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Charset.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Float.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Duration.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(IdStart.class)).isTrue();
    }
}
