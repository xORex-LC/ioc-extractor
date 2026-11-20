# Standalone library consumers

## Purpose and structure

These seven Maven projects exercise six reusable libraries from packaged JARs.
They have no IOC parent, are not root reactor modules and must not use reactor
class directories or the developer's installed artifacts. The registry in
[publication tooling](../ci/library-publication.py) selects the component,
fixtures and exact published dependency contract.

| Fixture | Component selector | Responsibility |
|---|---|---|
| [library-consumer](library-consumer/README.md) | `concurrency` | JDK keyed guard/executor and exact guide examples |
| [diagnostics-consumer](diagnostics-consumer/README.md) | `diagnostics` | JDK results, bounded counts and sink failure examples |
| [logging-consumer](logging-consumer/README.md) | `logging` | Typed SLF4J records/MDC with a consumer-owned Logback provider |
| [logging-boot-consumer](logging-boot-consumer/README.md) | `logging` | Actual application-owned Boot encoder/configuration recipe and emitted bytes |
| [configuration-consumer](configuration-consumer/README.md) | `configuration` | Inspection/migration API, closed-input recipe and dependency/routing checks |
| [events-consumer](events-consumer/README.md) | `events` | JDK control-event metadata/no-ops/recording and routing checks |
| [events-spring-consumer](events-spring-consumer/README.md) | `events-spring` | Exact core/adapter pair, real Spring dispatch and dependency/routing checks |

Each fixture owns a `pom.xml` and independent `LibraryConsumer.java`. Concurrency
and diagnostics compile from the project root; the others use `src/main/java`.
Guide classes and recipe resources are supplied at execution time; direct Maven
execution of an unprepared checkout fixture is unsupported.

## Preparation, dependencies and execution

From the repository root, for an already prepared matching bundle:

```bash
make library-consumer LIBRARY_COMPONENT=diagnostics LIBRARY_BUNDLE=.dev/diagnostics-bundle
```

`LIBRARY_REPOSITORY` defaults to `local`; `central` and `github` select published
bytes. `events-spring` also requires `LIBRARY_DEPENDENCY_BUNDLE` for the same-version
core. Use separate bundle paths; the [publication guide](../../docs/guides/library-publication.md)
explains preparation, credentials and exact-source/recovery rules.

The Python harness copies each project outside the checkout, extracts actual
Java/XML/YAML guide blocks and checks the required EN/RU block parity, names and
counts. It supplies empty global/user Maven settings (with explicit routing and
optional proxy settings), a fresh local cache and selected repository. Ambient
Maven/JVM option variables and publication key variables are removed for the
consumer subprocess. This is controlled resolution, not a general process sandbox.

The consumer compiles/runs the extracted examples and independent assertions.
For each own library it requests POM/main/sources/Javadoc, checks all four hashes
and Maven's per-file repository-origin evidence. Local assembly copies only those
primary files into a temporary target repository; Spring events combines both
bundles there. Public dependencies/plugins still require network access even
when the target bundle is local. Temporary projects, caches and outputs are
removed on success or failure; capture the command log if evidence is needed.

JDK-only concurrency, diagnostics and events admit no runtime dependency beyond
their library/JDK. Logging's main POM supplies SLF4J API; its fixtures select the
provider/Boot integration. Configuration's fixture owns Context/SnakeYAML;
Spring events must obtain core/Context/SLF4J transitively from the adapter POM.
Sources/Javadoc dependency exclusions prevent classifier artifacts from repairing
missing main dependencies where those APIs are tested. Fixture POMs and harness
graph expectations must change together with the reviewed component descriptor.

## Failure probes and evidence limits

Selected fixtures run warmed-cache negative dependency profiles and cold-cache
routing probes with populated alternate repositories. A negative command must
fail for the expected missing/incompatible API; controls must prove the alternate
can actually supply matching bytes. A generic failure is not successful rejection.
Configuration and events fixtures additionally compare exact whole graphs and
application-BOM mediation. Profiles are qualification probes, not extra supported
library baselines.

`make library-publication-test` checks offline preparation/admission/recovery and
extraction guards; it does not run these cold consumers. Consumer success proves
the selected bundle/repository and exercised examples, not another production
service, SMB integration, async logging transport or capacity. Remote qualification
belongs to retained per-component release records and must identify actual bytes.
