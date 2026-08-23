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

The P4 mapper is the `as-is` strategy. A `processed` contract fails closed until
the dedicated framework-free preparation strategy is connected; it is never
silently interpreted as `as-is`.
