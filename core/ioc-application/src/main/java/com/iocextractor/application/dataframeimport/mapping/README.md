# Import row mapping and merge decisions

## Purpose and boundary

Deterministic header/cell mapping before canonical persistence. One logical input row can produce authorized primary/related artifact branches.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ImportHeaderPlan](ImportHeaderPlan.java) | Exact physical-to-canonical alias resolution. |
| [DataframeImportRowMapper](DataframeImportRowMapper.java), [ImportRowMappingResult](ImportRowMappingResult.java) | Row-atomic mapping, transforms/validation, final keys and accepted warnings/rejected issues. |
| [ImportMergeResolver](ImportMergeResolver.java), [ImportMergeResult](ImportMergeResult.java) | Tri-state field action: set/clear/preserve/conflict. |
| [ImportRowMappingException](ImportRowMappingException.java), [ImportValueMappingException](ImportValueMappingException.java), [UncachedImportPreparationSession](UncachedImportPreparationSession.java) | Safe failure taxonomy and stateless preparation-session adapter. |

## Contracts and maintenance

Preserve ABSENT, explicit NULL and VALUE until merge. Omitted input and explicit clear have different meanings. A logical row must not leave a partial artifact fan-out when one branch fails validation.

As-is owns declarative order. Processed mode delegates admitted cells to the selected preparer, then validates final shape and resolves keys from final fields. It cannot enlarge source authority, change branch roles, requested-slot policy or merge permissions. A staging-attempt session scopes reusable preparation; close it on every exit before sealing. Uncached delegation is not permission to bypass validation or quota accounting.

[Capability internals](../../../../../../../../../../docs/dev/dataframe-import.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
