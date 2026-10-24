package com.iocextractor.config.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.iocextractor.config.boot.MigrationInspection.Resolution;
import static com.iocextractor.config.boot.PropertyMigration.Kind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent consumer fixtures keep name observations separate from actual bound values. */
class MigrationInspectionTest {

    public record Collector(Duration legacyTimeout, Duration requestTimeout, List<Connection> connections, Foo foo) { }
    public record Connection(String name, Duration legacyTimeout, Duration requestTimeout) { }
    public record Foo(String bar) { }
    record Ambiguous(String fooBar, Foo foo) { }
    record Custom(Object value) { }

    private static final PropertySchema SCHEMA = new PropertySchema("collector", Collector.class);
    private static final PropertyMigration FIXED = migration("collector.legacy-timeout", Kind.DEPRECATED,
            "collector.request-timeout");
    private static final PropertyMigration INDEXED = migration("collector.connections[].legacy-timeout", Kind.DEPRECATED,
            "collector.connections[].request-timeout");

    @Test
    void qualifiesAcceptedSpellingsAgainstRealBinderWithoutApplyingReplacement() {
        for (String name : List.of("collector.legacy-timeout", "collector.legacyTimeout", "collector.legacytimeout")) {
            assertAccepted(new MapPropertySource("input", Map.of(name, "30s")));
        }
        for (String name : List.of("COLLECTOR_LEGACYTIMEOUT", "COLLECTOR_LEGACY_TIMEOUT")) {
            assertAccepted(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(name, "30s")));
        }
    }

    @Test
    void findsRemovedNamesOutsideCurrentSchemaWithoutMakingThemBindable() {
        PropertyMigration removed = migration("collector.old-timeout", Kind.REMOVED, "collector.request-timeout");
        for (PropertySource<?> source : List.of(
                new MapPropertySource("input", Map.of("collector.oldTimeout", "30s", "collector.request-timeout", "5s")),
                new SystemEnvironmentPropertySource("systemEnvironment",
                        Map.of("COLLECTOR_OLD_TIMEOUT", "30s", "COLLECTOR_REQUESTTIMEOUT", "5s")))) {
            MigrationInspection result = inspect(List.of(source), List.of(removed));
            assertThat(result.complete()).isTrue();
            assertThat(result.observations()).singleElement().satisfies(observation -> {
                assertThat(observation.declaration().status()).isEqualTo(PropertyInspection.Status.UNKNOWN);
                assertThat(observation.resolution()).isEqualTo(Resolution.RESOLVED);
                assertThat(observation.matches()).extracting(MigrationInspection.Match::path)
                        .containsExactly("collector.old-timeout");
            });
            assertThat(bind(List.of(source)).requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Test
    void observesAnOldNameEvenWhenItsValueCannotBind() {
        var source = new MapPropertySource("input", Map.of("collector.legacy-timeout", "private-invalid-duration"));
        MigrationInspection result = inspect(List.of(source), List.of(FIXED));
        assertThat(result.observations()).singleElement()
                .satisfies(o -> assertThat(o.resolution()).isEqualTo(Resolution.RESOLVED));
        assertThat(result.toString()).doesNotContain("private-invalid-duration");
        assertThatThrownBy(() -> bind(List.of(source)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    @Test
    void preservesEveryConcreteIndexIncludingNestedListsAndUnboundSparseIndexes() {
        PropertyMigration nested = migration("collector.groups[].connections[].old-timeout", Kind.REMOVED,
                "collector.groups[].connections[].request-timeout");
        var source = new MapPropertySource("input", Map.of("collector.connections[0].legacy-timeout", "1s",
                "collector.connections[1].legacy-timeout", "2s", "collector.groups[12].connections[03].old-timeout", "3s"));
        MigrationInspection result = inspect(List.of(source), List.of(INDEXED, nested));

        assertThat(result.observations()).flatExtracting(MigrationInspection.Observation::matches)
                .extracting(match -> match.replacement().orElseThrow()).containsExactly(
                        "collector.connections[0].request-timeout", "collector.connections[1].request-timeout",
                        "collector.groups[12].connections[03].request-timeout");
        var env = new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("COLLECTOR_CONNECTIONS_0_LEGACYTIMEOUT", "1s", "COLLECTOR_CONNECTIONS_1_LEGACY_TIMEOUT", "2s"));
        assertThat(inspect(List.of(env), List.of(INDEXED)).observations()).hasSize(2);
        assertThat(bind(List.of(env)).connections()).containsExactly(
                new Connection(null, Duration.ofSeconds(1), null), new Connection(null, Duration.ofSeconds(2), null));
    }

    @Test
    void retainsShadowedAndNullDeclarationsWithoutClaimingAnEffectiveConflict() {
        var high = new MapPropertySource("high", Map.of("collector.connections[0].request-timeout", "5s"));
        Map<String, Object> lowValues = new LinkedHashMap<>();
        lowValues.put("collector.connections[0].legacy-timeout", "30s");
        lowValues.put("collector.legacy-timeout", null);
        var low = new MapPropertySource("low", lowValues);
        var shadowed = new MapPropertySource("last", Map.of("collector.connections[0].legacy-timeout", "60s"));
        List<PropertySource<?>> sources = List.of(high, low, shadowed);

        MigrationInspection result = inspect(sources, List.of(FIXED, INDEXED));
        assertThat(result.observations()).hasSize(3).extracting(o -> o.declaration().source().ordinal())
                .containsExactly(1, 1, 2);
        assertThat(bind(sources).connections()).containsExactly(new Connection(null, null, Duration.ofSeconds(5)));
        assertThat(bind(sources).legacyTimeout()).isNull();
    }

    @Test
    void disclosesCollisionsBetweenRemovedAndCurrentEnvironmentPaths() {
        PropertyMigration removed = migration("collector.foo-bar", Kind.REMOVED, null);
        var source = new SystemEnvironmentPropertySource("systemEnvironment", Map.of("COLLECTOR_FOO_BAR", "value"));
        MigrationInspection result = inspect(List.of(source), List.of(removed));

        assertThat(result.inspection().declarations()).singleElement()
                .satisfies(d -> assertThat(d.canonicalNames()).containsExactly("collector.foo.bar"));
        assertThat(result.observations()).singleElement()
                .satisfies(o -> assertThat(o.resolution()).isEqualTo(Resolution.AMBIGUOUS));
        assertThat(bind(List.of(source)).foo().bar()).isEqualTo("value");
        PropertyInspection ambiguous = PropertyInspector.inspect(new PropertySchema("collector", Ambiguous.class), List.of(source));
        assertThat(MigrationInspector.inspect(ambiguous, List.of(removed)).observations()).singleElement()
                .satisfies(o -> assertThat(o.resolution()).isEqualTo(Resolution.AMBIGUOUS));
    }

    @Test
    void treatsEquivalentRecordSpellingsAsOnePathWithoutLosingRealAmbiguity() {
        PropertyMigration compact = migration("collector.legacytimeout", Kind.DEPRECATED, "collector.request-timeout");
        var source = new MapPropertySource("input", Map.of("collector.legacy-timeout", "30s"));
        assertThat(inspect(List.of(source), List.of(compact)).observations()).singleElement()
                .satisfies(o -> assertThat(o.resolution()).isEqualTo(Resolution.RESOLVED));
        assertThat(bind(List.of(source)).legacyTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void preservesUnsupportedCoverageAndDoesNotReadValues() {
        PropertySource<?> unreadable = new EnumerablePropertySource<Object>("secret-label", new Object()) {
            @Override public String[] getPropertyNames() { return new String[]{"collector.legacy-timeout"}; }
            @Override public Object getProperty(String name) { throw new AssertionError("No value reads"); }
        };
        PropertySource<?> opaque = new PropertySource<Object>("opaque", new Object()) {
            @Override public Object getProperty(String name) { throw new AssertionError("No value reads"); }
        };
        MigrationInspection result = inspect(List.of(unreadable, opaque), List.of(FIXED));
        assertThat(result.complete()).isFalse();
        assertThat(result.observations()).singleElement().satisfies(o -> {
            assertThat(o.resolution()).isEqualTo(Resolution.RESOLVED);
            assertThat(o.matches().getFirst().migration()).isEqualTo(FIXED);
        });
        assertThat(result.toString()).doesNotContain("secret-label", "opaque");
        var custom = new SystemEnvironmentPropertySource("unqualified-env", Map.of("COLLECTOR_LEGACYTIMEOUT", "30s"));
        assertThat(inspect(List.of(custom), List.of(FIXED)).observations()).singleElement()
                .satisfies(o -> assertThat(o.resolution()).isEqualTo(Resolution.UNSUPPORTED));
        PropertyInspection gap = PropertyInspector.inspect(new PropertySchema("collector", Custom.class),
                List.of(new MapPropertySource("input", Map.of("collector.value.old", "value"))));
        assertThat(MigrationInspector.inspect(gap, List.of(migration("collector.value.old", Kind.REMOVED, null)))
                .observations()).singleElement().satisfies(o -> assertThat(o.resolution()).isEqualTo(Resolution.UNSUPPORTED));
    }

    @Test
    void doesNotInferChildDeclarationsFromContainersOrUnrelatedNames() {
        var source = new MapPropertySource("input", Map.of("collector", "scalar", "collector.connections", "scalar",
                "collector.connections[0]", "scalar", "collector.connections[-1].legacy-timeout", "bad-index",
                "collector.request-timeout", "5s"));
        MigrationInspection result = inspect(List.of(source), List.of(INDEXED));
        assertThat(result.observations()).isEmpty();
        assertThat(result.complete()).isFalse();
        assertThat(MigrationInspector.inspect(result.inspection(), List.of()).observations()).isEmpty();
    }

    @Test
    void rejectsInvalidTemplatesAndReplacementShapes() {
        for (String path : List.of("collector", "Collector.old", "collector.oldTimeout", "collector.items[0].old",
                "collector.labels.*.old", "collector..old", "collector[].old", "collector.old_thing")) {
            assertThatThrownBy(() -> migration(path, Kind.REMOVED, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String replacement : List.of("other.timeout", "collector.legacytimeout", "collector.items[].timeout")) {
            assertThatThrownBy(() -> migration(FIXED.path(), Kind.DEPRECATED, replacement))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> migration(INDEXED.path(), Kind.DEPRECATED, "collector.timeout"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateOverlappingAndChainedCatalogsBeforeAnyObservation() {
        PropertyInspection empty = PropertyInspector.inspect(SCHEMA, List.of());
        for (List<PropertyMigration> catalog : List.of(
                List.of(FIXED, FIXED),
                List.of(FIXED, migration("other.old", Kind.REMOVED, null)),
                List.of(FIXED, migration("collector.legacytimeout", Kind.REMOVED, null)),
                List.of(migration("collector.foo-bar", Kind.REMOVED, null), migration("collector.foo.bar", Kind.REMOVED, null)),
                List.of(INDEXED, migration("collector.connections", Kind.REMOVED, null)),
                List.of(migration("collector.foo", Kind.REMOVED, null), migration("collector.foo.bar", Kind.REMOVED, null)),
                List.of(FIXED, migration("collector.request-timeout", Kind.REMOVED, null)),
                List.of(FIXED, migration("collector.request-timeout", Kind.DEPRECATED, FIXED.path())))) {
            assertThatThrownBy(() -> MigrationInspector.inspect(empty, catalog)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDeclarationsFromAnotherNamespaceInsteadOfReportingNoOldNames() {
        PropertyInspection other = PropertyInspector.inspect(new PropertySchema("other", Collector.class),
                List.of(new MapPropertySource("input", Map.of("other.legacy-timeout", "30s"))));
        assertThatThrownBy(() -> MigrationInspector.inspect(other, List.of(FIXED)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespace");
    }

    @Test
    void copiesObservationCollectionsAndRetainsOriginalSnapshot() {
        PropertyInspection snapshot = PropertyInspector.inspect(SCHEMA,
                List.of(new MapPropertySource("input", Map.of("collector.legacy-timeout", "1s"))));
        MigrationInspection result = MigrationInspector.inspect(snapshot, List.of(FIXED));
        var observation = result.observations().getFirst();
        var matches = new ArrayList<>(observation.matches());
        var copy = new MigrationInspection.Observation(observation.declaration(), matches, observation.resolution());
        matches.clear();
        var observations = new ArrayList<>(List.of(copy));
        var copied = new MigrationInspection(snapshot, observations);
        observations.clear();
        assertThat(copied.inspection()).isSameAs(snapshot);
        assertThat(copied.observations()).containsExactly(observation);
        assertThat(copy.matches()).hasSize(1);
        assertThatThrownBy(() -> copied.observations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copy.matches().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MigrationInspection.Observation(observation.declaration(), List.of(), Resolution.RESOLVED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertAccepted(PropertySource<?> source) {
        MigrationInspection result = inspect(List.of(source), List.of(FIXED));
        assertThat(result.complete()).isTrue();
        assertThat(result.observations()).singleElement().satisfies(o -> {
            assertThat(o.resolution()).isEqualTo(Resolution.RESOLVED);
            assertThat(o.matches().getFirst().replacement()).contains("collector.request-timeout");
        });
        Collector bound = bind(List.of(source));
        assertThat(bound.legacyTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(bound.requestTimeout()).isNull();
    }

    private static MigrationInspection inspect(List<PropertySource<?>> sources, List<PropertyMigration> migrations) {
        return MigrationInspector.inspect(PropertyInspector.inspect(SCHEMA, sources), migrations);
    }

    private static PropertyMigration migration(String path, Kind kind, String replacement) {
        return new PropertyMigration(path, kind, Optional.ofNullable(replacement));
    }

    private static Collector bind(List<PropertySource<?>> sources) {
        return new Binder(ConfigurationPropertySources.from(sources)).bind("collector", Bindable.of(Collector.class)).get();
    }
}
