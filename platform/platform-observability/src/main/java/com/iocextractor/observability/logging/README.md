# com.iocextractor.observability.logging

IOC conveniences over the shared SLF4J event builder, plus the ETL observer.

**Layer rule:** the facade binds IOC enum values to shared descriptors. All
normalization, validation, MDC collision handling and emission live in
`com.iocextractor.platform.logging`; no independent mechanics remain here.

## Structure

| Type | Responsibility |
|---|---|
| `LogEvent` | Bind IOC fields, actions and outcomes to the shared builder |
| `LogEvents` | Preserve IOC level factories and caller-selected logger categories |
| `LoggingPipelineObserver` | Open owned run/stage context and emit stage lifecycle records |

## Dependencies

Main dependencies: IOC observability catalog, generic logging API, SLF4J and ETL.
No domain, adapter or bootstrap imports; no JSON or appender configuration.
