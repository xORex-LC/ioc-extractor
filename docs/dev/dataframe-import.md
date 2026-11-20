# Managed dataframe import

[Русская версия](ru/dataframe-import.md)

Managed dataframe import consumes operator-provided CSV deliveries from dedicated
local or SMB inboxes. Each stabilized file is one occurrence: it is claimed,
copied into a private immutable local snapshot, recognized against exactly one
versioned source contract, staged on disk and promoted into canonical SQLite
truth by one cross-artifact transaction.

The feature is disabled by default. Enabling it requires service and dataframe
JDBC storage, fixed lifecycle validity and a complete source, authority and
contract catalog. Intake runs in daemon mode; installed CLI commands use a
separate oneshot context. Managed
import does not restore the retired hand-filled CSV lookup/seed path.

## Runtime flow

```text
periodic complete listing <------------------------------ correctness backstop
        ^                         local watch / SMB CHANGE_NOTIFY (doorbells)
        |
positive source capability gate
  -> source-scoped READY / TRANSIENTLY_UNAVAILABLE / INCOMPATIBLE
        |
stabilized candidate
  -> reserve durable delivery ID and global sequence
  -> reserve and allocate shared dataframe admission order
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
The import sequence controls FIFO processing; the dataframe admission order is
the shared document/import precedence authority for configured mutable fields.
Service schema v11 writes an admission-reservation marker in the same transaction
that creates a new delivery, so recovery may allocate a missing rank only for a
proved new reservation. A legacy unranked delivery fails closed instead of being
re-ranked.

Service-ledger transitions and their audit timestamps use the greater of supplied
wall UTC and the delivery's durable `updated_at_ms`. Terminal retention starts
from that same nondecreasing timestamp; a clock step cannot shorten its configured
duration. Retry deadlines remain literal absolute UTC. Receipt-only finalization
can advance coordination after commit even when new canonical publication would
be rejected by the independent safe-clock policy.

## Recognition and mapping

Recognition tests every source-allowlisted charset/dialect/header signature and
requires exactly one match. Required headers must be present; optional and
ignored headers are allowed only when declared. Aliases resolve to canonical
header names, so a name plus its alias is a duplicate, not two input fields.
Unexpected, missing or duplicate canonical headers reject recognition. Header
names are exact and case-sensitive; column order does not select a contract.
Every data record must have the header's physical field count. Missing optional
headers and empty cells are therefore different cases, not short-row syntax.
The parser strictly decodes bytes and checks record separators and resource
limits; it does not repair an invalid file by replacing characters.

`as-is` mapping runs only transforms declared by the pinned contract and retains
the `ABSENT`, explicit `NULL` and `VALUE` cell states. `processed` delegates the
mapped logical row through the ordinary refang, extraction, classification and
artifact mapping policies. Pipeline-owned identity, routing and match fields
replace imported copies, while operator-owned metadata retains tri-state merge
semantics. Every populated IOC carrier must contain one whole-cell IOC; compound
URL/IP or multi-hash fields remain one atomic artifact row.
Column `validation` is explicit for both modes; AS_IS alone does not validate
IOC syntax. IOC validators use whole-cell extraction, supported network syntax
parsing and classification without
changing the admitted value. `network-address` accepts supported IPv4, domain
and URL forms; `md5`, `sha1` and `sha256` additionally enforce the hash algorithm
of the target column. `canonical-integer` accepts only the canonical decimal
representation of a signed 64-bit integer. This prevents string identities such
as `10` and `010` from becoming equal public values through INTEGER storage
affinity. Nullable cells bypass value validation and retain their presence state.
The import behavior pin includes the validator semantic revision independently
of the document policy. A validation-semantics upgrade can therefore block
unsealed restaging under an older contract pin; sealed-stage and committed-receipt
recovery retain their ordinary boundaries. Drain nonterminal imports before an
upgrade that changes this revision.
For a declared `source-label-target`, finalization verifies that the source
still comes from the admitted cell. Missing and explicit-null source cells
retain their distinct presence states. A route cannot replace that source target.
Every `processed` contract requires a `processed-route` selecting a named IOC
plan with explicit source-cell inputs and authorized final-field outputs.
A missing binding fails startup even when import intake is disabled; there is no
provider-inferred preparer or alternate processing path. The route cannot expand
source authority or produce a second logical row for the same artifact.
`as-is` remains independent and never invokes IOC routing. The compiled contract
pin includes the selected plan, binding order and processing semantic epoch.
See [ADR 0034](../ADR/0034-required-router-processing-plans.md).
When multiple selected inputs contribute to one artifact row, a null from an
inapplicable mapping provider is no contribution and cannot conflict with
another input's value. After all contributions are collected, a routed artifact
with a non-null output replaces its route-owned targets as a group: a prior
`VALUE` in an output target with no final contribution is cleared. Thus a URL
input routed to a cleaned IP does not retain its old URL carrier or path in the
final key. With no non-null route output, admitted cells remain unchanged.
`ABSENT` and explicit CSV `NULL` remain distinct instructions; distinct
non-null contributions to the same target still reject the logical row.
Warnings from every accepted member of a compatible COALESCE group, including
members whose cells are folded into the representative, are stored with the
canonical receipt. Rejected rows do not contribute accepted warnings. Receipt
recovery reproduces the same warning list without reopening the sealed stage.

Processed staging opens an application-owned preparation session after contract
pin validation and closes it before sealing, including read/map/append failures.
Selected routes reuse successful bounded semantics across rows in that attempt;
all rows still reach ordinary validation, authority checks and staging. Stateless
preparers forward both mapping and source authority through the session. Restaging
starts a new session; sealed-stage and receipt recovery open none. Direct mapping
and advisory validation retain row-local behavior. See
[ADR 0033](../ADR/0033-bounded-semantic-reuse-during-import-staging.md).

### Cell presence, merge and identity

An omitted mapped optional header produces `ABSENT`. An empty cell or exact
configured null literal produces `NULL`; CSV quoting does not escape that
import null instruction. Other text is `VALUE`, including whitespace unless
explicit transforms change it. Declared transforms run in order, then the mapper
checks null/empty again, formula policy and value validation. Formula rejection
checks the first non-whitespace character for `=`, `+`, `-` or `@`; it is not a
spreadsheet sanitizer. `machine-only-preserve` needs explicit source authority.

The effective merge policy is column override, then artifact default, then
contract default, bounded by source authority. For an existing active record:

| Policy | Incoming VALUE | Incoming NULL |
|---|---|---|
| `keep-existing` | retain stored value | retain stored value |
| `fill-missing` | fill only a stored NULL | retain stored value |
| `replace-non-null` | set the incoming value | retain stored value |
| `authoritative` | set the incoming value | clear the stored value |
| `reject-conflict` | fill NULL or accept equality; otherwise reject the logical row | accept a stored NULL; otherwise reject the logical row |

`ABSENT` never changes a field. New records initialize from supplied values;
`keep-existing` does not discard values just because no existing record exists.
Configured ordered mutable fields additionally obey shared observation rank:
an older late completion cannot overwrite a newer registered value.

Within-delivery reduction uses the primary artifact's final record key.
`keep-first` retains the first admitted whole row; `last-nonempty` selects the
last whole row with a nonblank `duplicate-selection-column`, or the first row
if none has one; `coalesce` combines compatible
presence/value contributions and rejects conflicting groups. These do not
override cross-delivery matching. Primary and declared alternative match keys
resolve only active rows; multiple active matches reject the logical row.
A merge that would change a matched record's stable primary identity is rejected
with `IMPORT.STABLE_IDENTITY_CONFLICT`, not silently converted into an insert.

An accepted public mutation confirms validity even with `renew-unchanged: false`.
That flag controls an otherwise unchanged confirmation, not whether changed
data can receive a deadline. An accepted nonblank ordered-field candidate also
requests confirmation, even when its public text already matches. The flag is
not a blanket TTL freeze. TTL-only confirmation does not advance public revision or require a new
immutable export. A retry recovered from the same commit receipt does not
confirm the delivery again.

## Source ownership

Local and SMB sources implement the same application lifecycle port, but retain
transport-specific ownership mechanics.

Candidate selection is intentionally independent of filename and suffix. A
producer-side temporary file must therefore stay outside the configured source
directory until one atomic handoff; a stable `.part` inside the source is still
a delivery candidate.

For SMB, the adapter first compares the listed candidate's leaf, size,
last-write time and server file ID, then renames it on the server into the
adapter-owned namespace. The destination name is a SHA-256 token of the durable
delivery ID, so it does not disclose the producer filename. Existing producer
and destination objects are a collision and fail closed; neither is overwritten.
After a restart, the same delivery can adopt the already-renamed object.

Before listing candidates, each source passes a transport-neutral positive
capability gate. Local sources validate their source root. SMB sources require
the operator-provisioned `processing`, `terminal`, `quarantine` and `probe`
directories, then prove an empty private object can be created, renamed through
the accepted private flow and deleted exactly. A failed source remains closed
and is reprobed by a later bounded reconcile; unrelated sources remain open.

Materialization opens the claimed file for read while excluding concurrent write
sharing and streams it to the common local snapshot store. The source adapter
checks source evidence before and after the stream; the shared store owns the
byte limit, digest, fsync, no-replace atomic publication, reference compatibility
and purge. Canonical processing can start only from that pinned snapshot. A
terminal outcome moves the remote processing object to `terminal` or
`quarantine` only after the protected local source/report unit exists.

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
- `adapter-store-jdbc` owns the service v9 delivery foundation and v11 observation
  reservation, dataframe v12 accepted-row warnings, private sealed workspaces, active-only canonical
  matching/mutation, sparse requested slots and dataframe `import_commit`
  receipt.
- An accepted processed row can carry a bounded, value-free `warnings` list
  independently of rejection `issues`. The sealed workspace stores it separately;
  the canonical transaction copies warnings into the dataframe receipt so
  post-commit report recovery reproduces them without reprocessing input.
  Warnings from the accepted representative and compatible COALESCED members
  are published; rejected rows do not contribute warnings. The workspace retains
  at most `maximum-row-errors` warning details (default 100,000); further warning
  details are omitted by the current writer. This is a detail cap, not an exact
  count of every warning observed during preparation.
- `adapter-ingest` owns local claim/terminal filesystem mechanics, the one
  local-filesystem immutable snapshot-store implementation shared by every
  source transport, and local WatchService hints.
- `adapter-transport-smb` owns SMB sessions, server rename, object evidence,
  claimed-byte access, the positive private-namespace probe, remote disposition,
  exact terminal-source purge and `CHANGE_NOTIFY`. It does not depend on the
  local adapter.
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
6. **Canonical promotion is one transaction.** All public mutations, ordered
   field origins, lifecycle effects, aliases, preferred export slots, revisions
   and the receipt are before-or-after across every affected artifact. A newer
   registered nonempty value wins even when its transaction completes first;
   an older later completion cannot overwrite it.
   Independently committed canonical/lifecycle ID reservations remain in the
   dataframe database under shared writer admission. Failed ranges remain
   burned; sealed CSV staging cannot become canonical ID or receipt authority.
7. **Only active rows match.** Missing import rows never delete, modify or renew
   local rows. Historical lifecycles do not participate.
8. **A sealed stage is immutable evidence.** Snapshot and contract pins, plan
   hash, counts, SQLite integrity and stage digest must agree before promotion.
   On restart without a sealed stage, the active contract must match the durable
   pin before any row is remapped. A mismatch blocks the ordered lane until the
   previous configuration is restored; committed deliveries finalize solely
   from the dataframe receipt.
9. **Events are latency hints.** Complete source listing, the service ledger,
   dataframe receipt and periodic reconciliation recover lost process-local
   notifications.
10. **Terminal evidence is a unit.** The protected source and safe JSON report
    are published atomically; raw IOC cells, producer paths and source-content digests do not enter
    reports, health details or diagnostics.
11. **Retention removes authority last.** A forward delivery purges the exact
    transport-managed terminal object before local terminal, workspace,
    snapshot and receipt cleanup; the ledger row is deleted last. Replay is
    explicitly source-detached and performs no source disposition or retention.
12. **Aggregate import is target-only.** The shipped disabled
    `ioc-aggregate-v1` contract requires exactly one of `ip_address`,
    `url_match`, `host_match` and `hash`, applies strict structural validators,
    reduces duplicates by the last nonempty `name`, and cannot route into the
    four legacy artifacts.

Promotion planning checks the complete delivery before canonical mutation.
Different primary groups can still share a new related key after workspace
coalescing. Such groups are all rejected with `IMPORT.DUPLICATE_NEW_TARGET`;
no branch or provenance from those logical rows is partially applied.
`ACCEPT_VALID` preserves independent valid rows, while `REJECT_DELIVERY` records
the rejected delivery without business mutations.

A requested slot for a matched survivor must refer to its stable assignment.
During the registry's first seed, existing canonical public IDs supply initial
assignments. Once the registry exists, an active lifecycle without an assignment
is rejected with `IMPORT.EXISTING_SLOT_UNASSIGNED` under either existing-slot
policy. The next eligible export can assign that lifecycle a slot; canonical ID
must not be substituted for an already initialized export namespace.

Slot requests and receipt resolutions are SQL-backed within the same promotion
transaction. Bounded batches stage the entire set before validation/allocation,
preserving input order and global sparse-slot preferences. Planning and slot
scratch are operation-owned TEMP tables, removed before the pooled connection
returns. The immutable SQLite attachment trusts service ownership of sealed
bytes; `immutable=1` itself does not prevent another filesystem owner from
replacing them.

## Failures and recovery

The durable path is `DETECTED -> CLAIMING -> CLAIMED -> SNAPSHOT_PINNED ->
CONTRACT_PINNED -> STAGING -> STAGED -> PROMOTING -> CANONICAL_COMMITTED ->
FINALIZING -> TERMINAL`. `TERMINAL` has an immutable outcome: `SUCCEEDED`,
`COMPLETED_WITH_ERRORS` or `REJECTED`. Rejection is an outcome, not a request to
reopen or reorder the lane. A retrying global head can delay promotion for other
sources even while their independent readiness/listing remains available.

| Situation | Behavior | Recovery authority |
|---|---|---|
| source changes before claim, open producer blocks delete sharing, or claim destination exists | fail closed and schedule a non-blocking retry | candidate evidence plus delivery ledger |
| disconnect after SMB rename | adopt the delivery-token processing object and continue materialization | remote private namespace plus ledger delivery ID |
| disconnect or mutation during download | discard `.part`; never publish a snapshot | remote object evidence and next retry |
| malformed bytes, dialect/header mismatch, zero/ambiguous recognition or hard parser limit | reject the whole delivery before canonical promotion | pinned snapshot and contract catalog |
| invalid logical row | retain only safe row/code evidence; apply configured row-failure policy | sealed stage |
| crash before dataframe commit | resume/rebuild from the latest service-ledger checkpoint | ledger, snapshot and sealed stage |
| crash after dataframe commit | observe `import_commit` and finalize without applying mutations twice | dataframe receipt |
| crash after delivery reservation but before registration link | resume the same reserved occurrence and rank; never allocate from an unproved legacy row | service reservation marker plus dataframe registration |
| event or notification loss | wait for the next bounded full listing or durable-head reconcile | periodic backstops |
| missing private SMB namespace or deterministic permission mismatch | close only that source before listing/claim and report incompatible readiness | positive capability probe plus bounded reprobe |
| temporary SMB capability or terminal-retention failure | keep intake or cleanup authority closed for the affected source and retry | source readiness state or terminal ledger row |
| replay finalization/retention | skip transport disposition and source-remnant purge | explicit source occurrence kind plus retained local terminal unit |

Contradictory durable evidence fails the startup recovery barrier. Ordinary
ingest and managed import both remain closed until their recovery and lifecycle
admission complete.

## Operator surfaces

- `ioc import validate` applies the real recognizer/parser/mapping rules without
  claiming a source, reducing duplicate groups or promoting canonical rows.
  It does not perform live canonical matching, merge/slot planning or commit;
  per-row acceptance is advisory. Full CLI startup can still initialize or
  migrate configured storage and prepare workspace directories. A validation
  command is not a filesystem-level dry run.
- `ioc import status` reports aggregate backlog and safe head retry facts; it
  cannot skip, reorder or force-complete a delivery. It reads the local service
  DB. Its recovery-complete flag belongs to the querying process; use daemon
  actuator health for the running process's recovery state. The current nullable
  retry-deadline reader defect [OPS-10](../KNOWN-ISSUES.md) can fail this query
  and the import health reader on a nonterminal head; it is not commit evidence.
- `ioc import replay` creates a new occurrence with a causal link to retained
  terminal evidence. It uses the active source/catalog, not the old contract's
  behavior, and its successful CLI return confirms admission rather than final
  canonical commit. Retained local terminal source bytes must still exist.
- Actuator health distinguishes failed recovery, safely degraded retry/backlog,
  transient source unavailability, incompatible source capability and normal
  progress. Source details are aggregate and value-free. Metrics remain
  low-cardinality.
- The shared `observationRegistration` component reports unresolved rank counts.
  Terminal import references remain protected until the delivery ledger is
  terminal and all canonical provenance/receipt references have expired.

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

- Exact recognition and presence: [ImportHeaderPlan](../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/mapping/ImportHeaderPlan.java), [DataframeImportRowMapper](../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/mapping/DataframeImportRowMapper.java).
- Preview boundary: [DataframeImportValidationService](../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/DataframeImportValidationService.java), [runtime composition](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DataframeImportRuntimeConfiguration.java).
- Transaction and replay evidence: [JdbcCanonicalImportWriterContractIT](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java), [RouterSelectedImportDeliveryIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/RouterSelectedImportDeliveryIT.java).
- Local source/report protection: [LocalManagedImportSourceLifecycleIT](../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/LocalManagedImportSourceLifecycleIT.java), [LocalImportTerminalStoreIT](../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/LocalImportTerminalStoreIT.java).

## When to update this document

Update when recognition, presence/authority, routing, identity/merge, ordering,
canonical commit, retained evidence, source ownership or CLI/recovery boundaries
change. Qualify external SMB behavior on the intended server family; offline
adapter tests and an unexecuted external suite do not establish live support.

## Related documents

- [storage.md](storage.md) — canonical/service database roles and migrations.
- [processing.md](processing.md) — ordinary processing and reusable preparation.
- [ingestion.md](ingestion.md) — ordinary whole-file ingest ownership/recovery.
- [event-coordination.md](event-coordination.md) — events-as-hints doctrine.
- [operator guide](../guides/dataframe-import.md) — configuration, intake,
  status, replay and incident procedures.
- [IOC aggregate guide](../guides/ioc-aggregate.md) — shipped contract,
  empty-start activation and coordinated rollback.
- [ADR-0015](../ADR/0015-retire-legacy-csv-lookup-storage.md) — legacy CSV
  lookup/seed retirement preserved by managed import.
