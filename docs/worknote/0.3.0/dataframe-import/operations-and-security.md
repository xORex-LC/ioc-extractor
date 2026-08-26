---
title: "DATA-IMPORT-01 — operations and security design"
version: "0.3.0"
status: "Approved architecture baseline"
document_type: "Operations and security design"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — operations and security design

## 1. Trust model

Writing to an import source grants business mutation authority only within the
source's configured contract allowlist and authority profile. Structural file
recognition does not grant authority. Separate trust levels use separate source
directories or SMB endpoint/credential identities.

V1 trusts the configured producer identity and managed transport boundary. It
does not authenticate individual files with digital signatures. This exclusion
must be visible in the operator guide and threat model.

## 2. Local source lifecycle

The local adapter owns detection and filesystem mechanics behind
`ManagedImportSourceLifecycle`.

Required controls:

- dedicated source, processing, terminal and quarantine roots;
- startup real-path validation, no overlapping source/processing/terminal roots
  and no escape outside configured roots;
- no-follow checks and rejection of symbolic links and unsupported special
  files;
- regular-file identity/attributes sampled during stability checks;
- unique collision-safe delivery target; never `REPLACE_EXISTING`;
- strict same-filesystem `ATOMIC_MOVE` in the default claim mode;
- private directories and files with least deployment-account permissions;
- SHA-256 and byte count computed during durable materialization;
- file and containing-directory force before atomic snapshot publication;
- immutable application ownership after claim;
- terminal source/report published as one directory and retained as one unit.

An unsupported atomic move is retryable/degraded, not a reason to silently use
ordinary move. A future copy-claim mode requires explicit configuration,
producer protocol and independent qualification; it is not implicit fallback.

## 3. SMB source lifecycle

The SMB adapter must prove ownership before downloading:

1. list/detect under the configured source path;
2. server-side rename the file into a private service processing namespace;
3. reject destination collisions and incompatible share/access modes;
4. stream the claimed remote object into a private local `.part` with digest
   and size;
5. force and atomically publish the local immutable snapshot;
6. retain enough remote claim metadata for orphan adoption after restart;
7. archive/quarantine remote state during forward finalization.

The import port is separate from generic `FileTransport`; raw rename is not
added to a broad fetch/publish abstraction. The SMB session/connection pool may
be extracted for reuse so import does not establish a competing stack.

Real SMB qualification must cover rename of regular files, collision,
concurrent producer handles/share modes, disconnect/reconnect, rename-success
plus download-failure, orphan recovery and server-specific CHANGE_NOTIFY
behavior. Unit mocks alone are insufficient.

## 4. Change notification and reconciliation

Local WatchService and SMB CHANGE_NOTIFY are doorbells:

```text
signal -> source-keyed coalesced nudge -> complete detection/listing
```

Periodic full scans and startup reconcile remain enabled. Overflow, reconnect,
duplicate, loss and executor rejection affect latency only. No signal carries a
filename as trusted work authority.

## 5. Resource controls

Every growth dimension is bounded and configured:

- maximum source file bytes;
- maximum rows, columns, record bytes and field bytes;
- maximum logical fan-out branches per row;
- maximum row errors retained and reported;
- maximum nonterminal deliveries;
- maximum aggregate snapshot/stage bytes;
- low/high disk-space watermarks;
- parser batch size and staging transaction batch size;
- claim/materialization executor threads and queue capacity;
- retry attempts/backoff and terminal age thresholds;
- maximum bounded finalization backlog.

Admission stops new claims at the high watermark and resumes below the low
watermark. Already claimed state stays durable. A capacity pause is visible but
does not consume a retry attempt. Hard per-delivery limit violation rejects that
delivery before canonical write.

Parsing and staging never hold canonical writer ownership. Promotion is one
transaction by business contract; it cannot be split merely to meet a latency
target. Performance qualification must therefore establish an acceptable
maximum delivery envelope.

Commons CSV ignores empty physical lines, including blank lines trailing a
delivery. A delimiter-only or whitespace-only line is still a record: it is not
silently normalized away and remains subject to the configured row policy or
strict structural validation. Parser-boundary failures retain a stable,
value-free reason in the protected report without copying raw content or an
exception message.

## 6. Scheduling and fairness

- source detection: keyed by source, bounded and parallel across sources;
- SMB commands: keyed by logical endpoint/credentials;
- materialization: small named bounded pool with byte admission;
- import drain: one constant lane key plus durable head selection;
- JDBC writers: common fair admission across ordinary ingest, import, lifecycle
  and slot operations;
- projection/finalization: durable-generation/ledger reconciles, never the
  import writer transaction.

Retry uses persisted `nextAttemptAt` and a scheduler nudge. Poller threads never
sleep for business backoff. Graceful shutdown stops admission first and waits
only to a configured durable checkpoint timeout.

## 7. Health model

An `ImportHealthIndicator` returns aggregate indexed facts only.

### 7.1 `UP`

- startup recovery complete;
- ledger/snapshot/receipt invariants agree;
- no overdue unsafe head;
- disk and queue below warning thresholds;
- required sources and canonical/staging storage available.

### 7.2 `DEGRADED`

- head is in expected retry backoff;
- capacity pause or disk warning watermark;
- optional WatchService/CHANGE_NOTIFY unavailable while polling works;
- projection/report/remote disposition is safely behind after commit;
- terminal backlog or retry age exceeds warning threshold.

### 7.3 `DOWN`

- startup recovery incomplete or intake opened prematurely;
- snapshot/stage digest contradiction;
- service state contradicts dataframe receipt;
- duplicate sequence/receipt invariant;
- required storage/source unavailable beyond failure threshold;
- global lane cannot determine or safely advance the head;
- identity/slot migration or canonical consistency check failed.

Health details include counts, oldest-safe age, state and diagnostic code only.
They exclude filenames, paths, contract contents, hashes, digests, credentials,
IOC values and raw exception text.

## 8. Observability

### 8.1 Metrics

Recommended low-cardinality metrics:

- detection/claim/pin/stage/promotion/finalization durations;
- deliveries by state/outcome/source ID;
- rows accepted/rejected and public mutations by artifact;
- snapshot/staging bytes and capacity pause duration;
- head age, retry count and terminal backlog;
- writer wait/hold duration by operation family;
- projection convergence lag;
- lost/overflow/reconnect change signals;
- slot exact/fallback/mismatch outcomes.

Delivery ID and source ID may be structured log fields under existing
cardinality policy; they are not unrestricted metric labels.

### 8.2 Logs and diagnostics

Events use cataloged names and stable diagnostic codes. Safe fields include
delivery ID, sequence, source ID, contract ID/version, state transition, counts,
duration and artifact names. Never log:

- raw CSV cells or canonical IOC values;
- source filename or path;
- snapshot/stage/report locator or digest;
- SMB host/share/user/secret;
- raw parser record or unbounded exception message.

Generated diagnostics documentation is updated from code registries, never by
editing generated output.

Implemented operational emission follows the shared observer pattern:

- the application-owned `DataframeImportObserver` is framework-free and is
  invoked only after durable admission, claim, staging, promotion, retry and
  terminal checkpoints;
- the bootstrap logging adapter emits the cataloged `import_*` ECS actions and
  the owning boundary emits each matching delivery diagnostic once;
- `import_complete` identifies the durable disposition as `terminal` or
  `quarantine`; classified parser failures also carry a stable value-free
  failure reason, never the parser message or source content;
- startup recovery and non-empty retention use aggregate events; empty periodic
  checks remain silent;
- keyed-lane shedding/failure and change-notification loss are explicit
  operational events while durable reconcile remains the correctness path;
- a non-throwing decorator prevents logging or diagnostic delivery failure from
  changing import state or control flow.

### 8.3 Delivery report

The protected JSON report contains:

- schema version and delivery ID;
- source ID and pinned contract ID/version/fingerprint identifier;
- safe outcome and phase timings;
- source/logical/accepted/rejected/public-mutation counts;
- affected artifacts and slot outcome counts;
- bounded entries with source row number(s), branch/artifact, diagnostic code
  and safe detail;
- truncation counts when the error-entry limit is reached.

It does not echo a raw value. The original protected CSV is the operator's
evidence for inspecting that row.

## 9. Read-only UX

V1 commands/endpoints are:

- validate/preview a file against the active catalog;
- show aggregate queue/head/recovery/status;
- create a replay from a terminal protected unit;
- health through the daemon actuator.

Preview may parse and build disposable staging, but it cannot claim an inbox
file, allocate canonical/lifecycle IDs, reserve slots, mutate ledgers, renew TTL
or guarantee a later outcome. Real promotion always revalidates live active
state.

There is no skip, delete, reorder, force-complete or edit-ledger operation.

## 10. Threat register additions

| Threat | Control |
|---|---|
| producer modifies file during read | ownership rename, private namespace, durable hash/size snapshot |
| symlink/path traversal | no-follow, real-path containment, dedicated roots |
| destination overwrite/collision | unique delivery IDs, no replace, database uniqueness |
| malformed encoding/parser abuse | declared charset/dialect, strict decoder, field/row/byte limits |
| CSV formula injection | reject dangerous free text by default; explicit machine-only ceiling |
| source escalates artifact/field authority | source contract allowlist and authority ceiling compiled at startup |
| ambiguous contract mutates wrong list | exact-one structural recognition, no filename/scoring fallback |
| replay/double application after crash | immutable occurrence, dataframe receipt, service-ledger recovery |
| queue reorder | durable monotonic sequence and minimum nonterminal selection |
| crafted sparse slot exhausts storage | positive bounds plus range-based allocator |
| hash collision affects matching | digest-index lookup plus canonical-material equality check |
| disk exhaustion | watermarks, bounded inflight state, pinned-state-aware cleanup |
| diagnostics leak IOC or infrastructure | safe schemas, no raw values/paths/digests, protected reports |
| SMB credential privilege crossover | endpoint/credential identity per trust boundary and least access masks |

## 11. Deployment and rollback

- Packaging creates private import processing, staging and terminal roots with
  explicit ownership/mode.
- Fresh production examples remain disabled until sources/contracts are
  configured.
- Config validation occurs before intake starts.
- Schema/identity/slot migration is backed up and qualified against a 0.2.0
  upgrade fixture.
- Application rollback after irreversible schema migration follows the current
  database backup/restore boundary; an older binary is not assumed able to open
  v7/v9 schemas.
- Disabling import stops new claims and drains or safely checkpoints current
  work; it never discards nonterminal evidence.
