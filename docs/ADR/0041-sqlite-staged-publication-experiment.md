# ADR 0041: SQLite staged publication experiment

- Status: Proposed; authorizes an isolated persistence experiment, not activation
- Date: 2026-10-10
- Scope: Bounded canonical writer occupancy; SQLite remains the only backend for
  release 0.3.0

## Context

Bounded Java batches do not release SQLite's writer inside a transaction. The
corrected canonical path still exceeds the five-second physical occupancy
limit. Ordinary confirmation is atomic per artifact; a managed import is atomic
across its fan-out artifacts and canonical receipt. Splitting either into visible
commits would change supported recovery, identity, provenance and lifecycle
semantics.

The current mutation kernel uses physical constraints, UPSERT and affected-row
counts. SQLite views with write triggers cannot transparently replace it.
Neither a second business mutation engine nor a database-family migration is
admitted by this experiment.

## Proposed protocol

### Ownership and storage

Keep persistence in `adapter-store-jdbc`, with existing inward-facing ports.
The shared match/mutation kernel remains the semantic owner. Extract its
persistence surfaces when integrating the experiment; do not reimplement
KEEP_FIRST, registration order, import merging or slot policy in the publication
mechanism.

A logical publication unit is an artifact confirmation or a complete import
delivery. Its immutable identity includes the existing observation/delivery,
contract, workspace digest, catalog, schema and policy pins. A monotonically
increasing attempt fence prevents an abandoned preparer from resuming ownership.
Retry uses the same logical identity and pins. ID reservations retain their
current non-reuse rule, including unsuccessful attempts.

Prepared facts have separate physical version identity. Their logical address
is `(family, scope, key)`, where families cover canonical values/lifecycle,
aliases, provenance, origins, history, slots, revisions, generations and receipt
children. The experiment may use opaque payloads to test visibility; production
requires typed representations and selective indexes for the existing queries.
Opaque payloads are not an admitted production canonical schema.

Every version names its unit and attempt. Hidden chunk transactions store
immutable facts and a digest-qualified ordered chunk journal. An acknowledged
chunk can be retried exactly; a changed retry, gap, duplicate fact or stale
attempt is rejected. Sealing is permitted only after the declared chunks and
facts are complete. Sealing freezes the attempt; publication cannot append data.

The final transaction allocates a publication sequence, stores effective UTC
and the complete receipt header, and changes SEALED to COMMITTED. Sequence is
allocated at publication, not at unit creation: unrelated control publications
may overtake a pending promotion. Visibility selects the newest COMMITTED
version for an address by publication sequence. A committed tombstone removes
the logical fact; it must not reveal an older version. Pending or cancelled
versions never participate. Readers retain one owned SQLite snapshot across
all families and artifacts.

### Conflict and time

Serialize promotions logically in the existing durable order, separately from
physical writer admission. Physical chunk/publication transactions use
`JdbcWriterAdmission`; CONTROL, EXPIRY and EXPORT_SLOTS remain eligible between
chunks. The integration must fence both the durable logical owner and each
attempt; a process-local mutex alone does not authorize restart recovery.

Preparation reads one committed base snapshot plus its own pending overlay.
Pin semantic epochs for every affected artifact and slot namespace. Epochs
advance for value, alias, provenance/origin, renewal and expiry changes,
including metadata-only changes; clock high-water updates alone do not advance
them. Negative matching and slot reservations require namespace pins as well as
positive row dependencies. The final check is proportional to affected scopes,
not input rows. This conservative first experiment may invalidate more work
than a future dependency vector; it must measure that cost explicitly.

Sample safe UTC through the existing dataframe clock in the final publication
transaction. This is the proposed confirmation linearization point. Reject a
plan when any matched active lifecycle has reached its pinned minimum deadline
(`deadline <= effective UTC`), or a dependency epoch changed. Rebuild from a
fresh snapshot with the same observation, registration rank and policy; an
expired lifecycle gets the existing new-lifecycle outcome. Three unsuccessful
attempts end the publication attempt with retryable failure, not an oversized
fallback transaction. This bounds one attempt, not eventual queue progress;
sustained mixed-load qualification must prove progress at admitted intake.

New or renewed versions reference the publishing header's effective UTC and
their fixed validity duration. Versions not renewed retain their prior deadline
anchor. TTL remains a property of each canonical row lifecycle; a shared
physical time anchor does not make delivery the business identity. Integration
must qualify indexed active/nearest-deadline/expiry queries before adopting
this representation. No N-row time correction is allowed at publication.

### Recovery and retention

Before publication, restart either resumes digest-qualified chunks or takes a
new fence and rebuilds. Old attempts cannot append, publish, cancel or clean up
the new attempt. After publication, recovery reads the complete canonical
receipt without input/stage and cannot apply another mutation. Receipt mismatch
or incomplete evidence fails closed.

Cleanup removes cancelled attempts in bounded transactions. Committed versions,
time anchors and receipt evidence require explicit retention rules: keep the
latest logical fact/tombstone, facts needed by active lifecycles and retained
receipts, and versions needed by an in-flight preparation base. A pinned SQLite
reader preserves its database snapshot; application preparation leases and
deadline/receipt references still need durable retention ownership. WAL,
versions and workspaces share measured disk/resource limits. Cleanup or a
retention timeout must never silently change matching or recovery semantics.

## Promotion gates

1. Test the physical primitive against the pinned SQLite driver: chunk retry,
   stale fence, seal, rollback, dependency/time conflict, selective reads,
   tombstones, publication ordering and pinned snapshots. This establishes only
   persistence behavior, not canonical-port equivalence.
2. Reuse the real mutation kernel and qualify all typed families behind the
   existing ports. Readers, expiry, slots and receipts must switch together.
   No production schema migration or configuration activation before this gate.
3. Run the complete crash/concurrency matrix and existing adapter/TCK suites.
   Assert identities, registration ranks, provenance, TTL, aliases, revisions,
   generations, slots and receipt-only recovery, not just CSV equality.
4. Compare complete-service corrected and candidate SQLite under identical
   workloads and limits. Include read amplification, invalidation/rebuilds,
   logical delay, physical holds/waits, cleanup, WAL and disk. All original
   limits remain binding; failed cells remain evidence.
5. Accept this ADR only after the integration decisions above are implemented
   and qualified. Drain intake/publications for upgrade, reject incompatible
   binaries and qualify coordinated backup/restore rollback, readiness and
   cleanup. Installed-service changes require a separate deployment action.

## Alternatives and consequences

- Visible chunk commits: rejected; weaken artifact/delivery atomicity.
- More SQLite connections or smaller batches in one transaction: do not solve
  physical occupancy.
- Whole-database generation copies: add database-sized copy/merge work and do
  not preserve concurrent expiry/slot decisions without another protocol.
- PostgreSQL: a future owner-planned track, excluded from this release.
- Transparent writable views: incompatible with existing UPSERT and update
  counts; not a drop-in kernel integration.

The versioned model adds read amplification, retention and recovery machinery.
It is justified only if measured occupancy and whole-service costs meet the
adopted limits without weakening data authority. A failed experiment leaves the
current path and failed capacity status explicit; it does not authorize relaxed
limits or premature production activation.
