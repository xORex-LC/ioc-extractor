# com.iocextractor.application.dataframeimport.mapping

## Purpose

Pure deterministic mapping-policy logic applied before row-level atomicity and
canonical persistence.

## Structure

| File | Purpose |
|---|---|
| `ImportHeaderPlan.java` | Exact alias-aware physical-to-canonical header plan |
| `DataframeImportRowMapper.java` | Atomic multi-artifact mapping, transforms, tri-state cells, key material and staging-attempt session ownership |
| `ImportRowMappingException.java` | Safe critical mapping failure and stable reason |
| `ImportValueMappingException.java` | Safe input-dependent transform rejection |
| `ImportRowMappingResult.java` | Accepted logical row with optional warnings, or safe rejection issues |
| `ImportMergeResolver.java` | Resolves one tri-state cell against an active value |
| `ImportMergeResult.java` | Storage-neutral set, clear, unchanged or conflict outcome |

## Dependencies

**Depends on:** `dataframeimport.model`. **Must not depend on:** adapters,
frameworks, storage or transport libraries.

The declarative mapper owns the `as-is` strategy. An explicit `processed`
contract delegates admitted tri-state cells through `ProcessedImportRowPreparer`.
The application then validates final row shape and computes canonical/match keys
from final fields. The processed strategy cannot change artifact roles, requested
slots or admitted merge policies. `as-is` retains its existing mapping order.

`UncachedImportPreparationSession` forwards stateless preparation and source authority
without introducing durable state or bypassing any row validation.
