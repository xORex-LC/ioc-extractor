# Stage and checkpoint tests

Supported stage outcomes, typed failure behavior, selected-plan accounting and side-effect order.

| Entry points / files | Responsibility |
|---|---|
| [ReadSourceStageTest](ReadSourceStageTest.java), [RefangStageTest](RefangStageTest.java), [ExtractIndicatorsStageTest](ExtractIndicatorsStageTest.java), [AttributeSourceStageTest](AttributeSourceStageTest.java) | Materialized-stage positive/negative/trace and attribution contracts. |
| [PrepareRoutedArtifactsStageTest](PrepareRoutedArtifactsStageTest.java), [DocumentObservationSelectionTest](DocumentObservationSelectionTest.java), [DocumentProcessingSessionContractTest](DocumentProcessingSessionContractTest.java) | Required plan, original versus final-key accounting, session lifetime and sealed-row preparation. |
| [WriteArtifactsStageTest](WriteArtifactsStageTest.java), [ArtifactPolicyCheckpointTest](ArtifactPolicyCheckpointTest.java) | Deferred IDs, canonical commit/projection, dry-run and rejecting policy before durable writes. |
| [StageContractTest](StageContractTest.java), [StageTestSupport](StageTestSupport.java) | Stage IDs/interfaces, diagnostic retention totals and reusable synthetic fixtures. |

Assertions should identify observable results and whether canonical/projection ports were invoked, not merely match an internal method order. Diagnostic-budget tests retain exact policy totals beyond the visible sample. Document per-artifact commits must not be mistaken for managed-import cross-artifact atomicity.

Materialized stage coverage does not imply those classes compose the factory-built document path; production uses the source-spool stages. Concrete streamed-source admission, disk codecs and real selected Router execution are qualified downstream.

[Test map](../../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
