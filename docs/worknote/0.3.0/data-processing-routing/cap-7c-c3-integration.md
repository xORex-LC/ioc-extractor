# CAP-7C C3: native kernel integration

Status: isolated SQLite candidate implemented. Native-port deterministic
qualification is recorded below; repository completion requires fresh gates on
the final proposed HEAD. C2 is closed; C4–C6 and G6 remain separate. This change
neither activates the candidate nor migrates existing data.

## Implemented boundaries

`JdbcStagedCanonicalBackend` composes the existing application ports entirely
inside `adapter-store-jdbc`. Ordinary confirmation publishes per artifact;
managed import publishes all affected artifacts and its canonical receipt
atomically. The existing mutation kernel, import planner, identity/rank/merge
rules and sparse slot allocator still decide business behavior.

The native writers share an internal `JdbcCanonicalPreparationContext` for
canonical ID reservations, explicit renewal capture, slot membership overlays
and bounded stage reads. Their public constructors retain the existing native
runtime behavior. No new dependency, Maven module, bootstrap switch or
alternative mutation-policy engine is introduced.

Selective hydration reads affected committed rows, aliases, origins, sources,
receipts and slot namespaces into a private native database. Slot reconciliation
streams minimal lifecycle membership headers; it does not copy every public
row. Borrowed canonical connections restore their cache/TEMP policy after the
owned snapshot closes. No whole-table copy or diff runs on the candidate path.

Private native DML capture records the original committed value and final typed
value/tombstone for each changed address, including cascades and moved keys.
The explicit renewal journal preserves renewals whose timestamps did not change.
Two passes over one sealed snapshot describe and transfer at most 256 effects /
1 MiB per physical chunk. Typed digests preserve field boundaries and distinguish
NULL, text, numeric and binary values. Hidden chunks and the final visibility
transition use the same shared `JdbcPublicationProtocol` exercised by C2.

All canonical readers and controls use one committed-version predicate:
matching, active/stream/snapshot reads, projection, indexed deadlines, expiry,
slot reconciliation, history and receipt retention. A unit commits its receipt
and visibility in one short transaction; existing WAL readers retain their old
snapshot across publication and cleanup.

## Temporal, recovery and resource contract

- Fresh safe UTC at publication confirms or renews a lifecycle. Equality with
  its deadline is expired. Expiry during preparation requires a fresh native
  plan with new non-reusable IDs; the admitted observation, rank and policy pins
  remain unchanged. Authoritative reservations stay burned after failure.
- Durable logical ownership, fences and dependency epochs reject stale workers
  and invalidate stale preparation. One execution has at most three attempts;
  exhaustion cancels hidden state, records failure and releases ownership for
  ordered retry. It does not silently fall back to a large native transaction.
- After commit, import recovery reads the canonical receipt without requiring
  the stage. The service database retains delivery/retry authority; canonical
  visibility, lifecycle, reservations and receipts remain dataframe-owned.
- Canonical workspaces share weighted nonblocking admission, leases and disk
  quotas with document preparation. Configuration requires headroom for a
  promotion, concurrent control work and a document lease. Private/base/stage
  caches, disk-backed TEMP requests and row/field materialization are bounded.
  These accounting limits are not whole-process capacity acceptance. Finished
  native scratch databases are disposed under root admission and their file lease;
  disposal failure releases permits, while crashed pins retain the existing
  lease-aware retention backstop.
- Cleanup uses durable keyset cursors and bounds both scanned addresses and
  deleted rows. It retains pending/current committed versions and tombstones
  that still dominate other versions. Acknowledged transfer journals and
  obsolete attempts can drain while current attempt identity, headers, pins
  and TTL anchors remain authoritative.

The exact SQLite driver contract matters: native
`PreparedStatement.executeUpdate()` preserves direct affected-row counts under
capture; plain `Statement.executeUpdate(sql)` includes trigger work. Native
cardinality checks retain prepared statements.

## Deterministic qualification

The inherited import and lifecycle TCKs execute on the staged candidate.
The lifecycle TCK checks one published time/deadline across a multi-row
confirmation using a clock that advances on every read. Replay makes no new
clock read. A requirement for exactly one preliminary read belonged to the
former transaction implementation and contradicted the approved publication-UTC
contract; suite discovery/tags, coverage floors and ratchets were preserved.

The existing Failsafe suites cover:

- corrected native-table equivalence for ordinary multi-artifact confirmation
  and import fan-out, clears, COALESCE warnings, source provenance, aliases,
  origins, revisions/generations, sparse slots and complete receipt fields;
- hidden visibility, publication between pinned reads, receipt-only replay
  without the input stage, native expiry/projection/history/receipt controls;
- deadline-equality rebuild without resurrecting an expired lifecycle, burned
  IDs after a hidden-chunk failure, id-less row/history ID non-reuse;
- three successive epoch invalidations, cancellation and retry with retained
  pins, and changed pins rejected before ownership without cancelling another
  pending attempt;
- weighted admission, pin-failure permit release, thread confinement, bounded
  termination, oversized committed fields, interruption/quota rollback, SQL-
  looking Unicode values and binary payloads through selective hydration;
- unsafe UTC at publication cancels hidden work and releases logical ownership;
  failure audit time is operational and never lifecycle confirmation authority;
- empty ordered values without origins, rank-only advances, malformed sealed
  metadata/carriers and stale export plans fail or complete with native evidence;
- persisted cleanup cursor progress past a retained prefix, pending-state
  retention, receipt cascade reclamation and surviving TTL anchors;
- incomplete/ambiguous catalogs, configuration drift, populated initialization,
  inactive lifecycle, stable-key requirements and bounded maintenance pages.

The JDBC integration cohort executes 419 tests with zero failures/errors and
one explicitly opt-in lifecycle-load skip. Focused suites include 56 import/TCK
tests and 45 physical-protocol tests with zero failures/errors/skips. Seven
inherited lifecycle TCK tests also pass. The suite inventory is unchanged. Failed development runs remain in `.dev/c3-*.log`;
fixture type/state errors, insufficient fixture headroom and obsolete clock-call
assertions were corrected without automatic retries or weakened gates. A full
reactor run exposed a race in the existing daemon E2E manual driver: stopping
only its file poller did not stop durable journal dispatch. Each scenario now
gets a fresh Spring context; manual scenarios close the bounded dispatcher,
while the watched-inbox scenario retains the normal autonomous runtime. The
three-scenario focused suite passes without retries.

The complete reactor at implementation reference `9b4aaa46` passed functional
suites and test-report union integrity (222 fast and 78 Failsafe suite identities,
including five external shells; 295 deterministic-offline identities). Its JDBC coverage scope
passed the unchanged absolute ratchet: 446 missed branches against baseline 468.
The complete coverage gate still rejected one `adapter-ingest` branch above its
baseline after autonomous fixture isolation. A dedicated non-file regression now
checks matching directories and vanished files without depending on a background
poller; its four-test focused suite passes. The failed aggregate gate is retained,
not relabelled as success.

Final repository completion requires `make verify`, `make pmd-analysis`,
`make pmd-watchlist` and `make docs` after this documentation synchronization.
`make context` and the generated local verification records identify the final
HEAD, result and worktree freshness; focused checks never substitute for those
gates. No test inventory, analyzer scope, floor or ratchet was relaxed.

The [exact SQL analyzer review](cap-7c-c3-sql-review.md) records 25 new SQL
false positives and seven changed slot-registry anchors. All acceptances have
exact raw identities, method-local presentation selectors and concrete review
triggers. Neither detector scope nor coverage baseline was relaxed.

## Remaining promotion gates

C4 owns the complete crash/cancel/conflict/process-restart matrix, including
service-ledger wiring and recovery of abandoned ownership.
The [C4 execution report](cap-7c-c4-fault-matrix.md) now records that implemented
deterministic matrix and its remaining scope limits. C5 owns paired
whole-service capacity comparison, sustainable invalidation/progress, physical
writer occupancy, eligible waits, read amplification and total heap/RSS/WAL/disk
cost. C6 owns drained migration/backup rollback, readiness/health, runtime
composition, cleanup scheduling and conservative header/pin/anchor compaction.
These outcomes are not inferred from deterministic C3 checks.

The proposed [ADR 0041](../../../ADR/0041-sqlite-staged-publication-experiment.md)
and [transaction design](cap-7c-transaction-design.md) remain protocol owners.
The published capability is [staged canonical publication](../../../dev/staged-canonical-publication.md).
No installed service, operator configuration or existing dataframe was changed.
