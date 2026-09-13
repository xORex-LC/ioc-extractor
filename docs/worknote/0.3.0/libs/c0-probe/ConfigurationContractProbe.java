package com.iocextractor.bootstrap;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Isolated synthetic characterization, not a product test or future service schema. */
public final class ConfigurationContractProbe {
    static Class<?> schemaType = Schema.class;

    public record Connection(String name, Duration requestTimeout) { }
    public record Schema(Boolean enabled, Duration requestTimeout, Connection primary,
                         List<Connection> connections, List<String> tags, Map<String, String> labels) { }
    public record Foo(String bar) { }
    public record Ambiguous(String fooBar, Foo foo) { }
    public record NestedMap(Map<String, Connection> targets) { }
    public record Collections(Set<String> tags, String[] names) { }
    public record Alias(@Name("remote-name") String displayName) { }
    public record Acronym(String URLValue) { }
    public record Token(String value) { }
    public record Converted(Token token) { }
    public enum Region { EAST, WEST }
    public record EnumMap(Map<Region, String> labels) { }
    public record BeanRoot(MutableChild child) { }
    public static final class MutableChild {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @Override public String toString() { return "MutableChild[name=" + name + "]"; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("case\tpreflight\tbound\treported declarations");
        row("canonical", map("input", "collector.enabled", "true", "collector.request-timeout", "30s"));
        row("camel", map("input", "collector.requestTimeout", "30s"));
        row("dashless", map("input", "collector.requesttimeout", "30s"));
        row("unknown", map("input", "collector.enabled", "true", "collector.request-timout", "30s"));
        row("invalid-value", map("input", "collector.request-timeout", "not-a-duration"));
        row("nested", map("input", "collector.primary.name", "alpha", "collector.primary.request-timeout", "30s"));
        row("record-as-scalar", map("input", "collector.primary", "alpha"));
        row("list-record", map("input", "collector.connections[0].name", "alpha",
                "collector.connections[0].request-timeout", "30s"));
        row("list-whole", map("input", "collector.tags", "a,b"));
        row("list-indexed", map("input", "collector.tags[0]", "a", "collector.tags[1]", "b"));
        row("list-hole", map("input", "collector.tags[1]", "b"));
        row("negative-index", map("input", "collector.tags[-1]", "b"));
        row("index-trailing-junk", map("input", "collector.tags[0]junk", "b"));
        row("flat-map", map("input", "collector.labels.region", "east"));
        row("dotted-map-key", map("input", "collector.labels.zone.name", "east"));
        row("bracket-map-key", map("input", "collector.labels[zone.name]", "east"));
        row("env-simple", env("COLLECTOR_ENABLED", "true"));
        row("env-compact", env("COLLECTOR_REQUESTTIMEOUT", "30s"));
        row("env-split", env("COLLECTOR_REQUEST_TIMEOUT", "30s"));
        row("env-whole-list", env("COLLECTOR_TAGS", "a,b"));
        row("env-list-record", env("COLLECTOR_CONNECTIONS_0_NAME", "alpha",
                "COLLECTOR_CONNECTIONS_0_REQUEST_TIMEOUT", "30s"));
        row("env-list-record-compact", env("COLLECTOR_CONNECTIONS_0_NAME", "alpha",
                "COLLECTOR_CONNECTIONS_0_REQUESTTIMEOUT", "30s"));
        row("env-map", env("COLLECTOR_LABELS_REGION", "east"));
        row("env-custom-source-name", new SystemEnvironmentPropertySource("custom-env",
                values("COLLECTOR_REQUESTTIMEOUT", "30s")));
        row("env-suffixed-source-name", new SystemEnvironmentPropertySource("probe-systemEnvironment",
                values("COLLECTOR_REQUESTTIMEOUT", "30s")));
        row("duplicate-spellings", map("input", "collector.request-timeout", "10s",
                "collector.requestTimeout", "20s"));
        row("env-bare-root", env("COLLECTOR", "unused"));
        row("other-prefix", map("input", "elsewhere.enabled", "true"));
        row("cli-canonical", new SimpleCommandLinePropertySource("--collector.request-timeout=30s"));
        row("yaml", new YamlPropertySourceLoader().load("yaml", new ByteArrayResource(("""
                collector:
                  enabled: true
                  connections:
                    - name: alpha
                      request-timeout: 30s
                """).getBytes(StandardCharsets.UTF_8))).getFirst());
        row("list-partial-overlay", map("commandLineArgs", "collector.connections[0].name", "new"),
                map("file [low.yml]", "collector.connections[0].name", "old",
                        "collector.connections[0].request-timeout", "10s", "collector.connections[1].name", "tail"));
        row("list-whole-overlay", map("commandLineArgs", "collector.tags", "new"),
                map("file [low.yml]", "collector.tags[0]", "old", "collector.tags[1]", "tail"));
        row("high-baseline", map("class path resource [defaults.yml]", "collector.enabled", "true"),
                map("file [low.yml]", "collector.enabled", "false"));
        row("null-high", map("commandLineArgs", "collector.enabled", null),
                map("file [low.yml]", "collector.enabled", "true"));
        row("shadowed-unknown", map("commandLineArgs", "collector.enabled", "true"),
                map("file [low.yml]", "collector.unknown", "x"));
        PropertySource<?> opaque = new PropertySource<Object>("opaque") {
            @Override public Object getProperty(String name) {
                return "collector.enabled".equals(name) ? "true" : null;
            }
        };
        row("opaque-source", opaque, map("file [low.yml]", "collector.enabled", "false"));
        CompositePropertySource composite = new CompositePropertySource("composite");
        composite.addPropertySource(map("inner", "collector.enabled", "true", "collector.unknown", "x"));
        row("enumerable-composite", composite);
        CompositePropertySource mixed = new CompositePropertySource("mixed");
        mixed.addPropertySource(opaque);
        mixed.addPropertySource(map("inner", "collector.enabled", "false"));
        row("opaque-composite", mixed);
        row("source-name-secret", map("synthetic-secret-label", "collector.enabled", "true"));
        row("alias", Alias.class, map("input", "collector.remote-name", "alpha"));
        row("alias-java-name", Alias.class, map("input", "collector.display-name", "alpha"));
        row("nested-map", NestedMap.class, map("input", "collector.targets.alpha.name", "alpha"));
        row("bean-child", BeanRoot.class, map("input", "collector.child.name", "alpha"));
        row("indexed-set", Collections.class, map("input", "collector.tags[0]", "a"));
        row("indexed-array", Collections.class, map("input", "collector.names[0]", "a"));
        row("acronym", Acronym.class, map("input", "collector.url-value", "alpha"));
        row("enum-map", EnumMap.class, map("input", "collector.labels.east", "alpha"));
        row("enum-map-invalid-key", EnumMap.class, map("input", "collector.labels.invalid", "alpha"));
        row("converted-record", Converted.class, map("input", "collector.token", "alpha"));
        row("converted-record-env", Converted.class, env("COLLECTOR_TOKEN", "alpha"));
        row("env-ambiguity", Ambiguous.class, env("COLLECTOR_FOO_BAR", "alpha"));
        row("env-ambiguity-with-unknown", Ambiguous.class,
                env("COLLECTOR_UNKNOWN", "x", "COLLECTOR_FOO_BAR", "alpha"));
        noValueReads();
        schemaType = Schema.class;
        var match = new IocEnvironmentPropertyMatcher().match("COLLECTOR_ENABLED");
        match.canonicalNames().clear();
        System.out.println("mutable-match-result\tknown-after-clear=" + match.isKnown() + "\tNOT_RUN\tNOT_RUN");
    }

    private static void row(String id, PropertySource<?>... sources) {
        row(id, Schema.class, sources);
    }

    private static void row(String id, Class<?> type, PropertySource<?>... sources) {
        schemaType = type;
        StandardEnvironment environment = environment(sources);
        String preflight = preflight(environment);
        String bound;
        try {
            var conversion = new ApplicationConversionService();
            conversion.addConverter(String.class, Token.class, Token::new);
            var result = new Binder(ConfigurationPropertySources.from(List.of(sources)), null, conversion)
                    .bind("collector", Bindable.of(type));
            Object value = result.isBound() ? result.get() : null;
            if (value instanceof Collections collection) {
                bound = "Collections[tags=" + collection.tags() + ", names="
                        + java.util.Arrays.toString(collection.names()) + "]";
            } else {
                bound = value == null ? "UNBOUND" : value.toString();
            }
        } catch (RuntimeException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null) { cause = cause.getCause(); }
            bound = "FAIL:" + cause.getClass().getSimpleName();
        }
        String report;
        try {
            report = new IocConfigurationOverrideReporter(environment)
                    .effectiveOverrides(environment.getPropertySources()).toString();
        } catch (RuntimeException ex) {
            report = "FAIL:" + ex.getClass().getSimpleName();
        }
        System.out.println(id + "\t" + preflight + "\t" + bound + "\t" + report);
    }

    private static String preflight(StandardEnvironment environment) {
        try {
            new IocUnknownConfigurationPreflight(environment).postProcessBeanFactory(null);
            return "ACCEPT";
        } catch (UnboundConfigurationPropertiesException ex) {
            return "UNKNOWN:" + ex.getUnboundProperties().stream()
                    .map(property -> property.getName().toString()).sorted().toList();
        } catch (IllegalStateException ex) {
            return ex.getMessage().startsWith("Ambiguous IOC environment")
                    ? "AMBIGUOUS" : "FAIL:" + ex.getClass().getSimpleName();
        } catch (RuntimeException ex) {
            return "FAIL:" + ex.getClass().getSimpleName();
        }
    }

    private static StandardEnvironment environment(PropertySource<?>... sources) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (var source : sources) { environment.getPropertySources().addLast(source); }
        return environment;
    }

    private static MapPropertySource map(String name, String... pairs) {
        return new MapPropertySource(name, values(pairs));
    }

    private static SystemEnvironmentPropertySource env(String... pairs) {
        return new SystemEnvironmentPropertySource("systemEnvironment", values(pairs));
    }

    private static Map<String, Object> values(String... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { values.put(pairs[i], pairs[i + 1]); }
        return values;
    }

    private static void noValueReads() {
        schemaType = Schema.class;
        var source = new EnumerablePropertySource<Object>("names-only") {
            @Override public String[] getPropertyNames() { return new String[] {"collector.enabled", "collector.unknown"}; }
            @Override public Object getProperty(String name) { throw new AssertionError("Unexpected value read"); }
        };
        var environment = environment(source);
        String inspected = preflight(environment);
        var report = new IocConfigurationOverrideReporter(environment).effectiveOverrides(environment.getPropertySources());
        System.out.println("no-value-reads\t" + inspected + "\tNOT_RUN\t" + report);
    }
}
