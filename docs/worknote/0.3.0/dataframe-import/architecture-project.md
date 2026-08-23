---
title: "DATA-IMPORT-01 — architecture project"
version: "0.3.0"
status: "Approved architecture baseline"
document_type: "Architecture project"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — architecture project

## 1. Executive decision

Implement dataframe import as a new application capability with its own durable
delivery lifecycle and one atomic canonical-promotion port. Reuse the existing
CSV, local-ingest, SMB and JDBC integration families, but do not route import
through the document extraction pipeline or the current per-artifact writer.

The design is a claim-check and transactional-saga architecture:

```text
local scan / WatchService hint       SMB listing / CHANGE_NOTIFY hint
              |                                  |
              +---------- detect by source ------+
                                 |
                 durable claim reservation + sequence
                                 |
                     exclusive ownership claim
                                 |
                  private immutable local snapshot
                                 |
                 service DB: durable global sequence
                                 |
               streaming CSV -> sealed SQLite staging
                                 |
                       head-of-line promotion
                                 |
          one dataframe DB transaction across all artifacts
          + lifecycle + aliases + slots + revisions + receipt
                                 |
            projection/report/disposition forward recovery
                                 |
                       terminal delivery unit
```

Events reduce latency only. The service ledger, immutable snapshot, dataframe
promotion receipt and periodic reconcile remain the source of truth.

No new runtime framework is justified. Apache Commons CSV, sqlite-jdbc/Spring
JDBC, Spring Integration/NIO, SMBJ and the existing keyed executor cover the
required integration families. Spring Batch, Reactor, Kafka, Axon, virtual
threads, parallel streams and event sourcing would add coordination surfaces
without removing the single SQLite-writer or global-order constraints.

## 2. Architectural drivers

The design prioritizes these properties in order:

1. no partial cross-artifact delivery commit;
2. no parse of mutable producer-owned bytes;
3. deterministic active-only matching and merge;
4. crash-safe idempotency across two independent SQLite databases and the
   filesystem;
5. strict global delivery order;
6. bounded heap, disk, queues and writer occupancy;
7. weak coupling through inward-facing ports;
8. loss-tolerant low-latency hints with reconciliation backstops;
9. safe operator evidence without raw IOC leakage;
10. compatibility with ordinary ingest, lifecycle and immutable export.

## 3. Current seams and required corrections

The following are implementation prerequisites, not optional cleanup.

| Current seam | Why it is insufficient | Required correction |
|---|---|---|
| `WriteArtifactsStage` invokes one writer transaction per artifact | A later failure leaves a partially applied delivery | Add one delivery-scoped `CanonicalImportWriter`; extract a connection-scoped mutation engine shared with the existing writer |
| `JdbcCanonicalLifecycleWriter` performs row-by-row lookup and mutation | Large imports cause N+1 JDBC work and it has no field merge | Add set-based planning/mutation paths over disk staging |
| `ArtifactRow` maps absent and null to the same Java `null` | Authoritative clear cannot be distinguished from no instruction | Keep an import-only `ImportCell` tri-state through planning |
| `first-non-empty` is both row identity and implicit match behavior | It cannot model compound rows or multiple alternative keys | Separate immutable record key from versioned match aliases and migrate both write paths |
| `export_slot_free` stores one row per hole | A sparse requested slot can materialize an enormous gap | Replace free-slot enumeration with coalesced ranges and a transaction-local preferred-slot allocator |
| local claim silently falls back from atomic move and replaces destinations | It cannot prove exclusive immutable ownership | Introduce strict collision-safe ownership transfer; fail closed |
| SMB fetch reads a producer-owned path with permissive sharing | The producer may mutate bytes during download | Server-side rename into a service namespace, then durable local materialization |
| mutable CSV projection loads an entire artifact | Memory grows with the active dataset | Stream DB cursor to temp file and atomically replace projection |
| CSV reader replaces malformed bytes | Contract recognition can silently change input | Use a decoder with `CodingErrorAction.REPORT` |
| daemon retry sleeps in a poller thread | It blocks capacity and has no durable wake-up authority | Persist `nextAttemptAt`; schedule a coalesced drain |
| `CanonicalArtifactsChanged` belongs to the ingest package | Import would depend on another use-case package | Replace it with an artifact-level post-commit event |
| `RemoteChangeSignalSource` accepts `RemoteFetchSource` | The watch abstraction is tied to fetch | Generalize to a transport-neutral remote watch target |
| `AppConfig` is already a large composition root | More import wiring would increase accidental coupling | Use a dedicated `DataframeImportConfiguration` |

Existing ordinary ingest must be migrated to the shared identity/mutation and
ownership primitives where required for one canonical truth. Maintaining an
import-only identity system would allow later document ingestion to create a
duplicate of an imported compound row.

## 4. Module and dependency design

No new core Maven module is proposed. The artifact/lifecycle kernel already
lives in `ioc-application`; extracting it merely to create an import module
would produce either a reverse dependency or a premature shared kernel.

```text
bootstrap/ioc-app
  DataframeImportConfiguration, startup coordinator, schedulers, health
        |
        +--> adapter-ingest
        |      local detection, stability, strict filesystem claim
        |
        +--> adapter-csv
        |      CSV decode/recognition and existing CSV output
        |
        +--> adapter-transport-smb
        |      SMB detection, claim, materialization and change hints
        |
        +--> adapter-store-jdbc
               service ledger, staging, match index, atomic promotion
                         |
                         v
                  ioc-application
                    dataframeimport capability
                         |
                         v
                     ioc-domain
```

### 4.1 CSV integration family

Rename `adapter-sink-csv` to `adapter-csv` and artifact
`ioc-adapter-sink-csv` to `ioc-adapter-csv` as a mechanical preparatory slice.
The current outbound packages may remain stable initially; a new inbound
package owns Commons CSV parsing. This keeps one external library family in one
adapter and avoids an adapter-to-adapter dependency.

If the rename is deferred, inbound CSV still belongs in the existing module and
the misleading module name is recorded as temporary debt. A second CSV adapter
is rejected.

### 4.2 Application packages

```text
com.iocextractor.application.dataframeimport
  contract/       source contract values and compiled catalog
  model/          delivery, logical row, tri-state cell, outcomes
  mapping/        library-neutral mapping and policy evaluation
  planning/       recognition, duplicate/fan-out and promotion plan values
  service/        validate, claim coordination, drain, recovery, finalization

com.iocextractor.application.port.in.dataframeimport
com.iocextractor.application.port.out.dataframeimport
```

CSV records, JDBC rows, Spring messages, SMB handles and filesystem paths do not
cross into the application model. The domain remains unaware of delivery,
files, CSV and persistence.

### 4.3 Ports

Driving ports:

- `ValidateDataframeImportUseCase` — advisory parse/plan without reservation;
- `AdmitDataframeImportUseCase` — source-scoped candidate admission and claim;
- `ProcessNextDataframeImportUseCase` — drain only the durable head;
- `RecoverDataframeImportsUseCase` — reconcile ledger, snapshot, stage,
  dataframe receipt and disposition;
- `QueryDataframeImportStatusUseCase` — aggregate/read-only status;
- `ReplayDataframeImportUseCase` — create a new delivery causally linked to a
  terminal unit, never reopen it.

Driven ports:

- `ImportDeliveryLedger` — global order, CAS transitions, retry and summaries;
- `ManagedImportSourceLifecycle` — detect, claim, snapshot and disposition;
- `DelimitedRecordReader` — strict library-neutral row stream;
- `ImportWorkspace` — disk staging and sealed-stage metadata;
- `CanonicalImportWriter` — one complete accepted write set transaction;
- `ImportReportStore` — safe protected report and terminal unit publication;
- `ImportStatusReader` — indexed operational aggregates;
- existing `ArtifactProjection`, `ControlEventPublisher`, lifecycle clock and
  artifact preparation ports where their contracts already fit.

Ledger and canonical-writer ports receive reusable TCKs in
`ioc-application-tck`.

## 5. Domain and application model

### 5.1 Delivery aggregate

`ImportDelivery` is the recovery aggregate, not a collection of all CSV rows.
It contains IDs and safe metadata only:

- non-reusable `deliveryId` and global `sequence`;
- `sourceId` and optional `replayOf`;
- snapshot digest/size and private locator;
- pinned contract ID/version/fingerprint;
- state, CAS version, attempts and next retry time;
- sealed-stage locator/digest;
- promotion receipt reference;
- safe count summary and terminal outcome.

Bulk row values remain in the snapshot/staging files. They are never loaded into
the delivery aggregate or emitted in events/logs.

### 5.2 Row model

`ImportLogicalRow` owns source row number, primary branch and deterministic
related branches. Each field is one of:

```text
ABSENT          source gives no instruction
NULL            source explicitly supplies no value
VALUE(text)     source supplies a value
```

`ArtifactRow` is created only after matching and merge have resolved every
field. It is not changed to carry persistence or CSV concerns.

### 5.3 Record key and match aliases

Canonical record identity and matching are separate contracts:

- one artifact-level, versioned **record-key definition** derives the immutable
  `row_key` for a newly created record;
- one or more named **match-key definitions** derive alternative canonical keys
  used to find active lifecycle candidates;
- source contracts may reference only allowlisted match definitions already
  registered for that artifact;
- a generic match-alias table indexes `(artifact, definition, key hash)` and
  confirms equality against canonical key material;
- aliases are created/replaced in the same transaction as canonical public
  fields and are maintained by ordinary ingest as well as import;
- lookup always joins the owning canonical row with
  `_valid_until_epoch_ms > asOf`; history is excluded;
- zero candidates creates a row, exactly one merges, more than one rejects the
  logical row.

The initial identity migration changes `address_blacklist` and `hashes` from
`first-non-empty` to explicit compound record keys while preserving canonical
IDs and lifecycle IDs. It increments the configured identity epoch, detects
collisions before mutation and rebuilds aliases under an intake barrier. Any
collision is a migration blocker requiring an explicit data decision; no
automatic merge is permitted.

This representation supports both a partially populated row that uniquely
enriches a compound record and repeated scalar pairs. If a scalar key points to
multiple compound records, that is the designed multi-match conflict.

## 6. Runtime flow

### 6.1 Detection and claim

1. A periodic full scan lists eligible candidates per source.
2. WatchService or CHANGE_NOTIFY coalesces into the same detection call.
3. Stability and capacity admission are evaluated without creating a retry
   attempt.
4. A service-DB claim reservation atomically assigns the occurrence its unique
   durable global sequence before transport workers can finish out of order.
5. The source adapter obtains ownership in a service-managed namespace; retry
   retains the same reservation and sequence.
6. The claimed object is streamed into a private local `.part` while computing
   SHA-256 and size.
7. File and directory metadata are forced, then `.part` is atomically published
   as the immutable snapshot.
8. The ledger records `SNAPSHOT_PINNED` and nudges the global lane.

A failed ownership proof never reaches parsing or permits a later sequence to
overtake it. Local unsupported atomic move and SMB sharing/rename conflicts are
safe retry/degraded conditions; bounded exhaustion terminally quarantines the
reservation so the lane can progress.

### 6.2 Recognition and staging

Only the lowest nonterminal sequence is processed in V1:

1. verify snapshot size/digest and load the pinned compiled contract catalog;
2. decode the declared charset with malformed/unmappable input reporting;
3. parse only configured dialect candidates and evaluate structural signatures;
4. require exactly one source contract;
5. stream records through mapping, normalization, safety checks and mode logic;
6. write logical rows, branches, tri-state values, duplicates and safe errors to
   a per-delivery SQLite workspace with batched statements;
7. create indexes, compute counts and a stage digest, close/freeze the database;
8. record the pinned stage as `STAGED`.

No dataframe write lock is held during parsing or staging.

### 6.3 Atomic promotion

The writer revalidates live state inside one dataframe write transaction:

1. verify head sequence, snapshot/stage fingerprints and absence/presence of a
   prior promotion receipt;
2. reserve worst-case monotonic canonical/lifecycle ID ranges before the
   transaction; unused IDs remain holes by existing policy;
3. attach the sealed stage read-only and acquire the existing lifecycle write
   ownership with one sampled `asOf`;
4. resolve active alias matches and stable-identity conflicts set-wise;
5. resolve duplicate groups, tri-state merge and fan-out atomicity;
6. apply delivery failure policy;
7. archive expired same-key lifecycles, then insert/update/confirm accepted
   records and provenance set-wise;
8. reconcile preferred export slots in final active-set order;
9. update aliases and lifecycle deadlines;
10. advance public revision/projection generation once per affected artifact
    only when its public bytes changed;
11. persist one `import_commit` receipt and safe rejection outcomes;
12. commit.

The staging database is never written in the dataframe transaction. Atomicity
therefore depends on one database transaction, not on a multi-file SQLite
transaction under WAL.

### 6.4 Forward finalization

After commit, recovery moves forward only:

1. service ledger observes the dataframe receipt and records
   `CANONICAL_COMMITTED`;
2. mutable projection convergence runs once per affected artifact through its
   durable generation backstop;
3. deadline and artifact-change hints are published after commit;
4. a safe JSON report is written to a temporary file and atomically published;
5. source plus report are atomically exposed as one terminal directory;
6. remote disposition is completed or retried;
7. ledger records terminal outcome.

Failure after dataframe commit never reapplies business mutations. The receipt
is authoritative and finalization resumes.

## 7. Transactions, ordering and concurrency

- Detection is serialized/coalesced by `sourceId`; distinct sources may run in
  parallel.
- SMB operations are serialized by logical endpoint/credential identity.
- Claim and materialization use a named bounded executor and global inflight
  bytes/count admission.
- V1 parses/stages only the durable head. Later bounded pre-staging may be added
  without changing promotion order, but is deliberately not required.
- Promotion uses one global import lane and the minimum nonterminal ledger
  sequence. Executor order is never authority.
- A retrying or capacity-paused head blocks later delivery promotion.
- A shared fair same-process JDBC writer admission coordinates import,
  ordinary confirmation, lifecycle reconciliation and slot work. SQLite
  transaction ownership remains the correctness mechanism.
- No parsing, hashing, file copy or report I/O occurs under writer admission.
- Spring application-event listeners only call `nudge()` and return. The
  current default synchronous listener thread never performs import work.

## 8. Event model

Two post-boundary hints are sufficient:

- `ImportDeliverySnapshotPinned(deliveryId, sequence, sourceId)` nudges the
  global drain; recognition has not yet pinned a contract, and the event
  contains no locator, filename, digest or IOC;
- `CanonicalArtifactsChanged(operationId, affectedArtifacts)` is moved to the
  artifact capability and nudges the existing export scheduler after a
  completed canonical operation.

Projection-required and lifecycle-deadline hints remain as currently designed.
Retry, capacity, retention, report and terminal state do not need dedicated
events because indexed ledger reconciliation owns them. Event loss, duplication
or executor rejection may add latency but cannot change the result.

## 9. Sparse requested-slot algorithm

The existing per-hole table must become coalesced free ranges before arbitrary
requested slots are accepted. Otherwise requesting a very large free slot can
create one row for every gap.

For each `(profile, artifact)` under the canonical transaction:

1. release vanished assignments and merge adjacent free ranges;
2. preserve all active survivor assignments;
3. validate requested-slot conflict groups;
4. for a new lifecycle with a free requested slot, remove/split the containing
   free range, or advance high water while adding at most one preceding range;
5. for an occupied request, allocate the smallest positive free slot;
6. allocate all remaining new lifecycles from lowest ranges, then high water;
7. never compact or renumber a survivor;
8. apply `preserve-existing` or `reject-mismatch` for a matched survivor;
9. update registry generation against the final active set.

All operations are range/index based. Complexity is proportional to affected
lifecycles and ranges, not to the numeric value of the largest requested slot.

## 10. Processed mode reuse

Processed import is not a synthetic Word document and does not run
`ReadSourceStage` through `WriteArtifactsStage`. It reuses framework-free
processing services behind stable application contracts:

- refang and extraction where a mapped field declares raw IOC input;
- one reusable classifier extracted from `ClassifyIndicatorsStage`;
- existing `MatchPolicy` and configured artifact preparers;
- bounded chunks whose prepared rows are written immediately to staging.

Primary compound-row grouping is retained by an import correlation key.
Related branches use the same provider/transform registries but never create a
cross-artifact relationship. `as-is` skips implicit transforms and validates
the mapped final artifact row.

## 11. Startup and shutdown

Replace competing highest-precedence runners with one bootstrap-owned
`CanonicalIntakeStartupCoordinator`:

1. keep ordinary ingest and import intake closed;
2. reconcile schema/identity migration and lifecycle admission;
3. recover ordinary ingest and import from ledger/snapshot/stage/receipt state;
4. start import drain and reconcile workers;
5. open ordinary ingest, local import scans and remote watches.

Shutdown reverses this order: stop watches/scans and new claims, let the active
operation reach a durable checkpoint, then stop lanes, reconciles and transport
sessions.

## 12. Configuration boundary

The proposed namespace is `ioc.dataframe-import`. It owns enablement, sources,
contracts, capacity, retry, retention, staging and health thresholds. SMB
sources reference the existing shared endpoint catalog; credentials and timeout
definitions are not duplicated.

Configuration is bound and validated using the existing strict preflight. The
bootstrap layer passes library-neutral dialect values to the CSV adapter.
Contract compilation validates references, aliases, transforms, match keys,
authority ceilings, artifact schemas and formula policy at startup. Activation
occurs only on restart; each claimed delivery pins the compiled fingerprint.

The detailed proposed contract is in [data-contract.md](data-contract.md).

## 13. Operational and security posture

The capability is fail-closed at trust and ownership boundaries, bounded at all
queues and storage points, and recoverable after every durable transition.
Health uses indexed aggregate queries and distinguishes safe delay from an
unsafe contradiction. Logs/events never contain input values, paths, filenames,
digests or credentials. The terminal report is a protected artifact.

See [operations-and-security.md](operations-and-security.md) for source
hardening, resource controls, health, diagnostics and threat scenarios.

## 14. External mechanism validation

The selected mechanisms follow current primary documentation:

- [Apache Commons CSV `CSVParser`](https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVParser.html)
  provides record-wise iteration suitable for streaming;
- [Spring Integration file reading](https://docs.spring.io/spring-integration/reference/file/reading.html)
  documents WatchService as an optimization that can lose or overflow events,
  so full scans remain necessary;
- [Spring application events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events)
  are synchronous in the publishing thread by default, supporting nudge-only
  listeners;
- [SQLite transactions](https://www.sqlite.org/lang_transaction.html) permit
  only one simultaneous writer and `BEGIN IMMEDIATE` obtains write ownership
  early;
- [SQLite attached databases](https://www.sqlite.org/lang_attach.html) do not
  provide the required crash-atomic guarantee for writes across attached WAL
  databases, hence staging is read-only during promotion;
- [SMBJ](https://github.com/hierynomus/smbj) provides low-level rename,
  access/share masks and change notification, but real-server contract tests
  are required for server-specific behavior.

No dependency upgrade is bundled into this architecture decision. In
particular, the repository's Commons CSV version is assessed independently from
the import feature rather than opportunistically upgraded.

## 15. Rejected alternatives

| Alternative | Reason rejected |
|---|---|
| Run every imported CSV through the existing document pipeline | Loses structured row/compound semantics and still commits per artifact |
| Keep an import-only canonical store then merge asynchronously | Creates two business truths and exposes partial state |
| Commit chunks directly to canonical tables | Violates whole-delivery atomicity |
| Hold the dataframe transaction while parsing | Unbounded writer occupancy and poor responsiveness |
| Store all rows in heap | Violates the large-delivery resource contract |
| Infer contract by filename or best score | Fails deterministic exact-one recognition and weakens authority |
| Treat events as the queue | Cannot recover loss/restart and cannot prove global order |
| Add Kafka or an external workflow engine | No distributed consumer exists; durable local ledgers already own correctness |
| Use separate local and SMB business workflows | Duplicates state machines; only claim mechanics are transport-specific |
| Preserve current `first-non-empty` identity | Cannot represent compound rows and alternative match keys safely |
| Enumerate every free slot below a sparse request | Time/storage cost depends on requested integer magnitude |

## 16. Approval gates

Architecture approval explicitly accepts these non-trivial changes:

1. mechanically rename the CSV adapter integration family;
2. migrate canonical record identity and add match aliases used by both write
   paths;
3. extract a shared connection-scoped mutation engine;
4. replace per-slot free rows with coalesced free ranges;
5. migrate mutable projection to bounded cursor streaming;
6. introduce strict local/SMB ownership lifecycle and a shared startup barrier;
7. add service schema v9 and dataframe schemas v7-v8 under the ownership described
   in [persistence-and-recovery.md](persistence-and-recovery.md).

Implementation sequencing and rollback boundaries are defined in
[implementation-plan.md](implementation-plan.md); evidence is defined in
[verification-matrix.md](verification-matrix.md).
