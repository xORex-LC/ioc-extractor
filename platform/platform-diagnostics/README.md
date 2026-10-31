# ioc-platform-diagnostics

IOC-specific diagnostic catalogs, metadata, rendering and the product exception
boundary. Generic values, results, policies and sinks live in
[`ioc-platform-diagnostics-api`](../platform-diagnostics-api/README.md).

| Package/type | Responsibility |
|---|---|
| `IocDiagnosticCode` | Adds category, message key and template to the shared code contract |
| `DiagnosticCategory`, `DiagnosticContextKeys` | IOC ownership and producer/renderer context vocabulary |
| `DiagnosticException` | Carries a shared diagnostic through `IocExtractorException` |
| `codes/`, `catalog/` | Product code enums and generated catalog reference |
| `render/` | Templates and application-supplied context formatting |

Dependencies point to the shared API and `ioc-platform-errors`. No Spring,
SLF4J, adapters, application use cases or bootstrap imports belong here. A generic
consumer code has no IOC category; rendering it returns its literal ID.

The [generated catalog](../../docs/DIAGNOSTICS-CATALOG.md) remains IOC-owned.
The [consumer guide](../../docs/guides/libraries/diagnostics.md) explains the shared
API; the [observability doc](../../docs/dev/observability.md) describes delivery.
