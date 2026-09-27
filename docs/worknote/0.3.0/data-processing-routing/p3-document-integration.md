# P3 document integration evidence

Status: implemented as an explicitly assembled execution path, 2026-09-28.
Production activation remains gated by P5 because the current durable processing
fingerprint and recovery pinning do not yet include the operator plan.

## Boundary and reuse

`IocExtractionServiceFactory` retains the legacy path when no document plan is
passed. With an admitted plan, `IocExtractionService` changes its application
stage sequence after attribution to `PrepareRoutedArtifactsStage`. The stage
keeps each attributed occurrence and its text/tie ordering, asks the
`DocumentProcessingPlan` port for selected candidates, resolves canonical keys
from mapped output rows, and uses the existing `ArtifactOccurrenceSelector`
with each artifact's configured write policy. It prepares an empty write plan
for every enabled artifact, including intentionally omitted destinations, so
the lifecycle receipt's expected artifact count remains stable. The existing
failure-policy checkpoint and `WriteArtifactsStage` remain the only path to ID
reservation and canonical writes.

`DocumentProcessingOperations` in bootstrap supplies the IOC/Camel catalog:
the existing domain `NetworkHostDeriver`, configured `MatchPolicy`
classification, feature predicates and CSV preparers. The technical plan is
compiled by the existing `CamelPlanCompiler`; `DocumentProcessingAdapter`
converts its runtime results to application candidates and diagnostics. The
Camel runtime remains caller-owned, allowing P5 activation to use the existing
`RouterRuntimeConfiguration` bean without a second context. The destination uses the
branch ID set before its Camel processor call to bind the correct default and
per-column views even when two branches target the same artifact. The CSV
preparer reuses its configured accepts/filter/mapper/trace and mapping diagnostic
path; no second row mapper or IOC-specific Router logic was introduced.

Router final failure resolutions become one diagnostic per expected view
failure, WARN when all reached consumers recovered and ERROR if any did not.
An explicit unmatched REJECT or ambiguity is ERROR. A located row mapping
rejection remains `SINK.ROW_MAPPING_FAILED`; unexpected exceptions still abort
the invocation. No Router reply writes to storage.

## Executable checks

- Two original URLs retain distinct blacklist rows while host-bound masks
  candidates share the same final key. A per-column original binding preserves
  the configured original URL match code while the default mask uses the host.
- The original source label reaches the mapped row; the stage carries occurrence
  order into ordered field positions and uses the existing last-nonempty winner.
- A configured recovery yields one WARN and a candidate; an unrecovered view
  yields ERROR. Explicit no-match REJECT and ambiguous EXCLUSIVE yield element
  ERROR. A selected branch filtered by its artifact remains a non-error;
  expected mapping failures retain their located CSV diagnostic.
- An application pipeline with fail-fast rejection reaches the checkpoint
  before any ID reservation, canonical write or projection.

## Follow-up gates

P5 must pin the exact selected plan and its semantic versions in the document
processing fingerprint before `document-plan` can choose this path in live
oneshot/daemon wiring. Pending runs must never resume under a different plan.
P4 adds processed import through the same semantic operations. P6 supplies
real artifact golden fixtures and production qualification. Existing canonical
rows are neither rewritten nor rekeyed; TTL only expires rows when fixed
lifecycle is active.

One performance edge needs measurement in P6: the plan currently classifies
each attributed occurrence before selection. Repeated identical original
values may therefore pay classification cost more than once, unlike legacy
batch-local deduplication. Any cache must remain invocation-local and preserve
per-occurrence source/ordering metadata.

The PMD review found the new stage's first implementation too complex and a
new long constructor. Splitting candidate collection from selection and
grouping extraction assembly ports/settings removed both new findings and one
pre-existing excessive-parameter finding. The accepted count tightens from 9
to 8; no rule or scope changed. Additional semantic tests cover duplicate
occurrences, unknown destinations and the checkpoint before ID reservation.
