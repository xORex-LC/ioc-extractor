# Diagnostic values and consumer-owned codes

This package creates structured occurrences without selecting a service catalog,
exception type or logging framework. It uses only the JDK. Read the
[module reference](../../../../../../../README.md) for dependency,
publication and verification rules; [package-info.java](package-info.java) and
public Javadoc define the member contracts.

## Source map

| Source | Responsibility |
|---|---|
| [DiagnosticCode.java](DiagnosticCode.java) | Consumer-owned nonblank ID, default severity and impact; stable metadata and equality |
| [DiagnosticSeverity.java](DiagnosticSeverity.java) | Ordered TRACE through FATAL; ERROR/FATAL detection |
| [DiagnosticImpact.java](DiagnosticImpact.java) | ELEMENT, RUN and OPERATION ownership, independent of severity |
| [DiagnosticFactory.java](DiagnosticFactory.java) | Shared caller-provided clock; creates a fresh builder for each occurrence |
| [DiagnosticBuilder.java](DiagnosticBuilder.java) | Task-owned effective severity, context and optional cause; timestamps at `build()` |
| [Diagnostic.java](Diagnostic.java) | Occurrence snapshot and severity-sensitive equality |

## Identity, ownership and failure

Consumers usually implement codes as an enum. The API imposes no ID naming
scheme or global catalog; consumers own uniqueness and compatibility. Equality
compares code objects using their own `equals`, rather than comparing ID strings.
A diagnostic's equality/hash includes code, context and effective severity,
excluding time and cause. Equal occurrences are still counted separately.

The builder defaults to the code's severity and accepts an explicit override.
Reusing a context key replaces its value; later builder changes do not alter a
previous diagnostic's map. The map is copied, but its values, code and cause are
shared references. Keep these objects stable after publication. `toString()`
includes raw context and is not a redacted log format.

Required arguments and code metadata must be non-null; code IDs must be nonblank.
Omit `cause(...)` when there is no cause: `cause(null)` is rejected. Bulk context
addition can retain entries accepted before an invalid entry. Builders are not
thread-safe. A factory has no mutable state of its own; sharing it requires a
thread-safe clock.

Impact does not decide severity, skipping, commit or retry. ELEMENT/RUN spend
bounded collectors' detail budget; OPERATION bypasses it and can grow memory.
[Results and policies](result/README.md) own accumulation and stopping decisions;
[delivery](sink/README.md) owns synchronous observation. Consumer examples live in
the [library guide](../../../../../../../../../docs/guides/libraries/diagnostics.md); local checks are mapped
in the [test reference](../../../../../../test/java/com/iocextractor/platform/diagnostics/README.md).
