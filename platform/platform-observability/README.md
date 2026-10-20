# platform-observability

IOC logging catalogs, compatibility facades and the ETL logging observer.
`LogEvent`, `MdcScope` and `SensitiveLogValueSanitizer` delegate to
[`platform-logging-slf4j`](../platform-logging-slf4j/README.md). This module keeps
IOC vocabulary out of the reusable JAR and keeps existing producer imports.

**Layer rule:** no domain/application business rules, adapter I/O or runtime
appender configuration. Bootstrap owns `logback-spring.xml` and the ECS encoder.

## Structure

| Part | Responsibility |
|---|---|
| `LogField`, `EventAction`, `EventOutcome` | IOC field/action catalog and binding to shared descriptors/outcomes |
| `MdcScope` | IOC field convenience; callback factory closes a partly populated scope on failure |
| `SensitiveLogValueSanitizer` | Delegates lexical URL-component sanitation; diagnostic disclosure remains local |
| `logging/` | Thin shared-builder facade and `LoggingPipelineObserver` |
| `src/test` | Catalog generation, IOC descriptor binding, delegated emission, MDC and URL regressions |

## Dependencies

Main dependencies: `ioc-platform-logging-slf4j`, `ioc-platform-etl`, SLF4J API.
Logback is test-only. No reverse dependency from the generic library exists.

`LogField.spec()` binds each catalog entry to a shared `LogFieldSpec` and its
`LogValueType`. New custom fields remain local catalog changes. The catalog is
generated into [LOGGING-CATALOG](../../docs/LOGGING-CATALOG.md).

## Wire and ownership

The facade uses the shared scalar checks and ECS helper validation. Invalid
values throw before level filtering. Event-local values override exact MDC keys
for one log attempt. Workers reconstruct context explicitly; scopes restore only
touched keys on the same thread. Acquire a scope before population, or use the
callback factory when an observer must return an owned `AutoCloseable`.

Control-event names use `ioc.event.type`; informational no-op records use
`ioc.operation.disposition=skipped` without an ECS outcome. When a record carries
a Throwable, the backend owns `error.*`. Type-only failures without a Throwable
may use `error.type`. Query migration and diagnostic rendering policy are in
[observability](../../docs/dev/observability.md).
