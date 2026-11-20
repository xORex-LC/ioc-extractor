package com.iocextractor.config.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.core.env.MapPropertySource;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertySchemaTest {

    record Root(String enabled) { }
    record Unsupported(@Name("remote-name") String displayName, String URLValue, String snake_name, Set<String> tags,
                       String[] names, Map<String, Root> nested, MutableChild child,
                       List<?> unresolved, List<List<String>> lists, Token token) { }
    record Token(String value) { }
    record Recursive(Recursive child) { }
    record Generic<T>(List<T> values) { }
    record Scalars(int count, Boolean enabled, Character letter, Double number, Path path, Charset charset,
                   Duration timeout, java.time.Instant instant, java.time.Period period,
                   java.net.URI uri, java.net.URL url, java.net.InetAddress address, java.util.UUID uuid,
                   java.util.Locale locale, java.util.Date date, Class<?> type) { }
    static final class MutableChild { public String getName() { return "synthetic"; } }
    enum Region { EAST, WEST }
    record EnumMap(Map<Region, String> labels) { }
    record InvalidMap(Map<Integer, String> labels) { }
    @SuppressWarnings("rawtypes")
    record RawList(List values) { }

    @Test
    void rejectsInvalidRootsAndPrefixes() {
        for (String prefix : List.of("", "two.parts", "UPPER", "indexed[0]")) {
            assertThatThrownBy(() -> new PropertySchema(prefix, Root.class)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PropertySchema("collector", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertySchema(null, Root.class)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PropertySchema("collector", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void reportsSchemaGapsEvenWithoutDeclarations() {
        PropertyInspection result = PropertyInspector.inspect(new PropertySchema("collector", Unsupported.class), List.of());

        assertThat(result.complete()).isFalse();
        assertThat(result.schemaGaps()).extracting(PropertyInspection.SchemaGap::path)
                .contains("collector.display-name", "collector.-u-r-l-value", "collector.snake_name", "collector.tags", "collector.names",
                        "collector.nested", "collector.child", "collector.unresolved", "collector.lists");
    }

    @Test
    void doesNotCallUnmodeledFieldSpellingsUnknown() {
        for (String name : List.of("collector.remote-name", "collector.snake-name")) {
            PropertyInspection result = inspect(Unsupported.class, name);
            assertThat(result.declarations()).extracting(PropertyInspection.Declaration::status)
                    .containsExactly(PropertyInspection.Status.UNSUPPORTED);
        }
    }

    @Test
    void detectsRecursiveAndUnresolvedSchemasWithoutRecursingForever() {
        for (Class<?> root : List.of(Recursive.class, Generic.class, RawList.class, InvalidMap.class)) {
            PropertyInspection result = PropertyInspector.inspect(new PropertySchema("collector", root), List.of());
            assertThat(result.schemaGaps()).isNotEmpty();
            assertThat(result.complete()).isFalse();
        }
    }

    @Test
    void recognizesQualifiedScalarTypesAndEnumMapNamesWithoutConvertingValues() {
        PropertyInspection result = PropertyInspector.inspect(new PropertySchema("collector", Scalars.class), List.of());
        assertThat(result.complete()).isTrue();
        assertThat(inspect(EnumMap.class, "collector.labels.east").declarations())
                .allMatch(d -> d.status() == PropertyInspection.Status.KNOWN);
    }

    @Test
    void validatesResultIdentityAndRequiredFields() {
        assertThatThrownBy(() -> new PropertyInspection.SourceReference(-1, PropertyInspection.SourceKind.OTHER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyInspection.SourceReference(0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PropertyInspector.inspect(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PropertyInspector.inspect(new PropertySchema("collector", Root.class), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PropertyInspection inspect(Class<?> root, String name) {
        return PropertyInspector.inspect(new PropertySchema("collector", root),
                List.of(new MapPropertySource("input", Map.of(name, "synthetic"))));
    }
}
