# Computed per-item decisions

## Purpose and boundary

Compact observation carriers for already-computed processing outcomes. This package does not render logs, schedule delivery or decide business success.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [PipelineItemDecision](PipelineItemDecision.java) | Computed decision kind/outcome and optional correlation/context fields. |
| [PipelineDecisionKind](PipelineDecisionKind.java), [NoopPipelineDecisionTracer](NoopPipelineDecisionTracer.java) | Stable decision vocabulary and explicit no-op implementation. |

## Contracts and maintenance

The driven `PipelineDecisionTracer` gate must be checked before allocating/rendering optional detail. Reuse the actual classification/routing result; never repeat domain work to log it. IOC values may be present in explicitly enabled item trace, so serializers/masks and logger scope are adapter-owned; do not label all decision carriers value-free.

Operational observation is not a diagnostic policy checkpoint or a durable event log. Refer to [trace port](../port/out/observability/README.md) for ownership.

[Capability internals](../../../../../../../../../docs/dev/observability.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
