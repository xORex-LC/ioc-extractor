# adapters/adapter-config-spring-boot

## Purpose and boundary

Reusable `io.github.xorex-lc:ioc-config-spring-boot` library for name-only
inspection of a service-owned record schema and ordered Spring property sources.
It returns declaration, coverage and old-name observations. The service owns
source loading, binding, value validation, diagnostic severity, rendering,
compatibility windows and startup enforcement.

The library creates no Spring context, registers no beans or Binder aliases and
reads no property values. It has no IOC model, diagnostics or logging dependency.
IOC bootstrap calls it explicitly; domain, processing and application modules
must not depend on this Spring adapter.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Reactor identity, required dependencies, flattened consumer POM and publication archives |
| [Production package](src/main/java/com/iocextractor/config/boot/README.md) | Public API, result model, schema/name matching and migration helpers |
| [Test package](src/test/java/com/iocextractor/config/boot/README.md) | Synthetic schemas/sources, real Binder comparisons and failure/ownership contracts |

There is one production package and one matching test package. Test inputs are
inline synthetic records/maps; there is no module-local test-resource tree.
The POM copies the repository LICENSE into `META-INF`; generated consumer POMs
and reports belong under `target/`.

## Dependencies

The module directly requires `spring-boot` and `spring-core`; versions are managed
by the parent and resolved into the parent-independent publication POM.
The documented standalone baseline is Java 21, Boot 4.0.8 and Core 7.0.9.
Context arrives transitively through Boot. This is a plain library, with no
starter or auto-configuration registration and no direct SnakeYAML dependency.
The context recipe's application owns its explicit Context/YAML dependencies.

Keep service-specific configuration records, converters, source display labels,
IOC spellings/env restrictions and diagnostic codes in bootstrap. Internal
reflection and matcher helpers remain package-private. Adding a dependency or
exposing a helper requires review of the standalone consumer graph and API
boundary, as well as the reactor graph.

## Entry points and contracts

The six top-level public types are `PropertySchema`, `PropertyInspector`,
`PropertyInspection`, `PropertyMigration`, `MigrationInspector` and
`MigrationInspection`; their eleven nested public records/enums define result
facts. The production package maps each type to its implementation and tests.

Call name inspection on a stable, highest-precedence-first source sequence.
Then match explicit migration descriptors against that immutable result without
rereading sources. `complete()` describes inspectability, independently of unknown
or ambiguous names, required values and successful binding. Shadowed and
null-valued declarations are retained; they do not establish Binder provenance.
Raw names/map keys and propagated source exceptions still need safe rendering.

IOC's stricter preflight, compatibility fallback and override-report policy remain
service-owned. See [configuration inspection](../../docs/dev/configuration-inspection.md) and [ADR 0046](../../docs/ADR/0046-reusable-spring-boot-configuration-inspection.md).
The [consumer guide](../../docs/guides/libraries/config-spring-boot.md) ([RU](../../docs/guides/ru/libraries/config-spring-boot.md))
contains API examples; the [restricted-context recipe](../../docs/guides/libraries/config-spring-boot-check.md) ([RU](../../docs/guides/ru/libraries/config-spring-boot-check.md))
explains application-owned loading, binding and semantic validation.

## Verification and publication

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-config-spring-boot
```

All four local suites run through Surefire. `make verify` is the full reactor
gate. The `library-publication` profile adds source and strict Javadoc archives;
flattening already produces `target/publication.pom` during normal packaging.
Packaging the profile does not itself publish a release.

The separate [standalone consumer](../../tools/fixtures/configuration-consumer/README.md)
checks packaged API/recipe examples, default and application-BOM graphs, routing
negatives and artifact origins. Run it through the `configuration` selector in
the [publication guide](../../docs/guides/library-publication.md); it requires a prepared bundle.
Reactor tests alone do not qualify publication, another Boot baseline or a second
deployed service.

The published `0.3.0-rc.1` has a retained [release record](../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md).
Central and GitHub Packages publication succeeded; corrected local Central
consumption does not close the protected CI recovery follow-up
[TOOL-8](../../docs/KNOWN-ISSUES.md). Current source/version authority remains the POM,
not the historical published coordinate.
