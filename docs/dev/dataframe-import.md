# Managed dataframe import

Managed dataframe import consumes operator-provided CSV deliveries from dedicated
local or SMB inboxes. Each stabilized file is one occurrence: it is claimed,
copied into a private immutable local snapshot, recognized against exactly one
versioned source contract, staged on disk and promoted into canonical SQLite
truth by one cross-artifact transaction.

The feature is disabled by default. Enabling it requires service and dataframe
JDBC storage plus a complete source, authority and contract catalog. Managed
import does not restore the retired hand-filled CSV lookup/seed path.

## Runtime flow

```text
periodic complete listing <------------------------------ correctness backstop
        ^                         local watch / SMB CHANGE_NOTIFY (doorbells)
        |
stabilized candidate
  -> reserve durable delivery ID and global sequence
  -> claim source ownership
       local: atomic move into the private processing directory
       SMB: server-side rename into inbox/.ioc-managed-import/processing
  -> create and pin a private immutable local snapshot
  -> exact-one recognition and strict streaming mapping
  -> sealed per-delivery SQLite stage
  -> active-only match, merge and preferred-slot planning
  -> one dataframe transaction plus import_commit receipt
  -> atomic protected source/report terminal unit
  -> local or remote terminal/quarantine disposition
```

Only the durable minimum nonterminal sequence may advance. A retrying head keeps
its place; executor order, filesystem event order and SMB notification order are
not business authority. The ledger-first claim reservation and every subsequent
checkpoint are idempotent, so startup recovery moves an incomplete delivery
forward from durable evidence rather than restarting it as a new occurrence.

`as-is` mapping runs only transforms declared by the pinned contract and retains
the `ABSENT`, explicit `NULL` and `VALUE` cell states. `processed` delegates the
mapped logical row through the ordinary refang, extraction, classification and
artifact mapping policies. Pipeline-owned identity, routing and match fields
replace imported copies, while operator-owned metadata retains tri-state merge
semantics. Every populated IOC carrier must contain one whole-cell IOC; compound
URL/IP or multi-hash fields remain one atomic artifact row.

## Source ownership

Local and SMB sources implement the same application lifecycle port, but retain
transport-specific ownership mechanics.

For SMB, the adapter first compares the listed candidate's leaf, size,
last-write time and server file ID, then renames it on the server into the
adapter-owned namespace. The destination name is a SHA-256 token of the durable
delivery ID, so it does not disclose the producer filename. Existing producer
and destination objects are a collision and fail closed; neither is overwritten.
After a restart, the same delivery can adopt the already-renamed object.

Materialization opens the claimed file for read while excluding concurrent write
sharing, downloads into a private `.part`, checks size and the remote evidence
before and after the stream, hashes and fsyncs the local file, then atomically
publishes `snapshot.csv`. Canonical processing can start only from that pinned
snapshot. A terminal outcome moves the remote processing object to `terminal`
or `quarantine` only after the protected local source/report unit exists.

Sync and managed import share one lazy endpoint-keyed SMB session pool. Operations
for one endpoint are serialized so idle close or reconnect cannot invalidate an
active handle; different endpoints remain independent. `CHANGE_NOTIFY` uses
dedicated long-poll sessions and emits only source-scoped listing hints. Polling
continues when notifications are disabled, lost, duplicated or reconnecting.

## Boundaries

- `ioc-application` owns contracts, exact recognition, tri-state mapping,
  staging/promotion/finalization orchestration, durable-order policies and
  storage/transport-neutral ports. It has no CSV, JDBC, SMBJ or Spring imports.
- `adapter-csv` owns strict charset decoding, Commons CSV dialect construction,
  streaming rows and import transforms.
- `adapter-store-jdbc` owns service schema v9, private sealed workspaces,
  active-only canonical matching/mutation, sparse requested slots and the
  dataframe `import_commit` receipt.
- `adapter-ingest` owns local claim/snapshot/terminal filesystem mechanics and
  local WatchService hints.
- `adapter-transport-smb` owns SMB sessions, server rename, object evidence,
  durable local materialization, remote disposition and `CHANGE_NOTIFY`.
- `ioc-app` is the only composition root. It shares the ordinary-ingest/import
  recovery barrier and post-commit canonical-change path.
- `ioc-application-tck` owns reusable ledger and canonical-promotion contracts.

## Invariants

1. **A delivery is an occurrence, not a digest.** Re-delivering equal bytes
   reserves a new ID and global sequence.
2. **Recognition is exact-one.** File names, catalog order and score-based
   fallback do not select a contract.
3. **Ownership precedes bytes.** No staging or canonical path accepts a producer
   file directly; it requires an immutable snapshot created after a successful
   local move or SMB server rename.
4. **Rows stream off heap.** Parsing uses a synchronous callback and bounded
   workspace batches. Hard field, record, row, column and snapshot limits apply
   before unbounded growth.
5. **One input row is one logical atomic unit.** A rejected branch rejects the
   row's complete deterministic fan-out. Accepted branches promote together.
6. **Canonical promotion is one transaction.** All public mutations, lifecycle
   effects, aliases, preferred export slots, revisions and the receipt are
   before-or-after across every affected artifact.
7. **Only active rows match.** Missing import rows never delete, modify or renew
   local rows. Historical lifecycles do not participate.
8. **A sealed stage is immutable evidence.** Snapshot and contract pins, plan
   hash, counts, SQLite integrity and stage digest must agree before promotion.
9. **Events are latency hints.** Complete source listing, the service ledger,
   dataframe receipt and periodic reconciliation recover lost process-local
   notifications.
10. **Terminal evidence is a unit.** The protected source and safe JSON report
    are published atomically; raw IOC cells, paths and digests do not enter
    reports, health details or diagnostics.

## Failures and recovery

| Situation | Behavior | Recovery authority |
|---|---|---|
| source changes before claim, open producer blocks delete sharing, or claim destination exists | fail closed and schedule a non-blocking retry | candidate evidence plus delivery ledger |
| disconnect after SMB rename | adopt the delivery-token processing object and continue materialization | remote private namespace plus ledger delivery ID |
| disconnect or mutation during download | discard `.part`; never publish a snapshot | remote object evidence and next retry |
| malformed bytes, dialect/header mismatch, zero/ambiguous recognition or hard parser limit | reject the whole delivery before canonical promotion | pinned snapshot and contract catalog |
| invalid logical row | retain only safe row/code evidence; apply configured row-failure policy | sealed stage |
| crash before dataframe commit | resume/rebuild from the latest service-ledger checkpoint | ledger, snapshot and sealed stage |
| crash after dataframe commit | observe `import_commit` and finalize without applying mutations twice | dataframe receipt |
| event or notification loss | wait for the next bounded full listing or durable-head reconcile | periodic backstops |

Contradictory durable evidence fails the startup recovery barrier. Ordinary
ingest and managed import both remain closed until their recovery and lifecycle
admission complete.

## Operator surfaces

- `ioc import validate` applies the real recognizer/parser/mapping rules without
  claiming a source or mutating durable state.
- `ioc import status` reports aggregate backlog and safe head retry facts; it
  cannot skip, reorder or force-complete a delivery.
- `ioc import replay` creates a new occurrence with a causal link to retained
  terminal evidence.
- Actuator health distinguishes failed recovery, safely degraded retry/backlog
  and normal progress. Metrics remain low-cardinality.

## How to extend

- Add a CSV shape to the compiled catalog and registered key/transform families;
  do not infer structure from filenames.
- Add a new wire format in its own parser adapter while preserving strict decode,
  streaming and the immutable snapshot boundary.
- Add a transport by implementing the source lifecycle and optional doorbell
  ports. The transport must prove ownership, stable materialization, idempotent
  disposition and complete-listing recovery.
- Extend processed mode through `ProcessedImportRowPreparer`; keep the
  application port framework-free and qualify new carrier/provider semantics
  against the ordinary pipeline before enabling a contract.

## Sources of truth

- Decision and invariants: [ADR-0024](../ADR/0024-managed-dataframe-import.md).
- Application contracts: `application.dataframeimport` and
  `application.port.*.dataframeimport` in `ioc-application`.
- CSV boundary: the `adapter-csv` module and package READMEs.
- Durable state and promotion: `adapter-store-jdbc` README, ledger TCK and
  canonical import integration tests.
- Local ownership: `adapter-ingest` import lifecycle classes.
- SMB ownership: `adapter-transport-smb` import lifecycle and live contract
  tests.
- Runtime configuration: `IocProperties.DataframeImport`,
  `DataframeImportConfiguration` and `DataframeImportRuntimeConfiguration`.

## Related documents

- [storage.md](storage.md) — canonical/service database roles and migrations.
- [processing.md](processing.md) — ordinary processing and reusable preparation.
- [ingestion.md](ingestion.md) — ordinary whole-file ingest ownership/recovery.
- [event-coordination.md](event-coordination.md) — events-as-hints doctrine.
- [operator guide](../guides/dataframe-import.md) — configuration, intake,
  status, replay and incident procedures.
- [ADR-0015](../ADR/0015-retire-legacy-csv-lookup-storage.md) — legacy CSV
  lookup/seed retirement preserved by managed import.
