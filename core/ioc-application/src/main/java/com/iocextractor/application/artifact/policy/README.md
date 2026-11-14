# Artifact occurrence and field-update policy

## Purpose and boundary

Pure strategies for choosing whole candidates and deciding one managed field update. No wall clock, storage, scheduler or transport is consulted.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ArtifactOccurrenceSelector](ArtifactOccurrenceSelector.java), [ArtifactWritePolicy](ArtifactWritePolicy.java) | Configured keep-first/last-nonempty candidate selection and field policy. |
| [LatestRegisteredValuePolicy](LatestRegisteredValuePolicy.java), [FieldValueOrigin](FieldValueOrigin.java), [FieldUpdateDecision](FieldUpdateDecision.java) | Durable order/position precedence and public-change versus origin-only result. |

## Contracts and maintenance

Whole-candidate selection does not merge fields from different occurrences. `latest-registered` compares dataframe-assigned observation order and occurrence position, not processing completion or source timestamp. Blank/NULL incoming values preserve the current field under this policy.

An equal public value with a later origin advances metadata without a public revision. Conflicting evidence at exactly the same order is rejected. Persisted origin belongs to one lifecycle; bootstrap compiles supported policy declarations and the canonical writer applies decisions atomically.

[Capability internals](../../../../../../../../../../docs/dev/processing.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
