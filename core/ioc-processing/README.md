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
Transform specifications are bound once at construction, preserving ordered
arguments and lazy errors for reached cells; providers still run per occurrence.

Dependencies point only to `ioc-domain` and `ioc-platform-errors`. Spring,
Camel, CSV, JDBC and transport libraries are forbidden by Maven and ArchUnit.
The CSV adapter consumes this evaluator for both compatible processing and
selected IOC routes; there is one field-mapping implementation.

`IndicatorProcessingSession` is thread-confined and closed by the document stage
or the processed-import staging attempt through application handles. Direct
single-row calls retain a row-local scope. Classification includes the complete
indicator/source under the same pinned policy instance; successful host pairs
reattach the current source. Separate LRU partitions share the existing total
256-entry / 1 MiB charged budget equally. Oversized entries bypass admission
without eviction; full partitions replace their least-recently-used entry.
Expected failures, diagnostics, positions and mapped rows are never cached.
See [ADR 0033](../../docs/ADR/0033-bounded-semantic-reuse-during-import-staging.md).
