# com.iocextractor.application.dataframeimport.mapping

## Purpose

Pure deterministic mapping-policy logic applied before row-level atomicity and
canonical persistence.

## Structure

| File | Purpose |
|---|---|
| `ImportMergeResolver.java` | Resolves one tri-state cell against an active value |
| `ImportMergeResult.java` | Storage-neutral set, clear, unchanged or conflict outcome |

## Dependencies

**Depends on:** `dataframeimport.model`. **Must not depend on:** adapters,
frameworks, storage or transport libraries.
