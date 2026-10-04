# ADR 0034: Required Router processing plans

Status: Accepted, 2026-10-04. Supersedes the optional-flow and compatible-preparer
retention clauses of [ADR 0031](0031-bounded-camel-preparation-runtime.md).

## Context

The initial Router rollout admitted explicit document/import selections while
retaining an executable document pipeline and provider-inferred processed-import
preparer for configurations without selections. This was a rollout boundary,
not the final product model. The operator accepted complete retirement of those
paths before stand deployment, with original-value production defaults.

Identical public CSV bytes alone do not prove preservation of observation
semantics. Source duplicates and distinct inputs that map to the same key affect
ID reservations, source counts and canonical confirmation validation.

## Decision

Every document executes a required named `ioc.processing.document-plan` through
the embedded Router. Every `mode: processed` import contract requires an explicit
`processed-route` with source inputs and authorized output fields. Missing
selections fail configuration admission; there is no inferred route or alternate
processing engine. `as-is` remains an independent import mode.

The classpath and production document defaults route the original IOC view to
all shipped artifacts. Existing artifact filters, column transforms, identity and
write policies retain their ownership. URL-to-host/IP cleanup requires an
explicit plan change and is not enabled by this migration.

Document plans declare `observation-selection`:

- `retained-observations`: KEEP_FIRST artifacts prepare the source observations
  admitted by `ioc.pipeline.deduplicate`, preserving mapped collisions and ID
  reservation multiplicity. LAST_NONEMPTY artifacts still examine every
  occurrence and select a whole row by final artifact key. This is the shipped
  original-value default.
- `final-key`: every occurrence participates in routing and diagnostics; each
  artifact selects whole-row winners by final key. This is the default for newly
  declared plans and supports derived-view collapse.

The source-observation policy is part of the same Router path and the policy
fingerprint. It is not a compatibility dispatcher. The application continues to
own identity resolution, winner selection, failure-policy checkpoints, ID
reservation, canonical commit and receipts. Camel owns bounded preparation only.

## Consequences

- Custom artifact catalogs must supply complete named plans. Disabled artifacts
  cannot be destinations; every enabled document artifact is routed or explicitly
  omitted. Processed contracts cannot omit their route even with intake disabled.
- Old configuration must be migrated and checked before activation. Drain work
  under its pinned policy; unsealed work cannot silently adopt a different plan.
  Canonically committed imports still recover from receipts without reprocessing.
- Existing canonical rows are not rewritten. The existing lifecycle confirmation
  rejection of duplicate final keys remains intact for retained-observation plans.
- Historical P6/O7/O8 comparison evidence describes the prior optional rollout;
  it does not qualify the complete cutover. New acceptance must verify defaults,
  configured routes, ID/provenance accounting, negative admission and recovery.

## References

- [Processing](../dev/processing.md)
- [Managed import](../dev/dataframe-import.md)
- [Operator routes](../guides/ioc-processing-routes.md)
- [Observation ordering](0030-registered-observation-order-for-artifact-fields.md)
