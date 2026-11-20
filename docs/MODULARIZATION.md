# Module map

[Русская версия](ru/MODULARIZATION.md)

The Maven reactor separates domain behavior, application orchestration, shared
technical contracts, technology integrations and runtime composition.
[ARCHITECTURE.md](ARCHITECTURE.md) describes their interaction;
[PRINCIPLES.md](PRINCIPLES.md) guides ownership choices and
[BOUNDARIES.md](BOUNDARIES.md) maps enforcement.

## Reactor topology

The root [pom.xml](../pom.xml) declares 31 projects: one parent/aggregator,
26 functional JARs and four build-only report POMs. Of the JARs, 25 contain
production code and one is reusable test support. Only
[bootstrap/ioc-app](../bootstrap/ioc-app/README.md) is Boot-repackaged and runnable.

The tables are the module responsibility map. Each linked directory README owns
its package/API reference; its POM owns exact direct dependencies.
A dagger (†) identifies one of the six separately published reusable artifacts.

### Platform

| Module | Responsibility and dependency boundary |
|---|---|
| [platform-errors](../platform/platform-errors/README.md) | Product root exception contract; JDK only |
| [platform-diagnostics-api](../platform/platform-diagnostics-api/README.md) † | Generic values, results/policies, bounded collectors and sinks; JDK only |
| [platform-diagnostics](../platform/platform-diagnostics/README.md) | IOC diagnostic catalogs, metadata, rendering and exceptions; depends on generic diagnostics and product errors |
| [platform-etl](../platform/platform-etl/README.md) | Framework-free Pipes-and-Filters runner; diagnostics/error contracts, without domain/application coupling |
| [platform-events](../platform/platform-events/README.md) † | Local publish-only control-event contracts and recording fixture; JDK only |
| [platform-concurrency](../platform/platform-concurrency/README.md) † | Keyed asynchronous serialization and synchronous exclusion; JDK only |
| [platform-logging-slf4j](../platform/platform-logging-slf4j/README.md) † | Typed logs, restoring MDC, scalar ECS profile and explicit masks; SLF4J API, with the provider selected by the consumer |
| [platform-observability](../platform/platform-observability/README.md) | IOC logging catalogs/facades and ETL observer; shared logging, ETL and SLF4J |
| [platform-diagnostics-logging](../platform/platform-diagnostics-logging/README.md) | Service diagnostic-to-logging bridge; IOC diagnostics and observability |

Platform is a set of cohesive subsystems, not a claim that every module is generic
or JDK-only. IOC vocabulary and the current ETL diagnostic/error closure remain
internal; ETL publication is deferred.

### Core

| Module | Responsibility and dependency boundary |
|---|---|
| [ioc-domain](../core/ioc-domain/README.md) | IOC model, refang/extraction, attribution, features and classification contracts; no production dependencies |
| [ioc-processing](../core/ioc-processing/README.md) | IOC views, classification composition, field mapping and invocation-owned semantic reuse; domain and product errors |
| [ioc-application](../core/ioc-application/README.md) | Use cases, inward-facing ports, pipelines, admission/order, canonical/lifecycle/import/export and sync orchestration; domain, processing and framework-free platform contracts |
| [ioc-application-tck](../core/ioc-application-tck/README.md) | Reusable JUnit/AssertJ port contracts; test support, consumed in adapter test scope and excluded from production analysis/coverage denominators |

The domain remains one IOC bounded context. Capability packages and their DAG
provide internal structure without one JAR per capability. Application capabilities
such as lifecycle and Artifact Emission use existing ports/adapters; a separate
business responsibility need not introduce another Maven module.

### Adapters

| Module | Responsibility and integration family |
|---|---|
| [adapter-config-spring-boot](../adapters/adapter-config-spring-boot/README.md) † | Record-schema name/source inspection and migration observations; Spring Boot/Core. IOC schemas, source loading and startup enforcement stay in bootstrap |
| [adapter-events-spring](../adapters/adapter-events-spring/README.md) † | Explicit Spring control-event publication; same-version events core, Spring Context and SLF4J. Service owns dispatch configuration and recovery |
| [adapter-regex-re2j](../adapters/adapter-regex-re2j/README.md) | Domain pattern-engine implementations; RE2/J and JDK regex fallback |
| [adapter-psl](../adapters/adapter-psl/README.md) | Host classification; Guava Public Suffix List |
| [adapter-source-tika](../adapters/adapter-source-tika/README.md) | Document text extraction; Tika parser family |
| [adapter-csv](../adapters/adapter-csv/README.md) | Strict CSV import, artifact preparation/projection and slice output; Commons CSV |
| [adapter-processing-camel](../adapters/adapter-processing-camel/README.md) | Bounded execution of required local processing plans; Camel, without canonical commit authority |
| [adapter-manifest-json-jackson](../adapters/adapter-manifest-json-jackson/README.md) | Immutable slice manifest codec; Jackson |
| [adapter-store-jdbc](../adapters/adapter-store-jdbc/README.md) | Native canonical storage, lifecycle/history, workspaces/receipts, slots, ledgers and migrations; Spring JDBC, SQLite JDBC and HikariCP |
| [adapter-transport-smb](../adapters/adapter-transport-smb/README.md) | Remote sync and managed-import ownership/materialization/disposition/notifications; SMBJ |
| [adapter-ingest](../adapters/adapter-ingest/README.md) | File detection, ownership and bounded daemon dispatch; Spring Integration and filesystem lifecycle |
| [adapter-cli-picocli](../adapters/adapter-cli-picocli/README.md) | Driving CLI, lightweight launch and command/result rendering; picocli and Boot integration |

Each adapter groups one integration family behind the relevant inward-facing
contracts. A tightly coupled family can contain several third-party libraries.
Generic SLF4J logging and reusable Spring adapters retain their explicit technology
dependencies; they do not justify putting those dependencies into the core.

### Composition and build support

| Project/directory | Responsibility |
|---|---|
| [ioc-app](../bootstrap/ioc-app/README.md) | Product composition root, typed configuration/preflight, runtime lifecycle, health and runnable Boot JAR |
| [coverage-report](../build-support/coverage-report/README.md) | Build-only JaCoCo aggregation and integrity over production scopes |
| [spotbugs-report](../build-support/spotbugs-report/README.md) | Build-only raw/filtered SpotBugs aggregate and exact reviewed-finding gate |
| [cpd-report](../build-support/cpd-report/README.md) | Build-only repository-wide source report and duplication-group count ratchet |
| [pmd-report](../build-support/pmd-report/README.md) | Build-only source policy and separate advisory watchlist, with scope/ruleset/report integrity |

[build-quality](../build-support/build-quality/README.md) and
[test-quality](../build-support/test-quality/README.md) are JDK-only verifier
sources and tests, not Maven projects. Root build executions run their checks.
Report POM dependencies order analysis after production projects; they do not
make reports runtime dependencies or publication candidates.

The four reports have their own scope registries. The TCK is intentionally outside
production denominators. [TESTING.md](TESTING.md) and
[build quality](dev/build-quality.md) own lifecycle, coverage and analyzer policy.

## Dependency direction and composition

The main inward chain is bootstrap → adapters → application → processing → domain.
Bootstrap and adapters also depend directly on the inward contracts they need.
Platform subsystems form their own acyclic graph; logging/diagnostic bridges are
outer integration consumers of those contracts.

Maven dependencies determine classpath availability, while ArchUnit and Enforcer
constrain allowed use. A successful module graph alone does not prove that a public
API hides every implementation detail. Use narrow contracts and deliberate Java
visibility; detailed checks are in [BOUNDARIES.md](BOUNDARIES.md).

Spring composes objects in bootstrap. Core use cases do not become Spring services
to obtain injection or transactions; JDBC transaction mechanisms stay in the
storage adapter. Required document and processed-import plans execute through the
Camel adapter. Processing-session ownership stays with the invoking application
workflow; Camel does not own semantic cache identity or canonical recovery.

## Build and publication ownership

The root parent centralizes dependency/plugin management and the shared
`${revision}` development version. Managed inter-module dependencies currently
use `${project.version}`; management supplies versions without adding every
managed dependency to every child classpath.

Six published artifacts use `io.github.xorex-lc`; other reactor modules use
`com.iocextractor`. Java packages retain their documented `com.iocextractor`
names. Publication profiles produce standalone consumer POMs without the private
reactor parent. The [library catalog](guides/libraries/README.md), module POMs and
component release records identify artifact coordinates and qualified releases.

[ADR 0048](ADR/0048-independent-library-versions-and-reuse.md) accepts independent
library versions, with events core/adapter as one exact-version family. POM/CI
activation remains TOOL-9; today's reactor still uses shared versions. Component
tags, immutable signed bundles and isolated repository consumers are separate from
the application release. [RELEASE-PROCESS.md](RELEASE-PROCESS.md) owns release
policy and activation criteria.

A module boundary is not automatically a publication boundary. New extraction
needs a cohesive API, owner, concrete consumer need, acceptable dependency closure
and independent qualification. Extend an admitted library when the responsibility
belongs there; keep service policy local. See the
[development guide](guides/libraries/development.md).

## Changing the module graph

Change a module boundary when ownership, dependency isolation or independent
lifecycle warrants the additional build/public API cost. Preserve supported
contracts and record significant decisions in an ADR; the original granularity
rationale is [ADR 0009](ADR/0009-modularization-granularity.md).

A module addition/removal also updates the root reactor, dependency management,
module references, architecture checks and applicable analyzer/coverage/test
registries in the same change. Generated reports do not replace those source
registries. JPMS, Spring Modulith/canvas and dependency convergence remain deferred;
their absence does not imply failure of the currently adopted checks.
