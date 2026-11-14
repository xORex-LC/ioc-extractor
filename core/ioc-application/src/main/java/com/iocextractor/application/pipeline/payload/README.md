# IOC pipeline payloads

## Purpose and boundary

Typed values passed between application stages. They can contain borrowed workspace-owned row sources; record syntax alone is not a deep ownership or lifetime guarantee.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [SourceText](SourceText.java), [RefangedText](RefangedText.java), [ExtractedIndicators](ExtractedIndicators.java) | Batch text/result carriers used by the materialized-stage seams. |
| [AttributedIndicators](AttributedIndicators.java), [IndicatorOccurrence](IndicatorOccurrence.java) | Workspace-backed attributed cursor and deterministic per-document occurrence metadata. |
| [PreparedArtifacts](PreparedArtifacts.java), [ArtifactWriteSummary](ArtifactWriteSummary.java) | Sealed precommit plans and final extracted/retained/write/change counters. |

## Contracts and maintenance

Keep `AttributedIndicators`' repeatable rows inside the document workspace lifetime. Each cursor must close on EOF, early return and failure. Sealed row sources do not become canonical authority until the write port commits.

`ArtifactWriteSummary` separates inserted counts from the ordered changed-artifact set; public field updates can matter without a new insert. Completion diagnostics/summaries belong to the enclosing result/envelope rather than being recomputed from retained samples.

[Capability internals](../../../../../../../../../../docs/dev/processing.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
