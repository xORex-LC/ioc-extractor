# com.iocextractor.common

This package contains the platform exception root. Its legacy `common` name
carries no permission to accumulate utilities, IOC models or framework adapters.
The [module reference](../../../../../../README.md) owns its dependency
rules and verification scope.

| Source | Responsibility |
|---|---|
| [IocExtractorException.java](IocExtractorException.java) | Public non-final `RuntimeException` subclass with two forwarding constructors |

The message-only constructor delegates to `RuntimeException(String)`; the second
passes message and cause to `RuntimeException(String, Throwable)`. There is no
additional validation or normalization: standard JDK null-message/cause behavior,
stack traces and suppressed-exception mechanisms apply. The class adds no fields,
error taxonomy, resource ownership or side effects.

The base is intentionally extensible. IOC
[DiagnosticException](../../../../../../../platform-diagnostics/src/main/java/com/iocextractor/diagnostics/DiagnosticException.java)
is a higher-level subclass carrying its own diagnostic; it does not move that
payload or its catalog into this bottom-layer package. Consumers should preserve
the meaningful subtype/cause when translating across a boundary. A base catch
alone supplies no failure-policy, rollback, retry or disclosure decision.
