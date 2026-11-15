# ioc-platform-observability

## Purpose and boundary

IOC logging vocabulary and facades over the reusable SLF4J library, plus the
ETL logging observer. The module keeps existing IOC producer imports and local
field/action policy separate from generic logging mechanics.

This is an internal product JAR, `com.iocextractor:ioc-platform-observability`,
using the shared reactor version. It is separate from the published
[platform-logging-slf4j](../platform-logging-slf4j/README.md) library.
Domain/application business rules, adapter I/O, appenders, JSON encoding, health
contributors and runtime backend configuration remain with their owners.

## Structure

| Location | Responsibility |
|---|---|
| [Base package](src/main/java/com/iocextractor/observability/README.md) | IOC field/action/outcome vocabulary, mode labels, MDC convenience and selected URL sanitization |
| [logging](src/main/java/com/iocextractor/observability/logging/README.md) | IOC event-builder bindings and `LoggingPipelineObserver` |
| [Tests](src/test/java/com/iocextractor/observability/README.md) | Catalog generation, taxonomy, bindings, emission, MDC callback cleanup and URL regressions |
| [pom.xml](pom.xml) | Shared logging/ETL/SLF4J dependency family |

The package references map all local types. Public Javadoc and `package-info.java`
own exact member/package contracts. Cross-module operation and disclosure policy
belongs to [observability](../../docs/dev/observability.md).

## Dependencies and direction

Direct production dependencies are `ioc-platform-logging-slf4j`,
`ioc-platform-etl` and `slf4j-api`, with parent-managed versions. ETL brings the
shared diagnostics API and IOC diagnostics/error contracts. Logback classic/core,
Jupiter and AssertJ support tests; the production closure selects no logging
backend or Spring configuration.

Keep business types, adapters and bootstrap out of this module. Bootstrap may
compose these facades and observer. Its
[ArchitectureTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
checks observability packages against domain/adapter/bootstrap dependencies and
separately protects the reusable logging API's JDK/SLF4J-only boundary. These
checks do not turn catalog descriptions into runtime disclosure validation.

## Entry points and wire ownership

`LogField.spec()` binds IOC keys and scalar types to shared descriptors; the five
standard fields reuse shared ECS declarations. `EventAction.value()` and
`EventOutcome.value()` supply stable operational wire values. The event facade
accepts `LogField` rather than arbitrary keys, delegates scalar checks/emission,
and preserves the caller-selected logger category.

Use action/outcome helpers for their intended wire semantics. Generic STRING
field conversion uses `toString()` and can bypass that vocabulary. Descriptions
such as TRACE-only value disclosure are producer obligations, not level-aware
checks in `field`. Control-event names belong in `ioc.event.type`; informational
no-op records can use `ioc.operation.disposition=skipped` without an ECS outcome.

`MdcScope` binds catalogued STRING fields to same-thread restoring scopes. Its
callback factory attempts cleanup if initialization fails and preserves the
original failure, suppressing only a distinct cleanup failure. Scopes propagate
nothing to workers. Null local omission differs from explicit ambient hiding.

`LoggingPipelineObserver` reconstructs run/stage correlation and reports stage
lifecycle through ETL callbacks. It is not a durable completion record or an
exception-isolation layer. The [logging reference](src/main/java/com/iocextractor/observability/logging/README.md)
owns the exact field/callback map. Sensitive-value sanitization is lexical and
selected-value only; diagnostic fingerprint/severity policy remains in the
[diagnostics logging bridge](../platform-diagnostics-logging/README.md).
With a Throwable, the backend owns `error.*`; producers own safe exception text.

## Catalog maintenance and verification

[LOGGING-CATALOG.md](../../docs/LOGGING-CATALOG.md) is generated from `EventAction` and
`LogField`; do not edit it by hand or maintain a competing field/action inventory.
The [base package reference](src/main/java/com/iocextractor/observability/README.md)
describes the generator and extension checks. Add an action with its first real
producer, check emitted fields/types and disclosure, and regenerate after a
catalog change. Catalog presence or a bytecode reference does not prove emission.

From the repository root:

```bash
make test-module MODULE=platform/platform-observability
make docs
make verify
```

The [test reference](src/test/java/com/iocextractor/observability/README.md)
separates local contracts from pipeline correlation, actual IOC JSON and Boot
recipe qualification. [AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
composes the extraction observer and diagnostic delivery;
[logback-spring.xml](../../bootstrap/ioc-app/src/main/resources/logback-spring.xml)
and the [IOC ECS encoder](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocEcsStructuredLogEncoder.java)
remain bootstrap-owned. Update affected references and tests with a contract
change; keep generic API guarantees in the reusable library and durable
completion/recovery evidence with the service.
