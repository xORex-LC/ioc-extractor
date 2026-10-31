# Inspect configuration with the Spring Boot configuration library

[Русская версия](../ru/libraries/config-spring-boot.md) · [Library catalog](README.md)

`ioc-config-spring-boot` inspects declared property names against a service's
record model. It reports known, unknown, ambiguous and unsupported names,
source/schema coverage, and observations about deprecated or removed names.
For example, a collector can detect `collector.request-timout` and suggest a
replacement for an old indexed connection setting.

The library reads names, not values. Boot still binds and converts values;
the service validates them and chooses whether a problem disables one
connection, a collector or the whole application. Loading files, choosing source
precedence, preparing imports/profiles, logging and live reload are service work.
The JAR supplies no starter, auto-configuration, context runner or YAML loader.

## Dependency and qualification

Use Java 21. The qualified versions are Spring Boot 4.0.8 and Spring Core 7.0.9:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-config-spring-boot</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

The parent-independent consumer POM declares required Boot and Core compile
dependencies. Boot transitively brings Spring Context and its closure. No IOC,
diagnostics or logging provider is required. Use your application's dependency
management and qualify changes to the tested Boot/Spring baseline. The isolated
consumer checks default mediation and a Boot 4.0.8 application BOM.

The release is published to Central and GitHub Packages. Default Maven Central
requires no extra repository declaration. GitHub reads require authenticated
settings; see the [publication procedure](../library-publication.md#consumption).
The Java implementation reviewed for this guide matches component tag
`ioc-config-spring-boot-v0.3.0-rc.1`; the reactor remains at `0.3.0-SNAPSHOT`.

Qualification has a recorded exception: the original GitHub consumer passed,
while the original Central CI consumer failed and its failure was hidden by a
shell pipeline. Corrected local Central consumers passed with the original
signed bytes. Routing/pipefail fixes were integrated in PR 19; a protected CI
recovery run remains pending. The [permanent release record](../../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md)
preserves both the failure and recovery identities. This documentation task does
not close that separate CI follow-up.

## Public tools and result types

The six top-level public types live in `com.iocextractor.config.boot`.

| Type | Use |
|---|---|
| `PropertySchema` | Select one canonical prefix segment and an ordinary record root |
| `PropertyInspector` | `inspect(schema, sources)` traverses prepared sources once without reading property values |
| `PropertyInspection` | Immutable declarations, source coverage and schema gaps; `complete()` reports inspection coverage |
| `PropertyMigration` | Describe an old path, DEPRECATED/REMOVED kind and optional replacement |
| `MigrationInspector` | `inspect(inspection, migrations)` derives migration observations from that snapshot without revisiting sources |
| `MigrationInspection` | Original snapshot, matching observations and original coverage |

Their eleven nested public types define the data passed to service policy:

| Nested type | Contents |
|---|---|
| `PropertyInspection.Status` | KNOWN, UNKNOWN, AMBIGUOUS, UNSUPPORTED |
| `PropertyInspection.SourceKind` | PROPERTIES, ENVIRONMENT, COMPOSITE, AGGREGATE, OTHER |
| `PropertyInspection.Coverage` | ENUMERATED, EXPANDED, EXCLUDED, INCOMPLETE |
| `PropertyInspection.SourceReference` | Nonnegative encounter ordinal and fixed source kind |
| `PropertyInspection.Declaration` | Raw name, ordered canonical candidates, status and safe source reference |
| `PropertyInspection.SourceCoverage` | Source reference and coverage state |
| `PropertyInspection.SchemaGap` | Canonical path and fixed unsupported-shape reason |
| `PropertyMigration.Kind` | DEPRECATED or REMOVED; descriptive service metadata |
| `MigrationInspection.Resolution` | RESOLVED, AMBIGUOUS or UNSUPPORTED |
| `MigrationInspection.Match` | Descriptor, concrete old path and optional indexed replacement |
| `MigrationInspection.Observation` | Original declaration, nonempty matches and resolution |

## Inspect names, observe a migration and bind values

Put `ConfigurationInspectionExample.java` in `src/main/java/examples/configuration/`
of a Maven project with the dependency above and `maven.compiler.release=21`.
This example inspects a source that rejects all value reads, derives a concrete
migration hint, and then binds an independently prepared value source with Boot.
The second inspection demonstrates an opaque source's incomplete coverage.

```java
package examples.configuration;

import com.iocextractor.config.boot.MigrationInspector;
import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertyMigration;
import com.iocextractor.config.boot.PropertySchema;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConfigurationInspectionExample {
    public record Collector(Duration requestTimeout, List<Connection> connections) { }
    public record Connection(String name) { }

    public static void main(String[] args) {
        var schema = new PropertySchema("collector", Collector.class);
        var names = new EnumerablePropertySource<Object>("private-source-label", new Object()) {
            @Override public String[] getPropertyNames() {
                return new String[] {"collector.requestTimeout", "collector.request-timout",
                        "collector.connections[3].legacy-name", "another-service.secret"};
            }
            @Override public Object getProperty(String name) {
                throw new AssertionError("Name inspection must not read values");
            }
        };
        var inspection = PropertyInspector.inspect(schema, List.of(names));
        long unknown = inspection.declarations().stream()
                .filter(d -> d.status() == PropertyInspection.Status.UNKNOWN).count();
        var migrations = MigrationInspector.inspect(inspection, List.of(new PropertyMigration(
                "collector.connections[].legacy-name", PropertyMigration.Kind.REMOVED,
                Optional.of("collector.connections[].name"))));
        var observation = migrations.observations().getFirst();
        String replacement = observation.matches().getFirst().replacement().orElseThrow();

        var values = new MapPropertySource("explicit-candidate", Map.of(
                "collector.request-timeout", "5s", "collector.connections[0].name", "primary"));
        Collector bound = new Binder(ConfigurationPropertySources.from(values))
                .bind("collector", Bindable.of(Collector.class)).get();
        var opaque = new PropertySource<Object>("opaque-source", new Object()) {
            @Override public Object getProperty(String name) { return null; }
        };
        boolean opaqueComplete = PropertyInspector.inspect(schema, List.of(opaque)).complete();
        if (!inspection.complete() || inspection.declarations().size() != 3 || unknown != 2
                || observation.declaration().status() != PropertyInspection.Status.UNKNOWN
                || !replacement.equals("collector.connections[3].name")
                || !bound.requestTimeout().equals(Duration.ofSeconds(5)) || opaqueComplete
                || inspection.toString().contains("private-source-label")) {
            throw new IllegalStateException("Inspection, migration or binding differs");
        }
        System.out.printf("Names: %d; unknown: %d; replacement: %s%n",
                inspection.declarations().size(), unknown, replacement);
        System.out.println("Bound timeout: " + bound.requestTimeout()
                + "; opaque source complete: " + opaqueComplete);
    }
}
```

Expected output:

```text
Names: 3; unknown: 2; replacement: collector.connections[3].name
Bound timeout: PT5S; opaque source complete: false
```

The unknown typo does not make `complete()` false: all relevant names and schema
paths were inspectable. It still needs service enforcement. Conversely, an opaque
source prevents complete coverage even if no unknown declaration was returned.

## Supported schemas and names

`PropertySchema` requires one canonical Boot namespace segment, such as
`collector`, and a record root. Nested unsupported shapes become schema gaps
even if the input contains no declaration for them.

| Qualified shape | Rules |
|---|---|
| Ordinary nested records | ASCII lower camel case fields without aliases or consecutive uppercase letters |
| Scalars | Primitive/enum/String/number/Boolean/Character; common time, path, URI/URL, address, locale, charset, UUID, date and Class shapes listed by the schema implementation |
| `List<T>` | Resolvable record/scalar elements; numeric indexes for record lists; whole scalar-list assignment is supported |
| `Map<K,V>` | Flat scalar values with String/enum keys and a single-token dynamic key |

JavaBeans, sets, arrays, recursive/unresolved types, custom conversion shapes,
`@Name` aliases, unqualified field names and nested maps report unsupported
coverage. Dotted or special-character map keys are outside the qualified grammar.
Record-as-scalar and whole scalar assignment to a record list are unsupported.
Name recognition does not validate enum-map keys, conversion, index continuity
or whether a particular supplied value can bind.

Canonical `collector.request-timeout`, camel `collector.requestTimeout` and
dashless `collector.requesttimeout` spellings are recognized. Qualified
environment sources also recognize `COLLECTOR_REQUESTTIMEOUT` and
`COLLECTOR_REQUEST_TIMEOUT`. If a spelling fits more than one record path,
all candidates are retained as AMBIGUOUS; the library does not select a winner.

## Choose sources before inspection

Pass a stable sequence of `PropertySource<?>` objects in highest-to-lowest
precedence order. Composite children preserve their order and get their own
ordinals. Names are sorted within each enumerable source; declarations from
other namespaces are omitted. Lower-priority, shadowed and null-valued declarations
remain present. Keep the sources stable through binding if both operations must
describe the same candidate.

| Source | Inspection behavior |
|---|---|
| Ordinary enumerable source | PROPERTIES / ENUMERATED |
| `SystemEnvironmentPropertySource` named `systemEnvironment` or ending in `-systemEnvironment` | Qualified ENVIRONMENT / ENUMERATED |
| Other environment-source name | OTHER / INCOMPLETE; matching declarations are UNSUPPORTED |
| Composite source | COMPOSITE / EXPANDED; children visited separately |
| Boot's attached `configurationProperties` aggregate | AGGREGATE / EXCLUDED to avoid duplicate observations |
| Nonenumerable source | OTHER / INCOMPLETE |

Enumeration failures propagate with the original exception identity. They are
operational failures, not unknown-name findings; no partial result is returned.
The result retains neither source objects nor arbitrary source labels, but raw
property names and map keys can themselves contain secrets. Apply your own
rendering policy to names, results and exceptions.

## Interpret findings and coverage separately

| Finding | Meaning | Service action |
|---|---|---|
| KNOWN | One supported canonical path | Bind and validate the value |
| UNKNOWN | No supported match or relevant unsupported subtree | Report a typo or removed key under your policy |
| AMBIGUOUS | Several canonical paths match | Require an unambiguous spelling |
| UNSUPPORTED, schema gap or incomplete source | Inspection cannot establish a full answer | Resolve the gap or explicitly accept partial checking |

`complete()` is true only without schema gaps, INCOMPLETE sources or UNSUPPORTED
declarations. UNKNOWN/AMBIGUOUS findings do not reduce coverage. A known name can
still have an invalid duration, missing required value or semantic conflict.
Neither completeness nor a resolved migration observation authorizes startup.

Declarations are not final Binder provenance. A higher-priority source defining
a list replaces the whole list, including its elements. A partial override of
`collector.connections[0].name` can drop that element's other fields and all
lower-priority elements. Map aggregation has different rules. Validate conflicts
on the bound model; keep declaration observations for separate input-name policy.

## Describe old names without rewriting configuration

Use canonical old paths such as `collector.connections[].legacy-timeout` and an
optional replacement such as `collector.connections[].request-timeout`.
`PropertyMigration.Kind.DEPRECATED` describes a service compatibility window;
REMOVED also supports names absent from the current record model. Both are
metadata. The library registers no Binder alias, conversion or deadline policy.

`MigrationInspector.inspect` uses the existing snapshot once, keeps its coverage
and does not read sources again. A removed name stays UNKNOWN in that snapshot
even when its migration observation is RESOLVED. Concrete list indexes are copied
in order into replacement placeholders. A replacement hint does not prove that
the old value can bind to the new field or that the target exists in the schema.

Descriptors must share the inspected namespace. Templates accept canonical
record paths and `[]` numeric-list placeholders, not literal indexes, map-entry
wildcards or regex callbacks. Replacements must change the path within that
namespace and preserve the number of placeholders. Overlapping/equivalent old
paths and migration chains are rejected before matching. For an empty snapshot,
namespace identity cannot be established; the service must select the correct
catalog. Several interpretations remain AMBIGUOUS rather than being guessed.

Retaining old and new declarations does not establish a bound-value conflict:
one may be shadowed or belong to a replaced list. A resolved observation in a
partial inspection does not prove the absence of old names in opaque sources.

## Binding, context ownership and errors

After name policy, let Boot bind values and run service-owned semantic validation.
Keep record constructors permissive enough to collect operator errors. To check
one explicit YAML document, use the [restricted-context recipe](config-spring-boot-check.md).
To check effective deployment configuration, prepare Boot's actual imports,
profiles and overrides. Limiting beans alone does not limit configuration inputs.

Inspectors have no mutable per-call state. Callers own source stability and
must not mutate them during traversal. Result lists/candidate sets are copied;
snapshot records are observations, not a certificate for hand-constructed data.
No context, file, worker or transaction is created or closed by the inspectors.
The recipe closes its short-lived context and returns a detached immutable model.

- Null required arguments/metadata/lists cause `NullPointerException`.
- Invalid namespace/root or migration templates/catalogs cause
  `IllegalArgumentException`; unsupported nested shapes instead report coverage gaps.
- Invalid source ordinals and migration observations with no matches are rejected.
- Enumeration, Binder and semantic failures belong to separate service handling.
  Do not print arbitrary parser/converter exception text as a safe error report.

## Sources, tests and maintenance

- [Module reference](../../../adapters/adapter-config-spring-boot/README.md) and
  [source/package Javadoc](../../../adapters/adapter-config-spring-boot/src/main/java/com/iocextractor/config/boot/)
  define the exact public contracts. Internal `SchemaModel`, `NameMatcher` and
  `MigrationPath` are implementation details.
- [Module tests](../../../adapters/adapter-config-spring-boot/src/test/java/com/iocextractor/config/boot/)
  cover names, schemas, ambiguity, snapshots, migrations, operational failures,
  real Binder conversion and whole-list replacement. Run
  `make test-module MODULE=adapters/adapter-config-spring-boot`.
- [Standalone consumer](../../../tools/fixtures/configuration-consumer/README.md)
  compiles this actual Java example and the restricted-context recipe, under
  default and application-BOM mediation, with dependency/routing negatives.
  EN/RU Java/XML/YAML parity and extraction are checked by
  [publication contracts](../../../tools/tests/library-publication-test.py):
  `make library-publication-test`. Run `make docs` for links.
- [ADR 0046](../../ADR/0046-reusable-spring-boot-configuration-inspection.md)
  records ownership; [configuration inspection](../../dev/configuration-inspection.md)
  explains IOC integration. [TOOL-8](../../KNOWN-ISSUES.md)
  retains the pending protected Central consumer recovery.

Product/library versions remain lockstep. Another deployed service, another Boot
baseline and a generic live-reload mechanism remain outside qualification. Update
both languages, code examples and the recipe when these public behaviors change.
