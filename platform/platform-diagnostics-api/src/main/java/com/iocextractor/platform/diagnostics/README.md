# Diagnostic values

`DiagnosticCode` defines only a stable ID, default severity and impact. Consumers
usually implement it as an enum. `DiagnosticFactory` shares an explicit `Clock`;
each `create` returns a task-owned builder. `Diagnostic` copies its context map,
but shares the context values and optional cause.

Equality includes code object, context and effective severity; time and cause
are excluded. Collectors count repeated occurrences even when they compare equal.
A code's metadata, equality and hash code must stay stable.

`result/` owns accumulation and decisions; `sink/` owns delivery. This package
uses only JDK classes and never selects a catalog, logger or service exception.
See the [module reference](../../../../../../../README.md) and
[consumer guide](../../../../../../../../../docs/guides/libraries/diagnostics.md).
