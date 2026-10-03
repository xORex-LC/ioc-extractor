# core/ioc-processing

Pure IOC preparation between domain rules and application orchestration.

`model` holds a classified IOC value; `classification` applies the configured
domain match policy; `parse` composes whole-cell extraction with the domain
address parser; `session` owns bounded invocation-local semantic reuse; `mapping` owns the one declarative field evaluator, providers,
transforms, gates and expected mapping-failure contract. It does not select
canonical keys, reserve IDs, emit diagnostics or write storage.
`ConfigurableRowMapper.toRow(defaultView, columnViews)` resolves a single
classified value per output column; its gate, provider and transforms all use
that same value. The caller validates bindings and supplies immutable views.

Dependencies point only to `ioc-domain` and `ioc-platform-errors`. Spring,
Camel, CSV, JDBC and transport libraries are forbidden by Maven and ArchUnit.
The CSV adapter consumes this evaluator for both compatible processing and
selected IOC routes; there is one field-mapping implementation.

`IndicatorProcessingSession` is thread-confined and closed by the document stage
through an application handle, or by bootstrap for one processed import row.
Classification includes the complete indicator/source under the same pinned
policy instance. Successful host pairs reattach the current source. Admission
caps jointly limit entries and charged key/result retention; oversized inputs
and full caches compute normally. Expected failures, diagnostics, positions and
mapped rows are never cached. See [ADR 0032](../../docs/ADR/0032-invocation-owned-ioc-semantic-reuse.md).
