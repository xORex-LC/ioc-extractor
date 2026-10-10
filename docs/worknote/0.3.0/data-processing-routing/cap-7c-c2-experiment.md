# CAP-7C C2: physical SQLite publication experiment

Status: physical primitive implemented in test scope; C2 canonical-port and
mutation-kernel qualification remains open. C3 production integration has not
started. This is neither a capacity acceptance nor an activated schema.

## Implementation

`SqlitePublicationExperiment` and `SqlitePublicationExperimentIT` live in the
existing JDBC adapter's test source set. They use the pinned sqlite-jdbc
3.53.2.0 driver, the production `SqliteDataSourceFactory`/PRAGMA policy, a
temporary file-backed SQLite database and `JdbcWriterAdmission`. Experimental
tables are explicitly created only in that database. Its `user_version` remains
zero; dataframe v12, service v14, bootstrap wiring and production writers are
unchanged. No dependency, Maven module or PostgreSQL path was added.

The primitive has no matching, merging, classification or slot-allocation
business logic. Addresses and payloads are deliberately opaque. The experiment
does **not** substitute these payloads for typed canonical storage.

- A logical unit has immutable identity/pins, an attempt fence, declared chunk
  and contribution counts, a minimum permitted deadline and a sealed state.
- Each attempt receives a durable monotonic stamp. Hidden chunks fold pending
  contributions and record an ordered SHA-256 journal with length-framed UTF-8
  values; `NULL` and empty strings have distinct digest encodings.
- Chunk replay checks its original digest and does not apply it again. Replaying
  an older chunk cannot overwrite a value produced by a later one. Changed
  replay, gaps, undeclared scopes and exceeded contribution/byte bounds fail
  and roll back the entire chunk.
- A chunk is limited to 256 contributions and 1 MiB of framed values in this
  probe. These are experiment bounds, not new operator configuration or an
  accepted production capacity.
- A rebuild advances fence/stamp, keeps business pins and can declare a changed
  contribution manifest. Facts from the previous fence remain invisible.
- Publication checks SEALED, scope epochs and strict deadline inequality,
  advances touched scope epochs, allocates publication sequence and writes its
  receipt/header in one caller-owned transaction. An empty unit may publish an
  outcome receipt. A cancelled unit cannot publish.
- Readers select only committed current-fence versions. Own pending reads use
  a separate connection over one pinned committed base; a pending tombstone
  overrides the base rather than revealing its old value.
- The physical lifecycle probe uses the real `RecordValidityPolicy`: new/renewed
  versions reference a header deadline calculated at publication; non-renewed
  versions retain a committed old anchor. Anchor references participate in the
  chunk digest. Missing/pending inherited anchors fail; inherited deadlines
  automatically lower the publication guard. The final transaction writes one
  header deadline and does not rewrite row deadlines.

The receipt here is an **opaque experiment header**, not `import_commit` or a
complete `CanonicalImportResult`. Reopening it without preparation input proves
physical persistence/idempotency only. The real canonical receipt and delivery
finalization remain part of C2/C3 port qualification.

## Findings incorporated into proposed ADR 0041

First, ordering a key's versions by joined publication sequence requires sorting
its retained history. The candidate now indexes `(family, scope, key, attempt
stamp DESC)`. Scope epochs must match at attempt creation/rebuild and again at
publication; future epoch pins are rejected. If another attempt publishes the
same scope first, the older attempt must rebuild with a newer stamp. This makes
index order agree with commit order for a shared address without an N-fact
sequence update at publication. Final publication sequence still records the
actual global publication order.

Second, multiple accepted inputs can contribute to one source summary or other
pending fact. Disallowing repeated addresses would force another mutation
accumulator ahead of the existing kernel. Instead, only the chunk journal and
committed versions are immutable; pending contributions fold until seal.
Retrying a journalled chunk never applies its contribution twice.

The indexed lookup excludes artifact-wide scans and a temporary ORDER BY
B-tree on the retained exact-driver query plan. This does not bound the number
of cancelled/pending versions it may skip, nor qualify active/nearest-deadline
queries at reference size. A small deadline experiment now verifies indexed
header/anchor lookup, exact equality and latest-version-before-activity ordering;
retention, expiry and reference-size typed lifecycle costs remain explicit gates.

## Deterministic checks

The new Failsafe suite covers:

1. Pending updates retain old committed values; pending inserts and receipt
   headers are absent. Row/alias/source/origin/revision/slot/warning families
   become visible together. A pinned reader retains its old snapshot.
2. Changed business pins and stale attempts cannot rebuild, append, publish or
   cancel the current attempt.
3. Incomplete manifests, gaps, changed chunk replay, undeclared scopes and
   excess declared/byte contributions roll back.
4. Equality at the minimum deadline rejects publication; semantic epoch changes
   require rebuilding. Failed publication does not consume a committed sequence.
5. An earlier-created unit rebuilt after a later publication gets the newest
   visible value; tombstones cannot reveal historical values.
6. Future/stale initial epoch pins are rejected.
7. Three contributions to one source address fold; replaying the first after
   the second preserves the second, and publication retains the third.
8. Slot-namespace changes invalidate a plan; an unrelated namespace does not.
9. Sixty-four retained committed versions plus abandoned pending state preserve
   latest-version/tombstone behavior.
10. Own pending updates, clears and inherited values overlay one pinned base.
11. Injected failure after the visibility/header writes rolls back; reopening
    the database permits idempotent header replay without preparation input.
12. Empty outcome publication works; cancelled facts remain invisible.
13. An owned worker streams 4,096 facts in sixteen chunks. A timed CONTROL
    transaction actually writes an unrelated control scope and commits between
    the first and second chunks. Query-plan checks
    require indexed fact search with no full fact scan or ORDER BY sort.
14. A self-anchored row requires a validity policy, publication stores its
    deadline, and equality is inactive. A failed validity publication rolls back
    its sequence/receipt.
15. A newer version with shorter validity expires without exposing an older
    still-valid version. Nearest deadline respects surviving keys and tombstones.
16. A non-renewed patch retains its old deadline; publication at that deadline
    fails. The nearest query must use header/anchor indexes without an ORDER BY
    sort.
17. A pending or missing inherited anchor cannot be used; the failed chunk
    rolls back and can be correctly restaged with its own anchor.

Run through the normal facade:

```bash
make test-one MODULE=adapters/adapter-store-jdbc TEST=SqlitePublicationExperimentIT
```

The first physical run passed 13 tests; the final deadline-focused run executed
17 tests with zero failures/errors/skips in 1.361 seconds. This is functional
probe execution time, not a throughput or writer-occupancy result. Final repository evidence comes
from `make verify`, `make pmd-analysis`, `make docs` and exact-HEAD `make context`.

The first full verify at `6a240d89839f70662adb9ae6684c118d0bc3874d` passed
functional/report-integrity checks but failed the ingest missed-branch ratchet
(136 versus baseline 135). [Retained failure](qualification/capacity/cap-7c-c2-first-gate.json)
records its identity and local log digest. The uncovered file-ledger transition
was refusal to archive FAILED. Shared `IngestionLedgerContractTest` now directly
asserts this refusal and preservation of the original failure, for both file
and JDBC implementations. No production logic, floor or ratchet is changed;
this is an explicit supported-state regression, not a retry of the failed gate.

The suite has a containing timeout, timed latches/futures, unconditional worker
release/cancellation and an asserted bounded shutdown. The same change reviews
the source inventory: one additional deterministic integration class,
222 fast / 78 integration / 5 external / 295 deterministic classes. No coverage
floor, analyzer exclusion or production baseline is relaxed.

## Typed bridge to the existing kernel

`SqliteKernelPublicationExperiment` extends the physical experiment in test
scope. It uses a fresh migrated private SQLite working database, selectively
hydrates one requested record and its aliases/source summaries/field origins
from a pinned committed snapshot, and invokes the unchanged
`JdbcCanonicalMutationEngine`. The fixed probe schema has one artifact, one
record/match definition, a deferred public ID and an ordered `name` field. It
is not a full-database clone, a new business mutation implementation, a generic
persistence API or a production dataframe format.

A bounded typed snapshot is written to hidden row/alias/source/origin/history tables.
The same fenced publication header makes all of them visible together. Native
kernel `UPSERT` and affected-row checks run against real private tables; public
compatibility views are read-only. The existing `JdbcCanonicalMatchPlanner`
and `JdbcCanonicalStoredRowReader` consume the published versions without a
substitute matcher/decoder. Typed snapshots are limited to 256 total rows and
1 MiB of framed values across all families, not 256 rows per family. The chunk
payload includes a SHA-256 digest over table/column names, deterministic row
order, framed values (including NULL), unit/fence and time-anchor metadata.
These are experimental bounds, not approved production configuration.

Publication samples the actual `JdbcLifecycleClock` inside the owned final
transaction and uses the existing fixed-validity policy. New lifecycle first
confirmation and creation time refer to the publication header; renewal keeps
the prior first-confirmation anchor and updates last confirmation/validity.
A non-renewing import patch retains the previous renewal/deadline anchor and
last-confirmation timestamp even when its publishing header is newer. Its
inherited deadline automatically constrains publication, without relying on a
caller-supplied minimum. Source summary anchors follow occurrence-count changes, not timestamp equality:
two confirmations at the same wall-clock value still increment provenance.
Committed replay checks the receipt before sampling fresh UTC, so an unsafe
wall-clock rollback cannot prevent receipt-only recovery.

Six additional deterministic cases exercise the bridge:

- Two confirmations of one IOC use the actual kernel's insert/update outcomes,
  ordered winner and source count; pending versions are invisible to the real
  matcher and a pinned reader, then decode correctly after publication.
- Preparation just before a deadline is rejected at publication equality.
  Rebuilding the same unit at a new fence invokes the kernel's real restart,
  archives the old lifecycle, its compact source summaries and field origins,
  then publishes history atomically with the new lifecycle. Real dataframe-owned
  row/lifecycle allocators commit reservations before private work; the rejected
  confirmation burns its reserved IDs rather than returning them. Failed publication rolls back the safe
  clock advance as well as visibility/receipt state.
- One requested row is hydrated while an unrelated committed row remains
  visible but absent from the private working set. A later completion with an
  older registration retains the newer ordered-field origin, increments its
  source count and preserves earlier time anchors.
- A real `MANAGED_IMPORT` registration feeds the existing ordered import mutation
  session. The patch changes public fields and provenance while retaining the
  original lifecycle confirmation timestamps and validity deadline.
- A non-renewing patch prepared before its inherited deadline is rejected at
  publication equality; its fields, source contribution and receipt stay hidden.
- Combined-family row overflow and multibyte UTF-8 overflow roll staging back,
  leaving no publication header, typed row or public version.

This bridge does **not** close C2. The deadline case exercises the actual
allocators for one artifact with a configured public ID; other cases supply
explicit fixture IDs. Artifacts without a public `id` still need a durable
canonical-row reservation boundary. A private SQLite AUTOINCREMENT sequence
must not allocate authoritative IDs independently: selectively loaded rows
omit other artifacts' records, expired history and previously burned ranges.
Upgrade must preserve the existing sequence high-water as well as active/history
IDs. Reuse the existing reservation mechanics where valid, but do not infer
that a public-ID allocator's history-column contract also covers an id-less
artifact. Private history IDs need the same explicit durable ownership review.

The bridge uses one key definition, one ordinary confirmation path and a
synthetic experiment receipt. Alternate match definitions, complete fan-out merge planning, production receipts/slots,
retention and restart of the private working set remain open. Published history
is a read-only experiment view; production retention writes are not supported
by that view and require their own versioned persistence integration.
The modified isolated database advertises the current migration baseline only
so existing clock/registration owners can be exercised; no production migrator
installs these probe views. Do not open an operator database with this helper.

## Remaining C2 gate before C3

### Internal canonical IDs without a public ID

`SqliteInternalIdReservationExperiment` qualifies initialization of an id-less
artifact's internal canonical space under exclusive protocol ownership. It
reuses `JdbcArtifactIdAllocator.reserve` for the committed range transition.
Initialization takes the maximum of active canonical IDs, history
`former_row_id`, the native AUTOINCREMENT sequence and retained reservation
state. It does not apply the public allocator's incompatible `history.id`
initialization formula. Private insertion seeds only a previously empty or
lower private sequence from the reserved range; it rejects a private high-water
above the reservation instead of silently lowering it.

Three independent legacy fixtures make active, history and native sequence
high-water dominate in turn. All reserve 171–173, discard that attempt, remove
retained history, then insert 174 through the real native id-less kernel and
reserve 175 through a recreated allocator. Epoch drift and private sequence
rollback are rejected. This is a reservation experiment, not a production
migration. Mixed native AUTOINCREMENT writers and range-reserving publishers
are unsupported; C3 must route every writer through the same durable authority.
History-row IDs still need their own separately scoped reservation integration.

### Native initial fan-out import and canonical receipt

`SqliteImportPublicationExperiment` transports the typed effects of the actual
`JdbcCanonicalImportWriter`, including its shared kernel and merge planner,
from a fresh private database. Its separate publication database exposes all
effect tables through committed-unit read views. This is explicitly an
**empty-base experiment**: it is not an existing-data migration, selective
hydration implementation, update/delete version resolver or runtime adapter.
No opaque serialized business row or replacement import mutation algorithm is
introduced. Native constraints and update counts still run in private tables.

The additional existing-contract-suite case uses 60 logical rows with two
fan-out artifacts, one COALESCE member warning and one rejected row. Real slot
reconciliation allocates 60 unique namespace slots and records the occupied
requested-slot fallback. Independently committed hidden typed chunks keep
rows, aliases, slots and receipt children invisible. One publication makes
them visible together; a pinned reader retains the old snapshot. The real
safe clock owns publication UTC, including first/last confirmation, validity,
source summaries, slot timestamps and complete import receipt timestamps.

Every transported typed table is compared, including NULLs, internal identities,
keys, counts, slots, revisions, generations and receipt evidence, with a second
corrected current SQLite writer using the same stage and publication UTC.
After deleting the stage, the production canonical import port returns
ALREADY_COMMITTED and the actual service ledger advances to CANONICAL_COMMITTED
through `DataframeImportPromotionService`. Provenance and warning/rejection
evidence remain unchanged. No alternate recovery algorithm is supplied.

ID reservations in this initial fan-out case still originate in its isolated
private writer. They are not a qualified authoritative reservation adapter for
concurrent publications. Slot mutations/readers, expiry/retention writes,
populated-base selective hydration and complete ordinary confirmation receipts
remain C2 integration seams. Do not infer C3 readiness from initial import replay.

### Bounded typed chunks for large source fan-in

The typed kernel bridge now has an additional streaming path. One pinned
private working snapshot is copied in independently admitted chunks of at most
256 typed rows and 1 MiB of framed values. A separate final pointer/seal binds
the complete row snapshot; no public row or child becomes visible between
chunks. The journal digest includes typed values, NULLs and time anchors, not
only generic fact pointers. The older single-transaction path retains its
overflow regressions. These remain experiment bounds, not runtime settings.

A deterministic case confirms one IOC from 520 different sources through the
real mutation kernel, stages three physical chunks, and commits a CONTROL
epoch write after each chunk. It asserts hidden rows/children, atomic publication,
complete selective hydration, and a subsequent source update with 521 total
occurrences while keeping 520 source summaries. Private hydration streams all
children; the old 256-child cap is no longer applied to that private operation.

Chunk restart/replay for typed rows, byte-adaptive partitioning, private workspace
quotas and production retention still need integration. A single typed row over
the byte limit fails closed; chunk overflow never invokes an oversized fallback.
This small test is not a physical-hold or whole-service capacity measurement.

On 2026-10-10 the owner confirmed fresh safe UTC at publication. A preparation
spanning an old deadline must rebuild and create a new lifecycle, with new
internal IDs, rather than renew an expired lifecycle. This intentional boundary
change still requires qualification through the real kernel and ports; the
prototype alone does not authorize production activation.

The physical probe alone does not meet the C2 gate. Next work must connect typed
facts to the real shared mutation kernel and existing canonical ports, covering
ordinary confirmation and complete fan-out import. It must exercise real row,
alias, registration-origin, provenance, slot and receipt outcomes against the
corrected current adapter's independent oracles.

In particular, qualify safe-clock ownership and deadline queries at reference size;
do not use the probe's caller-supplied millisecond value as production time
authority. Existing v12 records have individual persisted deadlines/timestamps;
migration must preserve them, not reinterpret all of them through one new
publication time or the current TTL setting. The anchor-only probe is not an
existing-database migration. Qualify durable logical promotion ownership, the three-attempt
rebuild policy, cancellation/retention and finite progress when expiry or slot
work invalidates preparation. Coarse scope epochs are conservative, not evidence
of adequate mixed-load progress. A passed 4,096-fact probe is not a 100k/1m
whole-service measurement, a five-second writer qualification, a process-kill
qualification, a service-ledger recovery test or a migration/rollback test.

C3–C6 and G6 therefore remain open. Installed service state is unchanged.
