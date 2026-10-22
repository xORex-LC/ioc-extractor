# Check configuration in a restricted Spring context

A service can reuse `ioc-config-spring-boot` to inspect property names and then
bind and validate its own model in a small Spring context. The service chooses
both the beans and the configuration sources. Restricting beans alone does not
restrict imports, profiles or environment overrides.

This recipe is application code, qualified with Java 21 and Spring Boot 4.0.8.
The configuration library is still a reactor dependency at `0.3.0-SNAPSHOT`;
published-artifact consumption remains a separate release step. See the
[API reference](../../adapters/adapter-config-spring-boot/README.md) for supported
schemas and the [inspection flow](../dev/configuration-inspection.md) for ownership.

## Choose a source policy

| Policy | Inputs | Imports and profiles | Intended use |
|---|---|---|---|
| Effective Boot configuration | Application defaults, candidate YAML and ordinary Boot overrides | Boot loads imports and active profile documents/files, including transitive imports | Check what a particular deployment will use |
| Closed inputs, shown below | One explicit YAML document and an explicit map of literal overrides | Rejected; no ConfigData processing | Check a self-contained candidate independently of the host environment |

The IOC `--ioc.validate-config=PATH` command uses the first policy. It loads
classpath `application.yml` (including its optional `./configs/application.yml`
import), then the candidate, with Boot's environment, system, JSON and command
line overrides. Imports can load other locations and fail independently of the
candidate. A missing optional import is allowed; a missing required import fails.
Import locations are trusted deployment inputs: this command is not a path or
network sandbox. See [Boot external configuration](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html)
for precedence and import resolution.

IOC owns the command's root locations and rejects CLI changes to
`spring.config.location`, `additional-location`, `import` and `name`, including
indexed and relaxed spellings. It rejects scalar or indexed `spring.main.sources`
from every loaded channel before additional bean sources can be instantiated.
It pins eager initialization, disables keep-alive and shutdown-hook registration,
and closes the explicit configuration context on success and failure. Runtime
storage, schedulers and transports are outside that context. The command's
temporary process-console suppression remains an IOC command detail; do not use
it as a shared embedded runner.

## Dependencies

Use the consuming application's Boot BOM. Add `io.github.xorex-lc:ioc-config-spring-boot`
at the matching library version, plus direct application dependencies on
`org.springframework:spring-context` and `org.yaml:snakeyaml` for this recipe.
The shared library itself does not require SnakeYAML, a starter, IOC models,
Bean Validation or an auto-configuration module. This example supplies its own
Spring `Validator`; services may choose Bean Validation instead.

## Implement the closed-input check

Save the following as `examples/configuration/CollectorConfigurationCheck.java`.
It accepts only `collector.*` settings, one YAML document, and literal strings.
It rejects `${...}` placeholders rather than resolving undeclared dependencies.
Neither a classpath `application.yml` nor an environment variable, system
property or `SPRING_APPLICATION_JSON` participates. Every declaration is checked,
including lower-priority keys shadowed by an override.

```java
package examples.configuration;

import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertySchema;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checks one explicit collector document without starting the collector runtime. */
public final class CollectorConfigurationCheck {
    private CollectorConfigurationCheck() { }

    /** Returns the bound model; failures carry a fixed code, never input values or parser text. */
    public static Collector check(Path candidate, Map<String, String> overrides) {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load("candidate", new FileSystemResource(candidate));
        } catch (IOException | RuntimeException failure) {
            // Do not attach the loader's exception: its message can contain document values.
            throw new CheckFailure(Code.INPUT_INVALID);
        }
        if (documents.size() != 1) {
            throw new CheckFailure(Code.INPUT_POLICY);
        }
        sources.addLast(documents.getFirst());
        sources.addFirst(new MapPropertySource("explicitOverrides", new LinkedHashMap<>(overrides)));
        for (var source : sources) {
            var enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                Object value = enumerable.getProperty(name);
                if (!(name.equals("collector") || name.startsWith("collector."))
                        || value instanceof String text && text.contains("${")) {
                    throw new CheckFailure(Code.INPUT_POLICY);
                }
            }
        }
        var inspection = PropertyInspector.inspect(new PropertySchema("collector", Collector.class), sources);
        if (!inspection.complete() || inspection.declarations().stream()
                .anyMatch(declaration -> declaration.status() != PropertyInspection.Status.KNOWN)) {
            throw new CheckFailure(Code.NAME_CHECK);
        }
        // Creating the scope before refresh also closes beans created during a failed refresh.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(CheckConfiguration.class);
            context.refresh();
            return context.getBean(Collector.class);
        } catch (RuntimeException failure) {
            throw new CheckFailure(Code.BINDING_OR_VALIDATION);
        }
    }

    /** Immutable settings returned after the short-lived context has closed. */
    @ConfigurationProperties("collector")
    public record Collector(Duration requestTimeout, List<Connection> connections) {
        public Collector {
            connections = connections == null ? List.of() : List.copyOf(connections);
        }
    }

    /** One configured connection; validation, not its constructor, rejects operator mistakes. */
    public record Connection(String name, Boolean enabled) { }

    /** Coarse application-owned outcomes; callers decide output, exit codes and recovery. */
    public enum Code { INPUT_POLICY, INPUT_INVALID, NAME_CHECK, BINDING_OR_VALIDATION }

    /** Contains no underlying throwable or rejected input. */
    public static final class CheckFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Code code;

        private CheckFailure(Code code) {
            super(code.name());
            this.code = code;
        }

        public Code code() { return code; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Collector.class)
    static class CheckConfiguration {
        @Bean(name = EnableConfigurationProperties.VALIDATOR_BEAN_NAME)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static Validator configurationPropertiesValidator() {
            return new Validator() {
                @Override
                public boolean supports(Class<?> type) { return type == Collector.class; }

                @Override
                public void validate(Object target, Errors errors) {
                    var collector = (Collector) target;
                    if (collector.requestTimeout() == null || collector.requestTimeout().isNegative()
                            || collector.requestTimeout().isZero()) {
                        errors.rejectValue("requestTimeout", "collector.timeout.positive");
                    }
                    if (collector.connections().isEmpty()) {
                        errors.rejectValue("connections", "collector.connections.required");
                    }
                    for (int index = 0; index < collector.connections().size(); index++) {
                        var connection = collector.connections().get(index);
                        if (connection.name() == null || connection.name().isBlank()) {
                            errors.rejectValue("connections[" + index + "].name", "collector.connection.name");
                        }
                        if (connection.enabled() == null) {
                            errors.rejectValue("connections[" + index + "].enabled", "collector.connection.enabled");
                        }
                    }
                }
            };
        }
    }
}
```

No `SpringApplication`, component scan, auto-configuration or ConfigData resolver
runs here. Only the explicit property model and validation infrastructure are
registered. The YAML loader parses data; a `spring.config.import` declaration is
rejected before refresh and never followed. Rejecting the entire `spring.*`
namespace also excludes profile selectors and extra bean sources.

Use this document as `collector.yml`:

```yaml
collector:
  request-timeout: 5s
  connections:
    - name: primary
      enabled: true
    - name: fallback
      enabled: false
```

Call `CollectorConfigurationCheck.check(Path.of("collector.yml"), Map.of())`.
An explicit `Map.of("collector.request-timeout", "2s")` overrides that scalar.
List binding follows Boot rules: supplying only
`collector.connections[0].name` in the override map replaces the **whole list**;
it does not retain `enabled` or the fallback entry. The validator rejects that
partial replacement. Supply every field of every desired replacement element.
Map aggregation follows separate Boot rules; do not infer list provenance from
a declaration report.

The result contains only the immutable model. It retains no context, environment,
source or loader. This example has no owned workers, database or transport.
If a service later adds resource-owning validation beans, they need close hooks
and tests for both successful and failed refresh. Runtime initialization belongs
outside the check configuration.

`CheckFailure` deliberately provides only fixed categories. A service can add
structured safe field paths and migration hints; it must choose those explicitly
rather than print a binding exception or validation message containing input.
Spring/provider logging is a separate output boundary: configure it in the
consuming application. This example does not mutate process-wide streams and
does not promise that arbitrary framework logging is value-free.

## Evidence and limits

`ConfigurationCheckRecipeIT` compiles this exact Java block and runs it in a
bounded separate JVM with the configuration library and third-party JARs only.
It checks literal overrides, whole-list replacement, unknown names, source and
placeholder rejection, input/semantic failures, ambient-input isolation and the
absence of runtime component scanning. `IocConfigurationContextIT` separately
checks effective Boot loading, source rejection, actual IOC aggregate binding
and command termination. These are reactor integration checks, not a cold
repository consumer or a second deployed service.
