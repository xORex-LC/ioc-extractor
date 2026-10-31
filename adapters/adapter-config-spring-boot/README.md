# ioc-config-spring-boot

## Purpose and boundary

Name-only inspection of a service-owned record schema and ordered property
declarations, with explicit source/schema coverage and old-name observations.
The library reads no values, chooses no severity or startup policy, creates no
context and registers no Binder aliases. Services own loading, binding, value
validation, reporting and recovery scope.

Published coordinate: `io.github.xorex-lc:ioc-config-spring-boot:0.3.0-rc.1`.
Central and GitHub Packages publication succeeded. Original GitHub CI and
corrected local Central consumers passed; protected Central CI recovery remains
[TOOL-8](../../docs/KNOWN-ISSUES.md). PR 19 integrated the routing/pipefail fixes.
The [release record](../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md)
retains the original failed Central log and immutable release/recovery identity.

Start with the [English guide](../../docs/guides/libraries/config-spring-boot.md)
or [Russian guide](../../docs/guides/ru/libraries/config-spring-boot.md).
The separate [restricted-context recipe](../../docs/guides/libraries/config-spring-boot-check.md)
and [Russian recipe](../../docs/guides/ru/libraries/config-spring-boot-check.md)
show explicit YAML inputs, overrides, binding and semantic validation.
Public/package Javadoc defines exact contracts.

## Public entry points

Six top-level public types live in `com.iocextractor.config.boot`:

| Type | Responsibility |
|---|---|
| `PropertySchema` | Canonical namespace segment and ordinary record root |
| `PropertyInspector` | One traversal of stable sources, highest precedence first |
| `PropertyInspection` | Declarations, safe ordinal/kind references, coverage and schema gaps |
| `PropertyMigration` | Deprecated/removed canonical path and optional replacement |
| `MigrationInspector` | Migration matching from the original snapshot without rereading sources |
| `MigrationInspection` | Original coverage, concrete indexed matches and resolution |

Their eleven nested public records/enums define the result model. `complete()`
measures inspectability, independently of unknown/ambiguous names or valid values.
Declarations include shadowed/null-valued inputs and do not establish final Binder
provenance. Names/map keys and operational exceptions still need safe rendering.

## Structure

| Path | Responsibility |
|---|---|
| `pom.xml` | Required Boot/Core dependencies, parent-independent POM and publication archives |
| `src/main/java/com/iocextractor/config/boot/` | Public contracts, package Javadoc and internal schema/name/migration helpers |
| `src/test/java/com/iocextractor/config/boot/` | Schemas, names, operational failures, immutable results, migrations and real Binder counterexamples |

## Dependencies and IOC integration

Java 21; standalone POM pins Spring Boot 4.0.8 and Spring Core 7.0.9. Context
arrives transitively through Boot. There is no starter, auto-configuration,
SnakeYAML dependency, IOC model, diagnostics or logging provider. The independent
recipe adds Context and SnakeYAML as application-owned dependencies.

Bootstrap calls this adapter explicitly. IOC retains stricter spelling/env
rules, fallback checks for unsupported shapes, report baseline/labels,
controlled failure output, bind/value constraints and migration-window policy.
See [configuration inspection](../../docs/dev/configuration-inspection.md) for
cross-module flow and [ADR 0046](../../docs/ADR/0046-reusable-spring-boot-configuration-inspection.md)
for ownership and compatibility.

## Verification

Run `make test-module MODULE=adapters/adapter-config-spring-boot`. The
[standalone consumer](../../tools/fixtures/configuration-consumer/README.md)
compiles the actual EN/RU API example and context recipe. It qualifies default
and application-BOM graphs, dependency/routing negatives and four artifact
hashes/origins. The [publication guide](../../docs/guides/library-publication.md)
uses the `configuration` selector. These checks do not qualify another Boot
baseline or second deployed service.
