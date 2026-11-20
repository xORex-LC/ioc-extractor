package com.iocextractor.config.boot;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.iocextractor.config.boot.PropertyInspection.Coverage;
import static com.iocextractor.config.boot.PropertyInspection.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyInspectionTest {

    record Connection(String name, Duration requestTimeout) { }
    record Settings(Boolean enabled, Duration requestTimeout, Connection primary,
                    List<Connection> connections, List<String> tags, Map<String, String> labels) { }
    record Foo(String bar) { }
    record Ambiguous(String fooBar, Foo foo) { }

    private static final PropertySchema SCHEMA = new PropertySchema("collector", Settings.class);

    @Test
    void recognizesRelaxedPropertiesAndRetainsCanonicalNames() {
        PropertyInspection inspection = inspect(map("input", "collector.requestTimeout", "collector.requesttimeout",
                "collector.primary.request-timeout", "collector.connections[12].name", "collector.tags",
                "collector.tags[0]", "collector.labels.region"));

        assertThat(inspection.declarations()).allMatch(d -> d.status() == Status.KNOWN)
                .flatExtracting(PropertyInspection.Declaration::canonicalNames)
                .contains("collector.request-timeout", "collector.primary.request-timeout",
                        "collector.connections[12].name", "collector.tags", "collector.tags[0]", "collector.labels.region");
        assertThat(inspection.complete()).isTrue();
    }

    @Test
    void recognizesCompactSplitAndIndexedEnvironmentNames() {
        PropertyInspection inspection = inspect(env("systemEnvironment", "COLLECTOR_REQUESTTIMEOUT",
                "COLLECTOR_REQUEST_TIMEOUT", "COLLECTOR_CONNECTIONS_0_REQUESTTIMEOUT", "COLLECTOR_TAGS"));

        assertThat(inspection.declarations()).allMatch(d -> d.status() == Status.KNOWN)
                .flatExtracting(PropertyInspection.Declaration::canonicalNames)
                .contains("collector.request-timeout", "collector.connections[0].request-timeout", "collector.tags");
        assertThat(inspection.complete()).isTrue();
    }

    @Test
    void collectsUnknownsAndAmbiguityWithoutDiscardingEither() {
        PropertyInspection inspection = PropertyInspector.inspect(new PropertySchema("collector", Ambiguous.class),
                List.of(env("systemEnvironment", "COLLECTOR_A_TYPO", "COLLECTOR_FOO_BAR")));

        assertThat(inspection.declarations()).extracting(PropertyInspection.Declaration::status)
                .containsExactly(Status.UNKNOWN, Status.AMBIGUOUS);
        assertThat(inspection.declarations().getLast().canonicalNames())
                .containsExactly("collector.foo-bar", "collector.foo.bar");
        assertThat(inspection.complete()).isTrue();
    }

    @Test
    void preservesSourceOrderAndShadowedNullDeclarationsWithoutReadingValues() {
        PropertySource<?> high = new EnumerablePropertySource<Object>("secret-source-label", new Object()) {
            @Override public String[] getPropertyNames() {
                return new String[]{"collector.tags", "collector.enabled"};
            }
            @Override public Object getProperty(String name) {
                throw new AssertionError("Inspection must not read a value");
            }
        };
        Map<String, Object> lower = new LinkedHashMap<>();
        lower.put("collector.enabled", null);
        PropertyInspection inspection = PropertyInspector.inspect(SCHEMA,
                List.of(high, new MapPropertySource("lower-secret-label", lower)));

        assertThat(inspection.declarations()).extracting(PropertyInspection.Declaration::rawName)
                .containsExactly("collector.enabled", "collector.tags", "collector.enabled");
        assertThat(inspection.declarations()).extracting(d -> d.source().ordinal()).containsExactly(0, 0, 1);
        assertThat(inspection.toString()).doesNotContain("secret-source-label", "lower-secret-label");
    }

    @Test
    void expandsCompositeAndDisclosesNonenumerableChild() {
        CompositePropertySource composite = new CompositePropertySource("private-label");
        composite.addPropertySource(map("first", "collector.enabled"));
        composite.addPropertySource(new PropertySource<Object>("opaque", new Object()) {
            @Override public Object getProperty(String name) { throw new AssertionError("No reads"); }
        });
        PropertyInspection inspection = inspect(composite);

        assertThat(inspection.sources()).extracting(PropertyInspection.SourceCoverage::state)
                .containsExactly(Coverage.EXPANDED, Coverage.ENUMERATED, Coverage.INCOMPLETE);
        assertThat(inspection.declarations()).hasSize(1);
        assertThat(inspection.complete()).isFalse();
    }

    @Test
    void excludesBootAggregateAndIgnoresOtherPrefixes() {
        PropertyInspection inspection = PropertyInspector.inspect(SCHEMA, List.of(
                map("configurationProperties", "collector.typo"), map("input", "other.enabled")));

        assertThat(inspection.declarations()).isEmpty();
        assertThat(inspection.sources()).extracting(PropertyInspection.SourceCoverage::state)
                .containsExactly(Coverage.EXCLUDED, Coverage.ENUMERATED);
        assertThat(inspection.complete()).isTrue();
    }

    @Test
    void doesNotQualifyCustomEnvironmentSourceConventions() {
        PropertyInspection inspection = inspect(env("custom-env", "COLLECTOR_REQUESTTIMEOUT"));

        assertThat(inspection.complete()).isFalse();
        assertThat(inspection.declarations()).extracting(PropertyInspection.Declaration::status)
                .containsExactly(Status.UNSUPPORTED);
        assertThat(inspect(env("probe-systemEnvironment", "COLLECTOR_ENABLED")).complete()).isTrue();
    }

    @Test
    void exposesRootScalarAndBroaderMapGrammarAsUnsupported() {
        PropertyInspection inspection = inspect(map("input", "collector", "collector.primary",
                "collector.labels.zone.name", "collector.labels[zone.name]", "collector.connections"));

        assertThat(inspection.declarations()).extracting(PropertyInspection.Declaration::status)
                .containsOnly(Status.UNSUPPORTED);
        assertThat(inspection.complete()).isFalse();
    }

    @Test
    void distinguishesUnknownChildrenAndBadIndexesFromKnownNames() {
        PropertyInspection inspection = inspect(map("input", "collector.enabled.child", "collector.typo",
                "collector.connections[-1].name", "collector.tags[abc]"));

        assertThat(inspection.declarations()).extracting(PropertyInspection.Declaration::status)
                .containsOnly(Status.UNKNOWN);
    }

    @Test
    void propagatesEnumerationFailureWithOriginalIdentity() {
        IllegalStateException failure = new IllegalStateException("enumeration failed");
        PropertySource<?> broken = new EnumerablePropertySource<Object>("broken", new Object()) {
            @Override public String[] getPropertyNames() { throw failure; }
            @Override public Object getProperty(String name) { return null; }
        };

        assertThatThrownBy(() -> inspect(broken)).isSameAs(failure);
    }

    @Test
    void preservesDynamicMapKeysInsteadOfApplyingFieldNameEquivalence() {
        PropertyInspection inspection = inspect(map("input", "collector.labels.a-b", "collector.labels.a_b",
                "collector.labels.a--b", "collector.labels[a-b]"));
        assertThat(inspection.declarations()).allMatch(d -> d.status() == Status.KNOWN)
                .flatExtracting(PropertyInspection.Declaration::canonicalNames)
                .contains("collector.labels.a-b", "collector.labels.a_b", "collector.labels.a--b");
    }

    @Test
    void qualifiesDashedNamespaceOnPropertyAndCompactEnvironmentChannels() {
        PropertySchema schema = new PropertySchema("collector-api", Settings.class);
        PropertyInspection properties = PropertyInspector.inspect(schema,
                List.of(map("input", "collector-api.enabled")));
        PropertyInspection environment = PropertyInspector.inspect(schema,
                List.of(env("systemEnvironment", "COLLECTORAPI_ENABLED")));
        assertThat(properties.declarations()).hasSize(1).allMatch(d -> d.status() == Status.KNOWN);
        assertThat(environment.declarations()).hasSize(1).allMatch(d -> d.status() == Status.KNOWN);
    }

    @Test
    void copiesEveryResultCollection() {
        Set<String> names = new LinkedHashSet<>(Set.of("collector.enabled"));
        var source = new PropertyInspection.SourceReference(0, PropertyInspection.SourceKind.PROPERTIES);
        var declaration = new PropertyInspection.Declaration("collector.enabled", names, Status.KNOWN, source);
        List<PropertyInspection.Declaration> declarations = new ArrayList<>(List.of(declaration));
        List<PropertyInspection.SourceCoverage> sources = new ArrayList<>();
        List<PropertyInspection.SchemaGap> gaps = new ArrayList<>();
        PropertyInspection inspection = new PropertyInspection(declarations, sources, gaps);
        names.clear();
        declarations.clear();
        sources.add(new PropertyInspection.SourceCoverage(source, Coverage.INCOMPLETE));
        gaps.add(new PropertyInspection.SchemaGap("collector", "unsupported"));

        assertThat(inspection.declarations()).containsExactly(declaration);
        assertThat(declaration.canonicalNames()).containsExactly("collector.enabled");
        assertThat(inspection.sources()).isEmpty();
        assertThat(inspection.schemaGaps()).isEmpty();
        assertThatThrownBy(() -> inspection.declarations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> declaration.canonicalNames().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static PropertyInspection inspect(PropertySource<?> source) {
        return PropertyInspector.inspect(SCHEMA, List.of(source));
    }

    private static MapPropertySource map(String source, String... names) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : names) { properties.put(name, "synthetic"); }
        return new MapPropertySource(source, properties);
    }

    private static SystemEnvironmentPropertySource env(String source, String... names) {
        return new SystemEnvironmentPropertySource(source, map(source, names).getSource());
    }
}
