# 0021 — Stable reusable export slots

## Status

**Accepted on 2026-08-19. Implemented in the P7 candidate; packaged release
qualification remains pending.**

This ADR narrowly supersedes section 4, "Public and source-owned identity", of
[ADR-0020](0020-canonical-record-expiration-lifecycle.md). ADR-0020 remains in
force for record validity, expiration, history, retention, active reads,
reappearance as a new lifecycle, and all other lifecycle decisions.

## Context

The existing artifact column named `id` conflates two different concerns:

- durable technical identity of a canonical record lifecycle; and
- a position-like identifier required by downstream consumers of an export
  slice.

ADR-0020 treated that exported value as a service-owned public identity that
must never be reused. That does not match the consumer contract. The exported
value is a projection slot. It must remain stable while its record remains
active, but it must become available to a later record after the former record
expires and disappears from active exports.

For example, if active records have slots `A=1`, `B=2`, `C=3`, then `A` and
`B` expire and `D` arrives, the next slice contains `D=1`, `C=3`. If `E` later
arrives while `C` is still active, it receives slot `2`; `C` remains `3`.
Existing active rows are never renumbered merely to close a gap.

The earlier immutable slice containing `A=1` remains unchanged. A slot is
therefore meaningful only together with its export slice and artifact/profile
scope; it is not a globally stable record identity.

## Decision

### 1. Separate canonical identity from export position

The application concept is named **export slot**. Existing external schemas may
continue to expose the column as `id` for compatibility, but application and
storage code must not use that value as canonical identity, lifecycle identity,
deduplication identity, provenance identity, or a foreign key outside the
export-slot registry.

Canonical technical row identity and `_lifecycle_id` remain non-reusable.
Reappearance after expiration still creates a new lifecycle. Source-owned IDs,
when present, remain explicitly namespaced business/provenance fields and are
not export slots.

Only configured artifacts whose external projection contains the `id` column
participate in export-slot allocation. Artifacts without such a column do not
receive synthetic slots.

### 2. Use stable sparse reusable allocation

For each `(profile, artifact)` namespace:

1. An active lifecycle with an assigned slot keeps it in every later slice
   while it remains active.
2. When that lifecycle is absent from the active set at export reconciliation,
   its slot is released.
3. A newly exported lifecycle receives the smallest currently free positive
   slot.
4. If no free slot exists, allocation uses the namespace high-water mark and
   advances it.
5. Existing active assignments are never compacted or renumbered.
6. Gaps are valid and remain until later new lifecycles consume them.
7. If several lifecycles are new in one reconciliation, their deterministic
   assignment order is ascending internal lifecycle identity.

The allocator is therefore neither a monotonic public-ID sequence nor a dense
`1..N` row number.

### 3. Persist the registry beside canonical dataframe truth

The JDBC adapter will persist export-owned state in the dataframe SQLite
database, not in the service database. This avoids a cross-database commit gap
between the active canonical set and its slot registry while keeping ownership
in the export application capability.

The target logical schema is:

```text
export_slot_assignment(
  profile, artifact, lifecycle_id, slot, assigned_at_epoch_ms,
  PRIMARY KEY(profile, artifact, lifecycle_id),
  UNIQUE(profile, artifact, slot)
)

export_slot_free(
  profile, artifact, slot, released_at_epoch_ms,
  PRIMARY KEY(profile, artifact, slot)
)

export_slot_state(
  profile, artifact, next_slot, source_generation, updated_at_epoch_ms,
  PRIMARY KEY(profile, artifact)
)
```

The physical migration may use project naming conventions or an equivalent
normalized representation, but it must retain both uniqueness constraints and
an indexed smallest-free-slot lookup.

### 4. Reconcile slots only as part of an eligible export

Expiration itself does not create an immutable slice and does not directly
allocate or release export slots. This preserves ADR-0020's accepted export
trigger policy: automatic export occurs only after ingestion adds new canonical
data.

When an export is eligible, the export use case reconciles the registry against
one active-set generation before opening the immutable slice:

1. release assignments whose lifecycle is no longer active;
2. preserve every surviving assignment unchanged;
3. allocate released slots, smallest first, to newly active lifecycles;
4. allocate any remainder from `next_slot`;
5. record the canonical source generation;
6. read the slice from a consistent active snapshot and materialize
   `export_slot AS id`, ordered by slot where the existing artifact contract
   requires ID ordering.

If canonical membership/generation changes between reconciliation and the
snapshot boundary, export retries rather than publishing a mixed mapping. The
existing export single-flight/saga and immutable-slice completion marker remain
the publication authority.

The JDBC implementation must use bounded set-based operations or staged batch
tables. Per-row round trips and full active-set materialization in the JVM are
not acceptable for the 100k reference envelope. Unique constraints are the
last-resort concurrency guard, not the allocation algorithm.

### 5. Migrate without renumbering the current active projection

On first activation, existing active lifecycles are seeded with their currently
exported `id` values wherever the mapping is unambiguous. This preserves the
current active consumer mapping across upgrade. The allocator high-water mark
starts above the greatest seeded slot, and holes not owned by an active
lifecycle become free slots.

A duplicate or ambiguous seed is an activation error; the migration must not
silently renumber rows. The export-slot policy/version belongs in the export
plan or schema fingerprint. It does not change canonical row identity and by
itself does not require an artifact-identity epoch bump.

### 6. Keep the change inside existing boundaries

No new Maven module, standalone Java library, control event, per-record timer,
or scheduler framework is introduced.

- `core/ioc-application` owns the existing `SnapshotSliceReader` contract and
  the slot-policy fingerprint without JDBC or Spring types; a second port or
  export use case is not introduced because slot resolution is an atomic part
  of opening that snapshot.
- `adapter-store-jdbc` owns the SQLite schema, migration, allocation and
  consistent-snapshot implementation.
- `adapter-sink-csv` continues to serialize the configured external `id`
  column, now supplied by the export slot rather than canonical row identity.
- `bootstrap/ioc-app` wires the existing boundaries.

Expiry/history/provenance writers do not depend on export slots.

## Consequences

Positive consequences:

- downstream IDs remain stable for all still-active records;
- expired records stop consuming scarce/ordered consumer slots;
- canonical lifecycle identity stays correct and non-reusable;
- old immutable slices remain auditable without constraining later slices;
- allocation policy is isolated behind an export-owned port.

Costs and risks:

- export now owns durable projection state and a reconciliation step;
- a slot can identify different lifecycles in different slices, so logs and
  diagnostics must include slice plus artifact/profile context;
- upgrade requires strict seeding and collision checks;
- allocation and snapshot generation must be race-safe and load-tested.

## Rejected alternatives

- **Monotonic never-reused exported IDs:** violates the consumer requirement
  that expired positions become available.
- **Dense `ROW_NUMBER()` on every slice:** renumbers surviving rows after any
  expiry.
- **`MAX(active.id)+1`:** can collide, cannot fill the smallest hole safely,
  and couples the projection to canonical storage identity.
- **Release slots in the expiry transaction:** couples lifecycle maintenance
  to a projection concern and changes state even when no export is requested.
- **Store the registry only in the service DB:** introduces a cross-database
  consistency gap with dataframe truth.
- **Create a job/event per record:** TTL is data state; the export allocator
  only reconciles when export is already eligible.

## Required verification before acceptance

- survivor stability, smallest-hole reuse, multi-hole deterministic allocation,
  no compaction, restart recovery, rollback, and concurrent ingest/export tests;
- upgrade seeding with gaps plus fail-closed duplicate/collision cases;
- immutable-slice proof that historical `slot=1` and later `slot=1` can refer
  to different lifecycles without mutating the former slice;
- artifacts without external `id` remain unaffected;
- 100k profile with SQLite query plans and bounded heap/transaction evidence;
- refreshed published capability, storage, export and operator documentation;
- rerun of the packaged fresh-install and v0.2.0 upgrade stand.
