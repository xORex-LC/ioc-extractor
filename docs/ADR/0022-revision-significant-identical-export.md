# 0022 — Revision-significant identical export candidates

## Status

**Accepted on 2026-08-22. Implemented in the 0.3.0 candidate; final release
qualification remains pending.**

This ADR narrowly supersedes ADR-0012 wherever that decision treats matching
profile bytes and `plan_hash` as sufficient to discard a materialized export
candidate. ADR-0012 remains in force for snapshot consistency, immutable slice
formation, the export saga, recovery, retention, and all other export behavior.

## Context

ADR-0020 introduced expiring canonical record lifecycles. An observation after
expiry creates a new active lifecycle and advances the existing insert-driven
`artifact_revision`. ADR-0021 may assign that lifecycle a reusable export slot.

A later active snapshot can nevertheless have exactly the same public CSV bytes
as an older slice. This happens, for example, when all expired IOCs return with
the same values and receive the same now-free export slots. The former
post-materialization rule compared only content hashes and `plan_hash`, marked
the candidate `SKIPPED`, retained the old slice ID, and emitted no
`SliceCompleted` event.

That outcome confuses projection equivalence with delivery equivalence. The
operator supplied a new source, canonical storage accepted new public
lifecycles, and downstream delivery must receive a new immutable occurrence
even if its bytes match a historical occurrence.

## Decision

### 1. Preserve the revision pre-gate

The existing `artifact_revision + ExportProgress` pre-gate remains unchanged.
Expiry, renewal, and confirmation of an active lifecycle do not advance the
revision and therefore do not create an export. A canonical commit that inserts
at least one new public lifecycle does advance it; reappearance after TTL expiry
is such a commit.

No lifecycle-specific export event, history query, or TTL dependency is added
to Artifact Emission.

### 2. Make revision equality part of redundant-candidate detection

A materialized candidate may become `SKIPPED` only when every artifact has all
of the following equal to its durable `ExportProgress`:

- artifact membership in the profile;
- export `plan_hash`;
- exact public content SHA-256; and
- covered `artifact_revision`.

Matching bytes with a higher covered revision is not redundant. The run follows
the normal `STAGED -> AVAILABLE -> COMPLETED` path, stores the candidate slice
as the new progress authority, and emits `SliceCompleted` after the durable
completion checkpoint.

This is a profile-level decision: one revision-significant artifact makes the
whole complete profile slice publishable.

### 3. Apply the same pure policy during recovery

`ExportService` and `ExportRunRecoveryService` use one framework-free change
detector. Recovery evaluates the revisions already captured in the manifest;
it does not reread canonical storage. Therefore a crash after staging cannot
silently turn a required new delivery into `SKIPPED`.

Recovery may still discard a candidate whose plan, revisions, and bytes all
equal completed progress. Such staging represents the same attempted public
occurrence, not a later accepted lifecycle.

### 4. Keep events as latency hints

No new control-event type, broker, outbox, Maven module, or Java library is
introduced. `SliceCompleted` continues to be emitted only after durable
`COMPLETED`; the publish ledger and periodic reconcile remain delivery truth and
the correctness backstop if the in-process event is lost.

## Consequences

Positive consequences:

- a source returning after TTL expiry creates a new export and downstream
  delivery even when its public CSV is byte-identical to an older slice;
- active confirmations, renewals, and expiry alone retain the accepted
  no-export behavior;
- forward execution and crash recovery cannot disagree;
- lifecycle/history storage stays independent of export policy.

Costs:

- immutable storage and remote delivery can contain multiple byte-identical
  slices with different run/slice identities;
- content hashes remain integrity and projection-equivalence evidence, but are
  no longer a complete business-occurrence identity;
- operators diagnosing export decisions must inspect terminal status together
  with covered revision, not hashes alone.

## Rejected alternatives

- **Keep content hash as the sole post-gate:** loses legitimate later
  deliveries and hides accepted new lifecycles from operators.
- **Advance revision on expiry:** creates withdrawal-driven slices contrary to
  the accepted TTL trigger contract.
- **Query history or TTL state from export:** couples bounded contexts and makes
  recovery depend on mutable current state.
- **Emit a special reappearance event:** duplicates the durable revision signal
  and would still require reconcile correctness.
- **Add a nonce to CSV bytes:** corrupts deterministic public projections to
  compensate for a coordination-policy error.

## Verification

- detector test: equal plan/hash with equal revision is redundant, while a
  higher revision is publishable;
- forward export test: identical bytes with a higher revision complete a new
  slice and emit `SliceCompleted`;
- recovery test: the same candidate completes and emits after a crash without
  canonical reread;
- recovery test: equal plan/hash/revision staging remains safely skippable;
- packaged stand: expire a source, ingest an identical later observation, and
  verify a new slice and publish-ledger delivery.
