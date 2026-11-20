# Inspect a service's Spring Boot configuration

`ioc-config-spring-boot` checks declared property names against your record schema
and returns immutable observations. Your service decides which inputs to load,
how to bind and validate values, and what an error prevents from starting.

`io.github.xorex-lc:ioc-config-spring-boot:0.3.0-rc.1` is published to Maven Central
and GitHub Packages. The GitHub consumer passed in
[run 40088095516](https://github.com/xORex-LC/ioc-extractor/actions/runs/40088095516).
The Central consumer passed locally after correcting repository routing in the
qualification tool; its original CI failure remains recorded. See the
[publication record](library-publication.md#configuration-library-admission).
Java 21 is required. Central needs no additional repository configuration:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-config-spring-boot</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

The [publication guide](library-publication.md#configuration-library-admission)
describes packaging, repository routing and GitHub authentication. The qualified
compatibility baseline is Spring Boot 4.0.8. Choose dependency versions through
your application's management;
compatibility with another Boot baseline needs qualification.

## Prepare the model and inputs

Define an ordinary record model under one canonical prefix, such as `collector`.
Use `new PropertySchema("collector", Collector.class)` to describe it. Supported
record/container shapes and naming conventions are listed in the
[API reference](../../adapters/adapter-config-spring-boot/README.md#supported-shapes).
Constructors should allow the operator mistakes your validator needs to collect;
the schema describes names, not value constraints.

Choose the source policy before inspecting anything. An effective-deployment
check can use Boot's prepared imports, profiles and environment overrides. A
self-contained check can load one explicit file and literal overrides. Follow
the executable [restricted-context recipe](configuration-library-check.md) for
the second policy, including rejection of imports and placeholders. Selecting
fewer beans does not itself restrict configuration inputs.

Pass stable `PropertySource` instances to
`PropertyInspector.inspect(schema, sources)` in highest-to-lowest precedence
order. Keep the sources stable through binding if the check must describe the
same candidate. You choose when to repeat inspection after a configuration change.

## Decide what the observations mean

Read `inspection.complete()` together with the declarations. Completeness means
the supported names were covered; it does not mean the settings are valid.

| Observation | Meaning | Service action to consider |
|---|---|---|
| `KNOWN` | One supported canonical path | Bind and validate its value |
| `UNKNOWN` | No matching supported path or relevant coverage gap | Report a probable typo or removed setting |
| `AMBIGUOUS` | More than one canonical path matches | Require an unambiguous spelling |
| `UNSUPPORTED`, schema gap or incomplete source | Inspection cannot establish a complete answer | Resolve the gap or explicitly accept partial checking |

For a model with `requestTimeout`, `collector.request-timeout` is a known name
even when its value cannot convert to a duration. `collector.request-timout` is
unknown. An opaque source can hide more declarations, so it prevents a claim that
every key was checked. Enumeration failures propagate as operational failures;
handle them separately from findings about operator-supplied names.

Retain source ordinals/categories if your own report needs source correlation.
The result omits raw source labels and values, but names and map keys may contain
sensitive text. Apply a rendering policy before logging results or exceptions.

## Observe old names

Define `PropertyMigration` descriptors in your service and call
`MigrationInspector.inspect(inspection, migrations)` with the existing snapshot.
Use canonical paths such as `collector.connections[].legacy-timeout` and
`collector.connections[].request-timeout`. A declaration at index `[3]` produces
a replacement hint with `[3]`. The [migration reference](../../adapters/adapter-config-spring-boot/README.md#migration-observations)
defines catalog validation, namespace selection and unsupported templates.

A removed name can produce a resolved migration observation while its original
status remains `UNKNOWN`. Decide whether that becomes a migration-specific error
or a warning. The library does not register aliases, rewrite configuration or
test whether an old value is valid for the replacement.

Seeing old and new names in different sources does not prove both fields are
bound. A higher-priority list can replace the complete lower-priority list.
Check conflicts on the bound model; preserve declaration observations for any
separate policy about forbidden input names.

## Bind, validate and close

After applying your name policy, let Boot bind values and run your service's
semantic validator. Use an explicit set of beans for a check-only context and
close it after both success and failure. Return a detached immutable model or a
structured report rather than a context-owned bean with a resource lifecycle.

Keep error scope in the service: a bad collector configuration may disable only
that collector while unrelated work continues. Startup refusal, diagnostics,
migration deadlines and recovery remain application decisions. The
[closed-input recipe](configuration-library-check.md) supplies a complete compiled
example, including its safe failure codes and context cleanup.

The module's independent collector tests use the real Binder for supported
spellings, conversion failures and list replacement. The release consumers also
compile and run the exact recipe without IOC classes, using separate empty Maven
caches and checking all four artifact hashes and their selected-repository origin.
These fixtures do not qualify a second deployed service. The ownership decision
is recorded in
[ADR 0046](../ADR/0046-reusable-spring-boot-configuration-inspection.md).
