# Reusable libraries

[Русская версия](../ru/libraries/README.md)

Use these libraries when a service needs the shared mechanics below. Each
artifact has its own Maven coordinate under `io.github.xorex-lc`. Java packages
keep their existing `com.iocextractor` names. All listed releases require Java 21.

| Library | Use it for | Production dependency boundary | Guide |
|---|---|---|---|
| `ioc-platform-concurrency` | Serial work or synchronous exclusion per key | JDK only | [Concurrency](concurrency.md) |
| `ioc-platform-diagnostics-api` | Structured diagnostics, failure policies and bounded detail | JDK only | [Diagnostics](diagnostics.md) |
| `ioc-platform-logging-slf4j` | Typed SLF4J records, restoring MDC scopes and explicit masking | SLF4J API; service selects the provider | [Logging](../logging-library.md), [Boot ECS recipe](../logging-library-boot.md) |
| `ioc-config-spring-boot` | Record-schema property-name inspection and migration observations | Spring Boot and Spring Core; service prepares sources and decides policy | [Configuration](../configuration-library.md), [restricted-context recipe](../configuration-library-check.md) |
| `ioc-platform-events` | Local control-event metadata, publication contract and recording fixture | JDK only | [Local events](../events-library.md) |
| `ioc-events-spring` | Explicit publication through a service-owned Spring publisher | Same-version events core, Spring Context and SLF4J API | [Spring adapter](../events-library.md#use-the-explicit-spring-adapter) |

Concurrency and diagnostics have English/Russian guides here. The other guides
remain at their existing paths while their code review and translations are
prepared. Their current release and qualification limits are stated on those
pages. ETL remains an internal module with extraction deferred.

## Where to look next

- Start with a library guide for dependencies, working examples, errors and
  resource ownership. Use the linked module README for its package map and
  Javadoc for exact public contracts.
- Read the linked ADRs for decision history and `docs/dev/` capability pages for
  IOC's integration. A synthetic consumer does not qualify a second deployed
  service or an untested framework version.
- Follow the [publication procedure](../library-publication.md) to prepare a
  new release or recover an existing immutable bundle.

## Maintaining the guides

English and Russian pages describe the same contract and link to each other.
Keep runnable Java/YAML/XML blocks identical; comments inside them are English.
Update both pages with a behavior change and check examples against the packaged
public API. Current source behavior and an older published version must be
distinguished explicitly. Worknotes are temporary planning material.
