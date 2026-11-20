# com.iocextractor.observability

IOC field/action/outcome catalogs and conveniences that bind them to the shared
logging library. JSON formatting and appender selection remain in bootstrap.

**Layer rule:** depend on the generic logging API, never business packages,
adapters or bootstrap. The diagnostics logging bridge is a separate module.

## Structure

| Part | Responsibility |
|---|---|
| `LogField` | IOC key, description and shared scalar descriptor |
| `EventAction`, `EventOutcome`, `ObservabilityMode` | Operational vocabulary |
| `MdcScope` | Bind catalogued correlation fields to the shared restoring scope |
| `SensitiveLogValueSanitizer` | Delegate URL-component preparation before local diagnostic rendering |
| `logging/` | IOC builder facade and ETL logging observer |

## Dependencies

Main dependencies: shared logging API and SLF4J; the logging observer also uses
ETL contracts. No dependency on domain, application, adapters or backend encoders.
