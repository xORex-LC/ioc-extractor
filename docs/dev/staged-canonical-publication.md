# Staged canonical publication

This is an isolated SQLite adapter candidate, composed explicitly by
`JdbcStagedCanonicalBackend`. The default application still uses native canonical
transactions. The candidate runs the same native mutation kernel in a private
workspace, transfers typed effects in short transactions, then publishes their
visibility and canonical receipt together. Activation and existing-data migration
require separate operational qualification.

## Runtime flow

```text
pinned command -> selective committed snapshot -> private native mutation
  -> bounded hidden chunks -> fence/epoch/deadline validation -> atomic publication
  -> canonical receipt -> service-ledger finalization
```

The shared document workspace factory admits canonical preparation against its
memory, cache and disk budgets. Configuration requires capacity for promotion,
control work and a document lease; admission is nonblocking when another workspace
would exhaust the budget. Hydration selects affected keys, aliases and dependent
graphs; slot reconciliation additionally streams minimal lifecycle membership
headers. It does not copy or diff the whole database.

Byte-size guards reject oversized committed fields before JDBC materializes
them. Private main/TEMP, committed-base requests, immutable stage readers and
attached stage caches are bounded. Native duplicate validation preserves a
stricter private TEMP cap. These are admission/accounting limits, not measured
whole-process resource acceptance.

Canonical leases reserve byte-bounded hydration/transfer chunks and both row
images, rather than hundreds of maximum-size rows. Private canonical main/TEMP
caches are capped at 512 KiB each; configured document caches and public row/field
limits remain unchanged. The document lease limit leaves room for one promotion
and one control working set. Defaults retain the 64 MiB aggregate budget and
256 KiB row / 64 KiB field contract. A full budget rejects canonical admission
instead of waiting while retaining another lease. These bounds do not establish measured
whole-service RSS or throughput.

Disk accounting uses the file walker's observed attributes. SQLite can remove
its known WAL/SHM/journal sidecars between listing and attribute reads; their
disappearance is tolerated. Present sidecars still count against quota. Missing
workspace roots or other owned files, inaccessible entries and symlinks fail
closed; accounting never follows links.

After its pool closes, a finished native workspace is deleted while both root
admission and its file lease remain held. Disposal releases weighted permits
even on filesystem failure. This avoids retaining one database per completed
attempt until the pin-retention deadline. A crash still leaves a private pin for
the existing lease-aware retention backstop; it is never canonical authority.

Capture triggers run only on private native tables. They preserve initial and
final typed values, NULL and cascaded tombstones. An explicit mutation journal
preserves renewals even when timestamps have not changed. Two passes over one
sealed snapshot describe and transfer at most 256 effects / 1 MiB per chunk.

An initially empty rowid family streams final rows directly from the sealed
native table, without a second payload or address representation. Deleted transient rows contribute nothing;
key changes retain only their final address. Nonempty families retain one initial
before-image per changed address and the final typed delta. This avoids copying
every new receipt field-position payload and composite key twice. A native
`WITHOUT ROWID` table or shadowed `rowid` column retains full typed capture.
Earlier artifacts' immutable receipt payloads stay in canonical storage.
Preparation copies their header/markers and reads actual typed-row counts in the
pinned committed snapshot, rather than recopying all payloads and positions.
The receipt dependency epoch protects those counts; the native kernel still
checks marker completeness and actual totals before publication. Receipt
field-position writes flush bounded batches with the same 128-row/1-MiB ceiling.

## Boundaries

All physical mechanics remain in `adapter-store-jdbc`. Application ports,
canonical identity, rank, merge, mutation and sparse-slot policies are reused.
The dataframe database owns canonical visibility, ID reservations, publication
fences, epochs and receipts. The service database continues to own ordered
delivery and retry coordination. There is no new queue or mutation-policy engine.

## Invariants

1. Only versions whose unit and fence are committed participate in reads. The
   latest committed address wins, including tombstones. Matching, active rows,
   projections, snapshots, TTL, history, slots and receipt recovery share this
   predicate. Existing readers keep one SQLite snapshot across publication.
2. Ordinary confirmation publishes per artifact; import publishes all artifacts
   and its receipt together. Hidden chunks never advance visible revisions or
   generations. Private preparation does not hold canonical writer admission.
3. Fresh safe UTC at publication is confirmation authority. An expired selected
   lifecycle, including equality at its deadline, requires a rebuild with new
   non-reusable IDs. Rebuild preserves the admitted command, rank and policy;
   reserved ranges remain burned. Three attempts bound one execution.
4. Immutable pins reject configuration or command drift. Empty initialization refuses
   populated native tables; explicit drained migration adopts their literal facts.
   Reopening requires the same configuration fingerprint.
   Native writers must never write into the candidate's read-only views.

## Failure and recovery

| Boundary | Behavior |
|---|---|
| Private mutation, quota or hidden transfer failure | Roll back private work; cancel hidden effects and persist ownership failure; no partial visibility |
| Changed dependency or expired base | Rebuild from a fresh committed snapshot with the same pins, within the execution budget |
| Stale logical owner | Reject its chunks and publication |
| Publication committed before delivery finalization | Recover from the canonical receipt without reading the input stage |
| Receipt absent or incompatible pins | Fail closed; do not reinterpret the admitted delivery |

Failure auditing uses operational wall-clock time, not safe lifecycle time.
This lets cancellation and ownership release complete after unsafe UTC rejects
publication. That audit timestamp neither confirms a lifecycle nor advances its
safe-time high-water mark. A healthy retry must still pass safe UTC validation.

Interruption also rolls back an uncommitted visibility flip. Failure cleanup
temporarily clears an already-set interrupt flag while it persists cancellation
and releases ownership, then restores that flag for the caller. It never clears
interruption to authorize publication. Physical publication and cleanup failures
remain attached to the original error; a failed cleanup still requires recovery.

Controls reuse native expiry, history, projection, receipt-retention and slot
operations in private selective snapshots. Controls with actual effects advance
epochs and invalidate stale promotion work; an empty maintenance result validates
its dependencies but leaves business epochs unchanged. Their fixed cycle time remains literal; promotion timestamps and
renewal deadlines use publication UTC.

Physical cleanup advances persisted keyset cursors, checking at most 256
addresses and deleting at most 256 rows per transaction. A tombstone remains
until every other version of its address is gone, including hidden versions.
Terminal transfer facts/chunks/dependencies and obsolete attempt identities can
be reclaimed independently. Committed typed facts, live attempts and current
attempt identities remain authoritative. Headers/pins and deadline anchors are
retained conservatively. Operational cleanup rotates through sorted typed families,
transfer journals and control headers using persisted progress. Each backstop call
scans/deletes at most 256 rows per page. It reclaims an unreferenced terminal control
header only after all versions, deadline references and transfer children are gone.
Promotion identities/pins remain retained: deleting them requires durable terminal
coordination acknowledgement, not an arbitrary age cutoff. SQLite WAL snapshots
protect readers across cleanup.

## Operational candidate and drained conversion

`JdbcStagedCanonicalOperations` is an adapter-private owning scope, with no default
Spring selector or production migration registration. It acquires an exclusive
local file lease beside the dataframe database. Another operational candidate
cannot open the same file while that lease is held. It supports local file-backed
SQLite only. The lease coordinates cooperating candidate instances; it does not
stop an old native application. Stop every native process before conversion.

The explicit conversion entry point checks existing service ledgers for unfinished
ingest/import, admission finalization, export and publish work. An empty inbox alone
does not establish drain. In one offline dataframe transaction it checks native
format 12, the exact identity catalog and public ID authority, seeds internal ID
high-water marks through the existing allocator, copies native facts with the same
bounded typed transport framing, replaces tables with committed views, pins the
configuration and stamps isolated format 13. This is an offline conversion, not a
short runtime writer unit. Failed conversion rolls back format, views, copied facts
and allocator initialization together. Historical timestamps, deadlines, IDs,
receipts, origins and slots stay literal. Conversion never renews an expired row.
The normal format-12 migrator rejects format 13 before schema reconciliation.

Opening an operational scope starts with readiness closed. A bounded recovery page
first releases a committed abandoned owner or audits/releases an uncommitted owner,
then cancels at most the requested number of pending units. Repeated pages continue
after process loss. They do not finalize the service ledger or reapply a delivery;
the existing receipt-first services retain that authority. The exclusive process
lease, not a timeout or timestamp, establishes that the former operational owner
has ended. Readiness opens only when this startup backlog is drained. Health checks
SQLite integrity/settings, format, configuration pins, safe-clock status and the
last maintenance outcome; hidden work during a healthy live invocation is normal.

Work borrows the backend within `use` and must not retain ports beyond the scope.
Shutdown refuses to release the lease while a borrowed invocation is running.
The caller cancels and drains its owned workers first. The scope owns no extra
executor, queue, database pool or retry engine. Startup and maintenance pages are
hooks for the existing lifecycle/backstop composition; that composition is not
enabled in the default application before resource qualification and ADR acceptance.

Rollback restores a coordinated stopped backup of both databases, owned files,
configuration and executable. Never lower `user_version`, copy only the dataframe
file, or run the old binary over converted state. See the
[operational rehearsal](../guides/staged-publication-rehearsal.md).

## Extension and executable sources

Add a native family to the persisted catalog, its selective hydration and typed
capture, then compare every transported table with the native oracle. Extend
ports through the existing application contracts rather than exposing private
workspace connections.

- [Candidate composition](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcStagedCanonicalBackend.java).
- [Native import oracle and TCK](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java).
- [Physical publication checks](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/SqlitePublicationExperimentIT.java).
- [Native crash and concurrency matrix](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/StagedPublicationFaultIT.java).

The fault suite owns killable child JVMs, reopens both SQLite databases after
forced process loss, and advances the real import service ledger from its
canonical receipt with the stage deleted. It checks all transported native
families, rather than using CSV equality as a commit oracle. Deterministic
crash/concurrency evidence does not establish sustained WAL, memory or latency
budgets, automatic takeover eligibility, or default-runtime activation.

Update this document when visibility, publication time, resource ownership,
recovery or activation boundaries change.

## Related documents

- Proposed decision: [ADR 0041](../ADR/0041-sqlite-staged-publication-experiment.md).
- Native storage: [storage](storage.md).
- Module reference: [adapter-store-jdbc](../../adapters/adapter-store-jdbc/README.md).
