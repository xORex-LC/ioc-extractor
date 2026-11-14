# Retention policy and leaf sweep

## Purpose and boundary

Pure age/count selection shared by capability-specific retention, plus the ordinary leaf-entry sweep use case.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [RetentionPolicy](RetentionPolicy.java) | Union of age and count expiry over caller-defined units. |
| [RetentionEntry](RetentionEntry.java), [RetentionTarget](RetentionTarget.java), [RetentionAction](RetentionAction.java), [RetentionService](RetentionService.java) | Configured leaf target, delete/archive selection and driven-store sweep. |

## Contracts and maintenance

Age selection is strictly older than the configured duration; equality is retained. Null/nonpositive age and nonpositive max-count disable the respective selector. Either active criterion can select an entry; count is per target and newest-first.

The unit matters: ordinary maintenance receives individual entries, immutable export supplies whole slices per profile, and managed import supplies terminal deliveries/outcome pools. Sharing selection logic does not authorize a recursive leaf reaper to delete protected import evidence or pinned slices. Physical listing/delete/archive and symlink/root protections belong to the store adapter.

[Capability internals](../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
