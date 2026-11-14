# Per-item decision trace port

## Purpose and boundary

Gate optional rendering of already-computed decisions without adding logging-framework dependencies to the application.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [PipelineDecisionTracer](PipelineDecisionTracer.java) | Cheap current enablement check and observational emission. |

## Contracts and maintenance

Check `isEnabled()` before creating decision objects/derived strings. `trace` must not change processing outcomes; implementations own logger level, MDC, encoding, value masks and suppression of operational failures. No second extraction/classification may be performed solely for tracing.

This seam is not diagnostic storage, a durable audit log or a broker. A disabled/no-op tracer is an explicit observation choice, not permission to skip business validation.

[Capability internals](../../../../../../../../../../../docs/dev/observability.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
