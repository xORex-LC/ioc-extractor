package com.iocextractor.config.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An unrelated service schema: no IOC classes, parent configuration or business rules. */
class GenericConsumerTest {

    public record Collector(Boolean enabled, Duration requestTimeout, List<Connection> connections,
                            List<String> tags, Map<String, String> labels) { }
    public record Connection(String name, Duration requestTimeout) { }

    private static final PropertySchema SCHEMA = new PropertySchema("collector", Collector.class);

    @Test
    void comparesSupportedSpellingsWithRealBootBinding() {
        for (String name : List.of("collector.request-timeout", "collector.requestTimeout", "collector.requesttimeout")) {
            MapPropertySource source = new MapPropertySource("input", Map.of(name, "30s"));
            assertThat(inspect(source).declarations()).allMatch(d -> d.status() == PropertyInspection.Status.KNOWN);
            assertThat(bind(List.of(source)).requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        }
        for (String name : List.of("COLLECTOR_REQUESTTIMEOUT", "COLLECTOR_REQUEST_TIMEOUT")) {
            var source = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(name, "30s"));
            assertThat(inspect(source).declarations()).allMatch(d -> d.status() == PropertyInspection.Status.KNOWN);
            assertThat(bind(List.of(source)).requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Test
    void separatesNameFactsFromUnknownsAndConversionFailures() {
        var source = new MapPropertySource("input", Map.of("collector.enabled", "true", "collector.request-timout", "30s"));
        assertThat(inspect(source).declarations()).extracting(PropertyInspection.Declaration::status)
                .containsExactly(PropertyInspection.Status.KNOWN, PropertyInspection.Status.UNKNOWN);
        assertThat(bind(List.of(source)).enabled()).isTrue();
        var invalid = new MapPropertySource("input", Map.of("collector.request-timeout", "invalid-duration"));
        assertThat(inspect(invalid).declarations()).allMatch(d -> d.status() == PropertyInspection.Status.KNOWN);
        assertThatThrownBy(() -> bind(List.of(invalid)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    @Test
    void observesDeclarationsRatherThanInventingAggregateProvenance() {
        var high = new MapPropertySource("high", Map.of("collector.connections[0].name", "new"));
        var low = new MapPropertySource("low", Map.of("collector.connections[0].request-timeout", "30s",
                "collector.connections[1].name", "old"));
        PropertyInspection result = PropertyInspector.inspect(SCHEMA, List.of(high, low));

        assertThat(result.declarations()).hasSize(3);
        Collector bound = bind(List.of(high, low));
        assertThat(bound.connections()).containsExactly(new Connection("new", null));
    }

    @Test
    void bindsQualifiedContainersAndRetainsNullDeclarations() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collector.tags", "east,west");
        values.put("collector.labels.region", "east");
        values.put("collector.enabled", null);
        var source = new MapPropertySource("input", values);

        assertThat(inspect(source).declarations()).hasSize(3).allMatch(d -> d.status() == PropertyInspection.Status.KNOWN);
        Collector bound = bind(List.of(source));
        assertThat(bound.tags()).containsExactly("east", "west");
        assertThat(bound.labels()).containsEntry("region", "east");
        assertThat(bound.enabled()).isNull();
    }

    private static PropertyInspection inspect(PropertySource<?> source) {
        return PropertyInspector.inspect(SCHEMA, List.of(source));
    }

    private static Collector bind(List<PropertySource<?>> sources) {
        return new Binder(ConfigurationPropertySources.from(sources)).bind("collector", Bindable.of(Collector.class)).get();
    }
}
