# IOC pipeline stages

## Purpose and boundary

One operation per stage, using inward domain/processing behavior and driven application ports. Assembly and order belong to the extraction service.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ReadSourceStreamStage](ReadSourceStreamStage.java), [RefangSourceStreamStage](RefangSourceStreamStage.java), [ExtractSourceStreamStage](ExtractSourceStreamStage.java), [AttributeSourceStreamStage](AttributeSourceStreamStage.java) | Factory-built source-spool path; global extraction/attribution state belongs to source workspace. |
| [ReadSourceStage](ReadSourceStage.java), [RefangStage](RefangStage.java), [ExtractIndicatorsStage](ExtractIndicatorsStage.java), [AttributeSourceStage](AttributeSourceStage.java) | Materialized batch seams retained for supported callers/tests; not an alternative factory-built document dispatcher. |
| [PrepareRoutedArtifactsStage](PrepareRoutedArtifactsStage.java) | Run required plan, preserve original occurrence accounting, reduce/validate and seal before checkpoint. |
| [WriteArtifactsStage](WriteArtifactsStage.java) | Dry-run summary or per-artifact canonical commit followed by mutable projection. |
| [StageNames](StageNames.java), [ExtractionDecisionObserver](ExtractionDecisionObserver.java) | Stable stage IDs and shared observation vocabulary. |

## Contracts and maintenance

Read/refang/extraction/attribution use the invocation source scratch, with priority/overlap decisions owned by the admitted workspace. Routing consumes ordered occurrences, not a whole-document list. Retained-observation versus mapped-key selection is explicit plan policy; all original occurrences still participate in required accounting/diagnostics.

Preparation closes its processing session/cursor before sealing. The pipeline's failure-policy checkpoint occurs before `WriteArtifactsStage`; exact ERROR/FATAL totals cannot disappear when retained diagnostics reach the limit. Promotion marks the workspace, reserves canonical IDs and writes artifacts in sequence. Projection follows each canonical commit. This is per-artifact persistence, not document-wide atomic commit; earlier effects survive a later failure.

Per-item trace first checks the tracer gate and uses already-computed outcomes. No duplicate classification/extraction may run solely to render a log. The adapter owns logger/serialization/privacy mechanics.

[Capability internals](../../../../../../../../../../docs/dev/processing.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
