# Diagnostic-to-log bridge

This package translates diagnostic occurrences into IOC operational log events
and supplies the service's selected-context formatter. It uses the diagnostic
API, IOC renderer/metadata and observability facade plus SLF4J; dependency and
composition rules are in the
[module reference](../../../../../../../README.md).

## Source map

| Source | Responsibility |
|---|---|
| [LoggingDiagnosticSink.java](LoggingDiagnosticSink.java) | Synchronous `DiagnosticSink` adapter: level, typed fields, rendered message and original cause |
| [RedactingDiagnosticContextFormatter.java](RedactingDiagnosticContextFormatter.java) | Severity-dependent handling of registered raw context strings and embedded copies |
| [package-info.java](package-info.java) | Local bridge package boundary |

## Event mapping and MDC

Each `emit` builds an event with `event.action=diagnostic_emit`,
`event.outcome=unknown`, `ioc.diagnostic.code` and the effective
`ioc.diagnostic.severity`. `ioc.diagnostic.category` is added only for
`IocDiagnosticCode`. FATAL and ERROR use the ERROR log level; WARN, INFO, DEBUG
and TRACE use their corresponding levels. FATAL remains distinguishable in the
severity field. Impact does not select the log level or outcome.

The injected renderer supplies the message; the original optional cause is
passed to the logging backend. Context is not copied wholesale into structured
fields, and the diagnostic timestamp is not explicitly forwarded. The sink
owns neither aggregation nor a failure-policy checkpoint.

An emission temporarily hides ambient `ioc.diagnostic.category`, including for
a generic code that has no IOC category, then restores it. The shared log builder
also hides exact MDC collisions with event-local fields while logging. Other
ambient correlation remains available. Run/source/stage scopes come from the
caller or pipeline observer; the sink neither derives them from diagnostic
context nor propagates MDC to another thread.

Logger and renderer constructor arguments and the emitted diagnostic must be
non-null. Rendering happens before the shared builder checks the logger level,
so disabling a level does not skip renderer work or its possible failures.
The sink catches no delivery failure; use the shared
[ResilientDiagnosticSink](../../../../../../../../platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/sink/README.md)
when reporting must not abort processing. Calls run on the caller's thread,
without workers, retries or guaranteed appender/encoder acceptance. Sharing the
sink depends on the supplied logger/renderer and stable diagnostic references.

## Formatter scope and disclosure

The registered raw keys are the exact, case-sensitive
[DiagnosticContextKeys](../../../../../../../../platform-diagnostics/src/main/java/com/iocextractor/diagnostics/DiagnosticContextKeys.java)
`indicator`, `item` and `value`. Policy uses the diagnostic's effective severity,
not the logger's configured threshold:

| Severity | Registered raw value |
|---|---|
| INFO / WARN / ERROR / FATAL | `[redacted:sha256:<12 lowercase hex digits>]`, derived from the first six SHA-256 bytes of the original UTF-8 string |
| DEBUG / TRACE | Shared lexical URL-component sanitization; remaining text stays visible |

The fingerprint uses the original value, including any credentials/query, rather
than its sanitized form. It is a correlation hint, not a canonical IOC identity
or an anonymization guarantee. For other formatted context entries, the formatter
replaces literal copies of each registered nonempty raw context string using
the same policy. It does not discover arbitrary secrets, encoded variants or
values under unregistered keys.

The [shared sanitizer](../../../../../../../../platform-observability/src/main/java/com/iocextractor/observability/SensitiveLogValueSanitizer.java)
hides recognized `://` URL user-info and query content. It is lexical rather than
URI parsing; paths, fragments and scheme-less credentials can remain visible.
DEBUG/TRACE disclosure therefore still requires a permitted producer contract.

With [TemplateDiagnosticRenderer](../../../../../../../../platform-diagnostics/src/main/java/com/iocextractor/diagnostics/render/TemplateDiagnosticRenderer.java),
formatting applies only to substituted context placeholders. Static template
text and missing placeholders are not rewritten; generic codes render as literal
IDs without calling the formatter. A different renderer may use another policy.
Neither the diagnostic/context nor its Throwable is mutated. The cause's message,
cause chain, suppressed exceptions, ambient MDC and unrelated free text are not
automatically sanitized. Producers own those disclosure channels; see
[observability policy](../../../../../../../../../docs/dev/observability.md#disclosure-and-redaction).

The formatter holds no per-call mutable state; it creates its hash state locally.
The [test reference](../../../../../../test/java/com/iocextractor/observability/diagnostics/README.md)
maps level, exact-value redaction and correlation checks and their limits.
