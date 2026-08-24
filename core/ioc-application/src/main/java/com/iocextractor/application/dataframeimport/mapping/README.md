# com.iocextractor.application.dataframeimport.mapping

## Purpose

Pure deterministic mapping-policy logic applied before row-level atomicity and
canonical persistence.

## Structure

| File | Purpose |
|---|---|
| `ImportHeaderPlan.java` | Exact alias-aware physical-to-canonical header plan |
| `DataframeImportRowMapper.java` | Atomic multi-artifact mapping, transforms, tri-state cells and key material |
| `ImportRowMappingException.java` | Safe critical mapping failure and stable reason |
| `ImportValueMappingException.java` | Safe input-dependent transform rejection |
| `ImportRowMappingResult.java` | Accepted logical row or bounded safe issues |
| `ImportMergeResolver.java` | Resolves one tri-state cell against an active value |
| `ImportMergeResult.java` | Storage-neutral set, clear, unchanged or conflict outcome |

## Dependencies

**Depends on:** `dataframeimport.model`. **Must not depend on:** adapters,
frameworks, storage or transport libraries.

The declarative mapper owns the `as-is` strategy. An explicit `processed`
contract delegates its already mapped row through `ProcessedImportRowPreparer`;
the CSV adapter reuses ordinary refang, extraction, classification and artifact
mapping policy, replaces derived fields, preserves operator-owned fields and
recomputes canonical/match keys before staging.
