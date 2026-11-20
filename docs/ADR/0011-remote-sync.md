> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0011-remote-sync.md) (relative link addresses rebased on relocation).

<a id="0011--синхронизация-с-внешними-хранилищами-deliverysmb"></a>
# 0011 — Synchronization with external storage (delivery/SMB)

<a id="статус"></a>
## Status

**Implemented (S0–S8).** Branch `feature/delivery/smb`. Transport-neutral fetch/publish,
JDBC ledgers, verified slice discovery, the SMB adapter, daemon lifecycle, CLI and health
passed the full reactor gate; operator documentation: [sync.md](../dev/sync.md).
ING backlog number — TBD. **Post-review hardening is complete** (Findings F1–F5 + ref-1…9
closed): in 0011 — **F3** (delivery owns a separate `publish_ledger`, decision 6), **ref-9**
(byte-preserving publish, decision 17), **F2** (slice retention, decision 18); the rest is in
[0012](0012-streaming-dataframe-emission.md).

> **Superseded:** the `publish.trigger` variants below are retained as design history.
> The implemented contract after [0013](0013-event-driven-coordination.md) has no such setting:
> the event fast path always has mandatory periodic reconcile/backstop.

<a id="контекст"></a>
## Context

Bidirectional synchronization with external storage is needed:

1. **Input:** fetch IOC files from remote storage into `var/inbox`;
   the application then processes them normally (existing daemon ingestion).
2. **Output:** after pipeline execution and dataframe formation, send them to remote
   storage. The output host/path/transport are **independent** of the input.

Principles from the requirements:

- **The synchronization service is transport-independent.** Transport is external;
  its purpose is to deliver files, and the service need not know its kind. All
  transport specifics stay outside. SMB is the only transport now; others may follow.
- **Connection parameters and other settings come from configuration**
  (`application.yml`/`IocProperties`), without hardcoding.

Fit with the current system: input reuses existing streaming daemon ingestion
([0001](0001-streaming-ingestion.md)); fetch ends once the file is in `var/inbox`.
Output transports **formed** artifacts; *how* they are formed over a continuous
stream (windows/triggers/output-mode, immutable snapshots) is a separate cross-cutting
concern in [0012](0012-streaming-dataframe-emission.md). Delivery is **agnostic** to this:
it sends whatever has been formed, whether a complete snapshot or an increment.

<a id="решения"></a>
## Decisions

**1. Configuration root — `ioc.sync`** (bidirectional: `fetch` + `publish`), with shared
named `endpoints` (endpoint = transport + connection parameters + base path).
The name is more accurate than “delivery”, since the feature is bidirectional.
Input and output reference endpoints by name, making their hosts/transports independent.

**2. Transport agnosticism means a port.** The transport SPI lives in `application`
(`port/out/sync`: **stateless `FileTransport`**; `RemoteSession`/connection is internal
to the adapter, see decision 13). All SMB specifics live in the
`adapters/adapter-transport-smb` module using **smbj** (pure Java SMB2/3, no SMB1).
The “one external library = one module” rule means a new transport (SFTP/S3/…)
is a new `adapter-transport-*` behind the same port, **without use-case changes** (OCP).
smbj types **must not leak into `core`** (enforce with ArchUnit). Transport selection by
id (`transport: smb`) uses a bootstrap registry, like `ioc.engine` → `PatternEngine`.

**3. Two use cases in `application/sync`** (pure, without Spring):
- `RemoteFetchUseCase` — input: list a source → download into the **existing**
  `ioc.ingestion.dirs.inbox`. The pipeline is **not duplicated**; fetch ends when
  the file has landed atomically in the inbox.
- `ArtifactPublishUseCase` — output: upload completed dataframes to a destination.

**4. Input: idempotency without writing to the source.** Treat the source as
read-only; a local **fetch-ledger** rejects duplicates (remote `path+size+mtime` key),
following `FileIngestionLedger`. Rejected for v1: source move/delete “claim” semantics
(requires write/delete permissions; retained as a future `after:` option).

**Atomic inbox landing.** Fetch does not write directly to an include-visible name.
The v1 protocol: `get` downloads the remote object to a local temp/staging file
(`*.part` or a directory outside `ioc.ingestion.dirs.inbox`) → close/fsync where
available → atomic move to the final name in `ioc.ingestion.dirs.inbox` → only then
mark the ledger. The ingestion daemon therefore never sees a partial download.
Final-name collisions are deterministic: skip a remote identity already in the
ledger; for a new identity whose name is occupied, append a stable remote-identity
suffix without overwriting another inbox file.

**5. Output: publish a formed Export Slice, rather than raw files.** The publish
source is a completed local export slice
([0012](0012-streaming-dataframe-emission.md)), **not** a
`dataframe/*_generated.csv` glob: storage evolution must not break delivery.
Delivery does not care whether this is a snapshot or an increment.
- **Atomic publication of a slice as a directory** uses the `publishAtomically`
  intent (decision 14): the adapter implements this natively (SMB/SFTP: temp→rename;
  S3: objects+marker), writing `_SUCCESS` **last**, as the commit point. The
  “marker ⇔ completeness” contract means a remote consumer takes the entire slice.
- **Delivery verification:** check checksum/`_SUCCESS` after upload before confirming
  delivery (the export-saga boundary is in decision 6).
- Names/immutability/coverage stamp belong to the *formed* slice (0012); delivery
  preserves them (`<profile>/<ts>__<run_id>/` + `manifest.json` + `_SUCCESS`, see
  “Slice format” in 0012).

**6. Publish trigger — a new slice with `_SUCCESS` / timer; delivery is a separate saga (F3).**
Delivery reacts to **a new Export Slice with an `_SUCCESS` marker** (`on-new-output`)
or polls at an interval. It does **not** decide whether a set is complete; that is
formation's concern ([0012](0012-streaming-dataframe-emission.md) §4a).
Configuration form: `publish.trigger: on-new-output | interval | both`.
**Formation boundary (F3):** the export saga ends at `COMPLETED` (a local slice with
`_SUCCESS`); the boundary is the **filesystem**
(`var/export/<profile>/<slice>/` + `_SUCCESS` + manifest), not a shared table.
Delivery owns a **separate `publish_ledger` keyed by (slice_id, target_id)** in the
service DB — per-target status/retries correctly express fan-out (one slice → N
targets: A published, B not published, C retrying). `slice_id` comes from the
manifest/directory name (`run_id`, ref-2). Transport **does not touch `export_run`**.
A local slice cannot be reaped while `publish_ledger` contains undelivered pairs
(the retention boundary, F2). Pair statuses: `PENDING → IN_PROGRESS → SUCCEEDED`
(`FAILED` retries by policy; `ABANDONED` is an explicit operator decision, terminal
for retention). Alongside the key, the ledger stores operational slice identity
(`profile`, `slice_name`, `manifest_sha256`) and target binding (`endpoint`,
`remote_path`), so restart/reconcile, health and manual operations do not reconstruct
paths from `slice_id` alone. At startup the publish scheduler first reconciles
`completed slices × configured targets`; a missing pair counts as pending even
before INSERT, so the reaper cannot outrun discovery.

**Completed local slice discovery is a separate read-only seam.** The publish use
case does not reuse `FileSystemSliceRetentionStore` as its worklist: that store
intentionally returns only `SliceDescriptor` and serves deletion candidates.
Delivery needs `CompletedSliceCatalog`/a similar port: scan
`var/export/<profile>/<slice>/`, verify the `_SUCCESS → manifest → data files` chain
with the same `SliceManifestCodec`/0012 verifier, and return `AvailableSlice` plus
the local directory `Path`. Only verified completed slices may enter `publish_ledger`.

**In v1, `on-new-output` means poll/reconcile, without requiring filesystem events.**
After 0012, formation publishes no separate outward application event. Restart-safe
semantics require periodically reconciling verified slices against `publish_ledger`.
Thus `on-new-output` means “find new completed slices and ledger gaps on each tick”;
`interval` sets the discovery frequency. WatchService/an event bus can be added
later to improve latency, but correctness must not depend on it.

**Post-v1 model clarification: detection and execution must be separated.** A
practical SMB delivery run showed that v1 delivers correctly, but
`RemoteFetchService` and `ArtifactPublishService` partly combine work detection and
execution. In particular, fetch performs remote `list` inside the use case; this
both checks remote-source changes and starts fetch. With an expired idle SMB
session this can produce transient `DiskShare has already been closed` and a brief
sync-health `DOWN`, although the next tick reopens transport and data keeps moving.

The target for the next hardening pass is **state-driven sync with a reconcile safety net**:

```text
RemoteSourceMonitor
  └─ строит remote snapshot/fingerprint и emits changed-work
     └─ RemoteFetchService скачивает конкретные новые identities

PublishWorkDetector
  └─ сверяет completed slices × configured targets × publish_ledger
     └─ ArtifactPublishService публикует конкретные pending/failed pairs
```

Fetch must not own its own work timer; the timer/notification belongs to the
remote-state detector. Publish may have its own cadence/window policy, but that
belongs in the detector/reconciler, rather than the delivery executor. Removing
reconcile entirely is undesirable: durable ledgers remain the source of truth
after restarts, lost events and crash windows. Events/WatchService/transport-native
notifications improve latency and are not the sole correctness guarantee.

**A profile is an indivisible publication unit.** A target references an
`export-profile`, without selecting individual `artifacts`: the adapter copies
the entire `<profile>/<ts>__<run_id>/` byte-for-byte, preserving its original
manifest and `_SUCCESS`. A different file set needs a separate `ioc.export`
profile. Delivery therefore creates no derived manifest and remains agnostic to
CSV/schema/output-mode.

**7. V1 scope — automatic daemon cycles + `ioc sync` CLI.** Automatic timed fetch
and barrier/timer-driven publish in `daemon` mode; manual `ioc sync` subcommands
(on-demand fetch/publish for operations and tests, symmetric with `ioc health`).
**Rejected for v1:** publish after oneshot `extract`.

**8. Scheduling/lifecycle — `SmartLifecycle` beans in `bootstrap`**
(`DaemonFetchScheduler`, `DaemonPublishScheduler`), following the existing
`DaemonMaintenanceScheduler`, under `@ConditionalOnProperty mode=daemon`.
The cadence strategy itself (`interval`/`quiet-period`+max-cap) is a
**framework-free `CadenceSource`** (reused by 0012 export and ING-7 local projection —
guardrail Q2); Spring wiring stays in bootstrap. `TransportRegistry`
(name → transport+connection) is also in bootstrap. Use cases remain pure.
(This aligns scheduler placement with [0012](0012-streaming-dataframe-emission.md):
bootstrap, without a separate `adapter-scheduler`.)

**Lifecycle ordering after 0012.** The publish scheduler starts after export
formation and before slice retention. Practical phases:
`DaemonExportScheduler.PHASE = 100`, `DaemonPublishScheduler.PHASE = 150`,
`DaemonSliceRetentionScheduler.PHASE = 200`. `DaemonPublishScheduler.start()` first
synchronously reconciles `verified completed slices × configured targets →
publish_ledger`, then starts the periodic cycle. This closes the race where
retention could delete a new slice before delivery created its pending pair.

**9. Secrets through `${ENV}`, never committed.** SMB passwords are env references
in `configs/application.yml` (gitignored) or the systemd environment file from
[packaging/](../../packaging/). SMB3 + encryption where available; the adapter
**does not log** credentials.

**10. Observability — diagnostics + ECS logs (detailed taxonomy).** A new
`DiagnosticCategory.SYNC` (`id` `SYNC.<NAME>`, `messageKey` `sync.<kebab>`) + ECS events;
last fetch/publish status (time, counts, errors) in `ioc health`; sync `--dry-run`
follows `extract --dry-run`. Conventions: [observability.md](../dev/observability.md).

**Completeness discipline** (project rule): the error axis is *closed* and specified
fully **in the design** now; flow codes, `event.action` and `ioc.*` fields have a
core set and grow. Add them to enum/catalog/whitelist **with their producer**
during implementation. The set here is *planned*.

**Two error layers** (closes Open Q1, “neutral error taxonomy”). The adapter translates
transport exceptions (smbj types must not reach `core`) into port-neutral
`RemoteErrorKind`; the use case/bridge maps that to a diagnostic code and decides
retry using `retryable`:

| `RemoteErrorKind` (port) | `SYNC.<code>` | severity | disposition (decision 15) |
|---|---|---|---|
| `UNREACHABLE` | `ENDPOINT_UNREACHABLE` | ERROR | `RETRY_LATER` (macro, next tick) |
| `AUTH_FAILED` | `AUTH_FAILED` | FATAL | `FAIL` |
| `PERMISSION_DENIED` | `PERMISSION_DENIED` | ERROR | `FAIL` |
| `NOT_FOUND` | `REMOTE_NOT_FOUND` | WARN | `FAIL` |
| `TRANSIENT` | `TRANSPORT_TRANSIENT` | WARN | **`RETRY_NOW`** (micro, immediately) |

Disposition (`RETRY_NOW`/`RETRY_LATER`/`FAIL`) is **code-owned, not configuration**;
it drives the retry model (decision 15).

**Other codes (extensible core):** `PUBLISH_VERIFY_FAILED` (ERROR, decision 14),
`ENDPOINT_UNKNOWN` (FATAL — fetch/publish references a nonexistent endpoint name,
cf. CFG-2), `CREDENTIAL_MISSING` (FATAL — unresolved `${ENV}`/secret). Preflight
codes (`ENDPOINT_PROBE_FAILED`/`PERMISSIONS_TOO_BROAD`) are reserved for OPS-3.

**`event.action` (planned, snake_case):** `sync_fetch_start`/`sync_fetch_complete`,
`sync_publish_start`/`sync_publish_complete` (cycle + `EventOutcome`); `remote_fetch`,
`remote_publish` (operation-level). Follow `*_start/*_complete` +
`source_read`/`artifact_write`. A diagnostic **does not dictate** `event.action`;
its code only adds `ioc.diagnostic.*` to the event.

**Fields (ECS-first):** no new *base* fields. Reuse ECS `file.path`/`file.name`/`file.size`
(remote object), `server.address` (host, optional/`DEBUG` — avoid exposing topology),
`event.duration`, `error.*` (through the bridge). Project `ioc.*`: 🆕
**`ioc.sync.endpoint`** (logical endpoint, no ECS equivalent); reuse
`ioc.artifact.name`/`ioc.mode`; extend **`ioc.run.id`** to mean a pipeline/fetch/publish
cycle (cycle correlation without another field); optional `ioc.sync.files`
(cycle count) as needed. Slice identity/watermark/publish↔export correlation belong
to the **0012** namespace (`ioc.export.*`); publish re-emits these fields.

**Implementation (sync stage):** `SyncDiagnosticCodes` + `DiagnosticCategory.SYNC` →
register in `DiagnosticCatalogs`, regenerate
[diagnostic-catalog.md](../DIAGNOSTICS-CATALOG.md), bump the `DiagnosticCatalogTest`
count (CODE-4); add `event.action`/`ioc.*` to `EventAction`/whitelist alongside the producer.

**11. Failure isolation.** Network/authentication errors must not bring down the
daemon or damage local artifacts; retry/backoff follows `ioc.ingestion.retry`.
A failed pass retries next cycle; publish **does not block** the pipeline.

**12. Libraries — smbj behind a thin port + reuse Spring Integration.**
- **SMB transport → `smbj`** (pure SMB2/3, no SMB1, SMB3 encryption) behind our
  **file-ops port** (thin `list/get/stat/delete` + `publishAtomically` intent,
  decision 14); smbj types must not leak into `core` (ArchUnit).
- **Driving side (polling/scheduling) → existing Spring Integration / Spring
  scheduling** (`spring-integration-file` in `adapter-ingest`), without a new
  dependency; fetch polling follows existing inbox polling.
- **Apache Camel rejected:** an EIP framework shifts the center of gravity to
  routes rather than use cases; excessive for a thin delivery slice (same rationale
  as rejecting Spark in 0012).
- **Apache Commons VFS stays on the radar:** its unified file API duplicates our
  `FileTransport` port, while its jcifs SMB provider is weaker than smbj for SMB2/3
  (breadth at the expense of SMB quality). A future `adapter-transport-vfs` candidate
  behind the same port if multi-protocol needs outweigh this. Transport replacement
  is already solved architecturally: another `adapter-transport-*` behind the port (OCP).
- **Borrow EIP patterns conceptually** (Polling Consumer, Idempotent Receiver =
  fetch-ledger, atomic publication = staging+`_SUCCESS`), without adopting a heavy
  EIP framework — like the Dataflow model without Spark in 0012.

**13. Connection lifecycle — stateless port (Model A).** `FileTransport` is
**stateless**: the use case sees thin independent file operations
(`list`/`stat`/`get`/`delete`) and a coarse write intent `publishAtomically`,
**without sessions/connections**. Raw `put`/`rename` are transport-specific adapter
primitives, not a portable application contract. Connection lifecycle is an
**adapter-internal concern**, managed by `bootstrap` (a Spring bean with lifecycle:
lazy open, **reuse within a cycle**, close on idle timeout/shutdown), **just like
the Hikari datasource** in storage (stateless application; bootstrap owns lifecycle).
Connections are **reused per endpoint** (not per operation: an SMB handshake per
file is costly; no heavy concurrent pool: the daemon cycle is single-threaded).
`RemoteSession` **leaves the port** and becomes adapter-internal (amends decision 2).
Repository precedent: `CanonicalArtifactRepository` (stateless port + adapter-internal Hikari pool).
- **Paired with Step 5:** publish is a **coarse intent** (`publishAtomically(slice)`),
  so the one atomic multi-operation batch stays in the adapter and needs no session
  in the port; fetch (`list → ledger → get×N`) consists of independent operations
  transparently reusing the adapter connection.
- **Model B rejected** (explicit `RemoteSession` in the port): atomic correctness
  does not require a session (SMB rename works through any session), and B leaks
  “connection” into `application` while degenerating for connectionless S3.

**14. Publication atomicity — coarse `publishAtomically` intent, thin independent operations.**
Boundary principle: **thin for independent operations; coarse precisely where a
multi-file invariant exists.**
- Read/cleanup (`list`/`get`/`stat`/`delete`) are **thin**, independent operations
  without an atomicity requirement: fetch and remote retention.
- Write side is **one intent**:
  `publishAtomically(endpoint, remotePath, localDir, commitMarkerName)` mirrors
  `localDir` → `remotePath`, with **data files first** and `commitMarkerName`
  (`_SUCCESS`) **last**, as the commit point.
- **Do NOT expose `rename` or raw `put` in the port**: S3 lacks these primitives
  (no atomic rename or directories); hide them inside the intent. Every adapter
  implements atomicity **natively**: SMB/SFTP temp→rename; S3 objects then marker
  object. This removes **S3 bias at the contract level**.

**`publishAtomically` contract:**
- **Invariant:** `_SUCCESS` appears at the destination ⇔ all data files are fully
  present (the marker is the linearization point).
- **Two `_SUCCESS` contexts:** marker content/checksum belongs to the formed
  slice (0012); **remote write order** (data → marker last) belongs to the 0011
  adapter, even when the marker already exists in local `var/export/...`.
- **Best-effort verification:** always sizes, hashes where cheap (S3 ETag;
  SMB/SFTP have no server-side hash → size/re-read). Full hash checking everywhere
  is not promised (cf. ACL audit OPS-3).
- **Idempotency / forward recovery:** a slice is immutable (timestamp name);
  failure before marker → consumer skips it (no `_SUCCESS`) → retry overwrites
  only its own partial state and writes the marker once at the end. Success marks
  the `publish_ledger` pair `SUCCEEDED`; delivery does not change `export_run` (decision 6).
- **Crash after remote commit, before ledger commit:** if retry finds valid remote
  `_SUCCESS` and matching manifest/checksum, treat the slice as already published
  and atomically advance the pair to `SUCCEEDED` without re-upload. If the remote
  path exists without `_SUCCESS`, the adapter may overwrite/clean only its own
  partial/temp state and write the marker last again. Existing `_SUCCESS` with
  mismatching manifest/checksum is `PUBLISH_VERIFY_FAILED`, never disguised as success.
- **Covered by a port TCK** (like `IngestionLedger` TCK in
  `core/ioc-application-tck`): one contract test, “marker visible ⇔ complete data;
  exception ⇒ no marker”; every `adapter-transport-*` runs it, protecting SFTP/S3 atomicity.

**15. Reconnect and retries — three layers (core policy, adapter mechanism, scheduler orchestration).**
Three distinct concerns must be separated:
- **Policy + executor — thin `core` foundation** (`application`, framework-free):
  `RetryPolicy` (`max-attempts`/`backoff`/`multiplier`/`max-backoff`/`jitter`) + a tiny
  `Retrier` (attempt loop + backoff + disposition predicate). No Spring or transport;
  any adapter calls `retrier.execute(op)`. Do **not** bring spring-retry (only in
  `adapter-ingest`, SI flow) into transport; `adapter-transport-smb` stays smbj-only.
- **Reconnect + micro-retry in the adapter.** Reconnect needs **no separate design**:
  it follows decision 13 (lazy open/reuse/reconnect inside the adapter). Micro-retry
  repeats `retrier.execute(op)` on `RETRY_NOW` (TRANSIENT; small N, short backoff);
  the connection layer transparently reopens the dead session on retry.
- **Macro-retry in the scheduler (bootstrap).** Retry a failed cycle on the next
  tick (decisions 8, 11), with no separate knob: `fetch.interval`/`publish.interval`.
  No reconnect here, just another use-case invocation.

**Prerequisite — idempotency** (already designed): fetch-ledger (decision 4) and
atomic publish/immutable slices (decision 14) make both retry levels safe.

**Configuration vs code** (like Q4 in 0012): **numbers in configuration**
(`ioc.sync.retry`, operator settings, `@Validated`); **`RemoteErrorKind → disposition`
classification in code** (correctness, not a knob: an operator must not make
`AUTH_FAILED` retryable). Bootstrap binds configuration to a `RetryPolicy` value
object and passes it inward (core remains framework-free). Defaults are safe out
of the box. **Seam:** per-endpoint retry override as needed (start with shared
`ioc.sync.retry`); backoff between macro cycles for persistent failures is optional/later.

**Not included:** circuit breaker / Resilience4j — excessive for a few endpoints.

**16. Credential resolution — credential-blind port, binding-time resolution, deferred resolver port.**
Extends decision 9 (secrets through `${ENV}`). Secret lifecycle responsibilities:
- **Credential-blind port:** the use case/`FileTransport` does **not** see credentials
  (least privilege, consistent with decision 13). `TransportRegistry` (bootstrap)
  resolves endpoint name → connection parameters, including credentials; the
  adapter builds smbj `AuthenticationContext`. Credentials are per endpoint.
- **Acquisition (v1):** env / systemd env-file (`${ENV}`); committed configuration
  contains only `${SMB_PASS}` references, never plaintext. Vault/AWS-SM is a seam.
- **Resolution differs by _time_, rather than source:** **binding-time** (Spring
  property source, `${ENV}`; Vault can also use `spring-cloud-vault` through the same
  `${…}` mechanism without a custom port) suffices for **static** secrets.
  A **connect-time `CredentialResolver` port** is justified only for
  **dynamic/rotating** secrets (Vault dynamic, short leases: startup resolution does
  not rotate). V1 uses binding-time resolution, with acquisition behind a thin
  **adapter-internal seam** (`SmbCredentials`/`CredentialSource`) for a local switch
  to runtime resolution. Add the full `CredentialResolver` port **when dynamic
  secrets arrive** (YAGNI, like DuckDB on the radar).
- **Use / hygiene:** smbj `AuthenticationContext`, SMB3 encryption (decision 9);
  **never log** credentials, use `char[]` + clear where supported (smbj accepts
  `char[]`); avoid elaborate heap zeroization (env-sourced static secrets already
  exist in the process environment). **Actuator hygiene:** a `password` field gets
  Spring Boot `password/secret/token/key` masking in `/configprops` and `/env`
  automatically; credentials must not appear in bound-object `toString()`
  (extends decision 9 from “no logging” to “no exposure by configuration objects”).
- **Failure** (error axis in decision 10): unresolved secret → `CREDENTIAL_MISSING`
  (FATAL, fail-fast); rejected secret → `AUTH_FAILED` (FATAL, disposition `FAIL`:
  no retry that could lock the account).

**17. Payload shape — `Path` + adapter streaming and transformations.** The port uses
**local `Path` values** (`get(endpoint, remotePath, Path localDst)`;
`publishAtomically(…, Path localDir)`): both ends already deal with files
(`var/inbox` / `var/export/...`), following repository idioms (`SourceReader`,
`CsvArtifactProjection`, ECS `file.path`).
- **`Path` does not mean in-memory:** the adapter reads/writes it by **streaming
  copy** (channel / `Files.copy`) ↔ remote socket, using **constant memory** at any size.
- **Transformations (ref-9 clarification): v1 publish is byte-preserving.** Transparent
  protocol features (SMB3 encryption/compression) preserve end-to-end bytes and
  therefore the slice manifest checksum. **At-rest** compression/encryption
  **changes bytes**, so it is a future **formation option (0012)**, not v1
  (slice = `.gz`/`.enc`, manifest covers final bytes), **not publish transit**,
  which would break checksums. A streaming read hash (`DigestInputStream`) preserves
  bytes and remains allowed. The single manifest stays the integrity root (ref-4).
  (The former `ioc.sync.publish.compress` version is withdrawn.)
- **Rejected:** streams in port signatures (leak lifecycle into the use case,
  conflict with decisions 13/14, no net gain since the adapter does it internally),
  and `byte[]` (heap growth for large lists).

**18. Retention/deletion — our local data, recipient-owned destination (symmetric with fetch).**
Who cleans accumulated data on each side:
- **Do not touch the fetch source** (read-only, decision 4); fetch-ledger handles dedup, not deletion.
- **Local slices** (`var/export/...`, ours) use **slice-granular retention** (F2):
  unit = entire directory with `_SUCCESS`; max-age/max-count **per slice**, no leaf
  reaper; exclude staging/incomplete data. `PublishLedgerSliceRetentionGuard`
  compares expected profile targets with the ledger: missing/`PENDING`/`IN_PROGRESS`/
  `FAILED` block; `SUCCEEDED`/`ABANDONED` allow deletion. With sync disabled/no targets,
  use the standalone guard. Permanent failure pins a slice until operator abandon
  or opt-in `force-after`, so max-count is best-effort for pinned slices. Reuse
  ING-1 policy/scheduler; store/guard are slice-level.
- **Publish destination default: recipient-owned retention.** We only **write**,
  without requesting delete on someone else's share (mirror of read-only fetch,
  least privilege); completed slices are self-contained, so the recipient keeps
  the latest and cleans older ones.
- **Opt-in publish-side retention** (`ioc.sync.publish.retention`: max-age/max-count)
  for deployments **where we own the destination** and want cleanup. Requires
  delete permission; mechanism is ready (`FileTransport.delete`, decision 14).
  Symmetric counterpart to the rejected fetch `after:` semantics.

<a id="эскиз-конфига"></a>
### Configuration sketch

```yaml
ioc:
  sync:
    enabled: false
    retry: { max-attempts: 3, backoff: 1s, multiplier: 2.0, max-backoff: 30s, jitter: true }  # micro
    endpoints:
      - { name: intel-share, transport: smb,
          smb: { host: 10.0.0.5, share: intel, domain: CORP,
                 username: ${SMB_USER}, password: ${SMB_PASS}, encrypt: true } }
      - { name: dist-share,  transport: smb,
          smb: { host: 10.0.0.9, share: dist, username: ${SMB_USER2}, password: ${SMB_PASS2} } }
    fetch:                              # вход: remote -> var/inbox
      enabled: true
      interval: 1m
      sources:
        - { endpoint: intel-share, remote-path: "/incoming",
            include: [ "*.htm", "*.docx" ] }   # источник read-only, дедуп через ledger
    publish:                            # выход: сформированные артефакты -> remote
      enabled: true
      trigger: on-new-output            # on-new-output | interval | both
      interval: 5m                      # output-mode/окно/маркер — см. 0012
      # retention: { max-age: 30d, max-count: 10 }   # opt-in: самоочистка приёмника (нужен delete-грант), реш. 18
      targets:
        - { endpoint: dist-share, remote-path: "/reputation-lists",
            export-profile: reputation-lists }
```

<a id="следствия"></a>
## Consequences

Implemented consequences:

- New `adapters/adapter-transport-smb` module (smbj in parent `dependencyManagement`).
- New packages: `application/sync` (use case), `port/in/sync`, `port/out/sync`
  (**stateless `FileTransport`**, neutral error model, `RemoteFetchLedger`,
  `PublishLedger` keyed by (slice_id, target_id), `CompletedSliceCatalog` for
  verified local slices, `PublishLedgerSliceRetentionGuard` — F2/F3;
  `RemoteSession` stays in the adapter, decision 13).
- Read manifests with shared `SliceManifestCodec` from
  `adapter-manifest-json-jackson` (0012); no JSON parsing in transport; fail-fast
  validation of every target `export-profile` against `ioc.export.profiles`.
- Forward service-schema migration for `publish_ledger` (`slice_id`, `target_id`,
  `profile`, `slice_name`, `manifest_sha256`, `endpoint`, `remote_path`, status,
  attempts, timestamps, last_error, remote checksum/verification metadata);
  unique `(slice_id,target_id)` — F3.
- `bootstrap`: `DaemonFetchScheduler`, `DaemonPublishScheduler` (`SmartLifecycle`,
  phase between export formation and slice retention; startup reconcile before
  periodic loop), `TransportRegistry`, `ioc.sync` in `IocProperties`.
- Pure `CadenceSource` (`interval`/`quiet-period`+max-cap) reused by 0012 export
  and ING-7 (guardrail Q2).
- **Retention** (decision 18): local slices use slice-store + delivery guard over
  ING-1 policy/scheduler; destination is recipient-owned by default, opt-in remote
  retention where we own it.
- `adapter-cli-picocli`: `ioc sync` subcommand.
- `application.yml`: `ioc.sync`; ArchUnit rule preventing smbj visibility from `core`.
- Docs: user-facing `docs/sync.md` (or a section in `ingestion.md`), entry in
  `docs/dev/README.md`.

<a id="открытые-вопросы"></a>
## Open questions

1. ✅ **Where is the transport-agnostic boundary? — RESOLVED (facets, decisions 13–17).**
   The port combines thin independent file ops (`list/get/stat/delete`) with the
   coarse `publishAtomically` intent (decision 14), `Path` payload, statelessness
   (decision 13); the service orchestrates. Repository examples:
   `PatternEngine`/`SourceReader`/storage port. This boundary is **a set of seams**,
   rather than one line; all are resolved:
   - ✅ **Session / connection lifecycle:** Model A (stateless port, adapter/bootstrap
     lifecycle), **decision 13**.
   - ✅ **Publication atomicity:** coarse `publishAtomically` intent + thin independent
     operations; no `rename`/`put` in the port (S3 bias removed), **decision 14**.
   - ✅ **Reconnect / retries:** three layers: thin `core` `RetryPolicy`/`Retrier`,
     adapter micro-retry+reconnect (follows decision 13), scheduler macro cycle retry;
     `RETRY_NOW/LATER/FAIL` disposition, **decision 15**.
   - ✅ **Credentials:** credential-blind port; binding-time resolution (Spring
     property source, `${ENV}`); `CredentialResolver` port deferred until dynamic
     secrets (resolution _time_ determines the need for a port), **decision 16**.
   - ✅ **Neutral errors:** port-neutral `RemoteErrorKind`
     (`unreachable/auth-failed/permission-denied/not-found/transient`) ↔ `SYNC.*`
     codes, **decision 10**; `retryable` drives reconnect behavior.
   - ✅ **Payload:** `Path` (adapter streams internally; compress/encrypt/hash
     transformations are adapter seams), **decision 17**.

> Continuous-stream dataframe formation questions (what a “complete set” means,
> `complete`/`append` output-mode, export cadence/trigger) belong to
> [0012](0012-streaming-dataframe-emission.md), a separate concern to which delivery
> is agnostic.

<a id="план-реализации-по-срезам"></a>
## Implementation plan by slice

Implementation has nine slices, `S0`–`S8`. Each ends with a separate commit, updated
directory READMEs for affected Java directories, and tests for new business logic/
invariants. Order matters: pure model and durable state first, then use-case
orchestration, then real SMB adapter and runtime/productization.

<a id="s0--sync-contracts-config-model-diagnostics-skeleton"></a>
### S0 — sync contracts, config model, diagnostics skeleton

**Commit:** `ARCH: introduce remote sync contracts`

Implement:

- `application/sync` with framework-free value objects and policies;
- `port/in/sync` and `port/out/sync`;
- stateless `FileTransport` contract:
  - thin independent `list`, `stat`, `get`, `delete` operations;
  - coarse write intent `publishAtomically(endpoint, remotePath, localDir, commitMarkerName)`;
  - no raw `put`/`rename` or `RemoteSession` in the port;
- `RemoteErrorKind` and code-owned `RETRY_NOW | RETRY_LATER | FAIL` mapping;
- `RetryPolicy`/`Retrier` in `application`, without Spring or transport types;
- `ioc.sync` in `IocProperties`, with fail-fast validation:
  - duplicate endpoint/target names;
  - unknown endpoint references;
  - invalid trigger/retry intervals;
  - missing required SMB credential placeholders after binding;
- `DiagnosticCategory.SYNC`, `SyncDiagnosticCodes`, registration in `DiagnosticCatalogs`;
- sync `EventAction` and `LogField` whitelist (`ioc.sync.endpoint`, counters/status fields);
- package-info/Javadoc/README for new packages.

**Acceptance/tests:**

- retry/disposition unit tests;
- configuration validation tests without transport/storage IO;
- updated diagnostic catalog test;
- updated observability taxonomy whitelist;
- ArchUnit guard: no smbj/transport-specific types accessible from `core`.

<a id="s1--service-db-ledgers"></a>
### S1 — service DB ledgers

**Commit:** `STORAGE: add remote sync ledgers`

Implement:

- service migrations for `remote_fetch_ledger` and `publish_ledger`;
- JDBC `RemoteFetchLedger` adapter:
  - remote identity key `path + size + mtime`;
  - fetched/skipped/failed status with the minimum required for retry;
- JDBC `PublishLedger` adapter:
  - unique `(slice_id, target_id)`;
  - operational identity: `profile`, `slice_name`, `manifest_sha256`;
  - target binding: `endpoint`, `remote_path`;
  - statuses `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `ABANDONED`;
  - attempts/timestamps/last_error/verification metadata;
  - CAS/expected-status transitions for concurrent scheduler/CLI safety;
- health/reconcile read models;
- JDBC adapter README update.

**Acceptance/tests:**

- fresh/upgrade migration tests;
- idempotent insert/reconcile contract tests;
- CAS conflicts are not hidden;
- `ABANDONED` is terminal for retention;
- `FAILED` remains retryable;
- unique `(slice_id,target_id)` protects against duplicate discovery.

<a id="s2--local-export-slice-discovery"></a>
### S2 — local export slice discovery

**Commit:** `EXPORT: expose verified slices for delivery`

Implement:

- `CompletedSliceCatalog`/similar read-only publish worklist port;
- completed local slice model:
  - `sliceId`;
  - `profile`;
  - `sliceName`;
  - `manifestSha256`;
  - local `Path`;
  - `SliceManifest`;
- filesystem implementation over `var/export/<profile>/<slice>/`;
- reuse the 0012 verification chain:
  - `_SUCCESS` exists;
  - `_SUCCESS` contains manifest hash;
  - manifest decodes through `SliceManifestCodec`;
  - every artifact hash/size matches the manifest;
  - exact directory membership rules remain strict;
- fail-fast target `export-profile` validation against `ioc.export.profiles`.

**Acceptance/tests:**

- valid completed slice appears in catalog;
- staging/partial/incomplete slice is ignored or reported by contract, never published;
- corrupt final slice surfaces as corruption, not pending work;
- symlink/unsafe path rejected;
- profile pools independent;
- no `FileSystemSliceRetentionStore` as publish worklist API.

<a id="s3--publish-application-saga"></a>
### S3 — publish application saga

**Commit:** `FEATURE: publish export slices by ledger`

Implement:

- `ArtifactPublishUseCase`;
- reconcile `verified completed slices × configured targets → publish_ledger`;
- per-target fan-out: one slice publishes independently to N targets;
- pair lifecycle:
  - `PENDING → IN_PROGRESS → SUCCEEDED`;
  - `FAILED` retries according to policy/disposition;
  - `ABANDONED` terminal/operator decision;
- idempotent forward recovery:
  - matching remote `_SUCCESS` and manifest/checksum → mark `SUCCEEDED`;
  - remote partial without marker → repeat/overwrite adapter-owned partial state;
  - remote marker/checksum mismatch → `SYNC.PUBLISH_VERIFY_FAILED`;
- `PublishLedgerSliceRetentionGuard`:
  - missing pair, `PENDING`, `IN_PROGRESS`, `FAILED` block deletion;
  - `SUCCEEDED`/`ABANDONED` allow deletion;
  - configured profile targets count before ledger-row materialization;
- `ArtifactPublishObserver`/logging boundary or reuse sync observer pattern.

**Acceptance/tests:**

- fan-out with different target statuses;
- discovery creates missing pairs before publish;
- target failure does not block another target;
- crash after remote commit/before ledger commit recovers to `SUCCEEDED`;
- remote marker mismatch emits a diagnostic without marking success;
- retention guard blocks missing/pending/failed pairs;
- no `export_run` mutation.

<a id="s4--fetch-application-use-case"></a>
### S4 — fetch application use-case

**Commit:** `FEATURE: fetch remote files into inbox atomically`

Implement:

- `RemoteFetchUseCase`;
- source include/exclude filtering;
- read-only remote semantics: no move/delete/claim in v1;
- fetch-ledger dedup by remote identity `path + size + mtime`;
- atomic landing:
  - download to temp/staging invisible to ingestion include patterns;
  - close/fsync where available;
  - atomic move to `ioc.ingestion.dirs.inbox`;
  - ledger mark only after successful final move;
- deterministic local filename collisions:
  - previously fetched identity → skip;
  - new identity with occupied name → stable remote-identity suffix;
- dry-run result model shared with CLI.

**Acceptance/tests:**

- duplicate remote identity skipped;
- failed download leaves no final inbox file or fetched ledger mark;
- failure between temp write and move is retry-safe;
- name collision does not overwrite existing inbox file;
- include/exclude works without transport-specific path leaks;
- source remains read-only.

<a id="s5--smb-transport-adapter"></a>
### S5 — SMB transport adapter

**Commit:** `ADAPTER: add SMB file transport`

Implement:

- new `adapters/adapter-transport-smb` module;
- smbj dependency in parent `dependencyManagement`;
- SMB endpoint settings:
  - host/share/domain/username/password/encrypt/timeouts;
  - no credentials in `toString()`/logs;
- adapter-internal connection/session lifecycle:
  - lazy connect;
  - reused-per-endpoint within cycle;
  - reconnect on transient failure;
  - close on idle/shutdown;
- `FileTransport` implementation:
  - `list`;
  - `stat`;
  - streaming `get(Path localDst)`;
  - `delete` only for opt-in remote retention;
  - `publishAtomically`;
- SMB-native atomic publish:
  - upload data files to adapter-owned temp path;
  - write marker last;
  - temp→final rename where available;
  - verify sizes/checksum best-effort;
- transport exception translation to `RemoteErrorKind`;
- adapter README/package docs.

**Acceptance/tests:**

- port TCK for `publishAtomically`: marker visible iff complete data set visible;
- throw before marker leaves no committed slice;
- remote already committed matching slice is idempotent success;
- transient SMB errors map to `TRANSIENT`;
- auth/permission/not-found map to correct neutral kinds;
- no smbj types in `core` or application public APIs.

<a id="s6--bootstrap-wiring-and-daemon-schedulers"></a>
### S6 — bootstrap wiring and daemon schedulers

**Commit:** `FEATURE: schedule remote fetch and publish`

Implement:

- bootstrap `TransportRegistry`: endpoint name → transport+settings;
- Spring composition for sync use cases/adapters under `ioc.sync.enabled`;
- `DaemonFetchScheduler`;
- `DaemonPublishScheduler`;
- lifecycle ordering:
  - export formation `PHASE = 100`;
  - publish `PHASE = 150`;
  - slice retention `PHASE = 200`;
- `DaemonPublishScheduler.start()`:
  - synchronous reconcile first;
  - then periodic loop;
- trigger semantics:
  - `on-new-output` = poll/reconcile verified slices and ledger gaps;
  - `interval` = fixed cadence;
  - `both` = both conditions, still restart-safe;
- overlap guards and controlled shutdown;
- failures isolated per source/target and retried next tick.

**Acceptance/tests:**

- publish starts before retention and after export scheduler;
- start reconcile creates pending pairs before first periodic tick;
- slow run does not overlap;
- failure one source/target does not stop daemon;
- restart repeats pending/failed work;
- disabled sync creates no schedulers and standalone retention guard remains active.

<a id="s7--cli-ioc-sync"></a>
### S7 — CLI `ioc sync`

**Commit:** `FEATURE: expose remote sync CLI`

Implement:

- `ioc sync fetch`;
- `ioc sync publish`;
- `ioc sync all` or a general mode if it does not complicate UX;
- `--dry-run`;
- filters/options:
  - source/target/profile selection where practical;
  - fail-fast unknown endpoint/profile before heavy IO;
- lazy resolution like `ioc export`, so unrelated commands do not open service DB/transport;
- deterministic exit codes and concise operator output;
- CLI README updates.

**Acceptance/tests:**

- unknown profile/endpoint fails before transport resolution;
- dry-run does not write inbox/remote/ledger;
- fetch command reports fetched/skipped/failed counts;
- publish command reports pending/succeeded/failed/abandoned counts;
- transport failure returns non-zero but does not corrupt local state.

<a id="s8--health-docs-e2e-gate"></a>
### S8 — health, docs, e2e gate

**Commit:** `TEST: gate remote sync end to end`

Implement:

- health read model:
  - last fetch per source;
  - last publish per target/profile;
  - pending/failed publish counts;
  - endpoint status summary;
  - retention-pinned slices count;
- generated diagnostic catalog update;
- published docs:
  - `docs/sync.md`;
  - `docs/architecture.md`;
  - `docs/modularization.md`;
  - `docs/ingestion.md`;
  - `docs/diagnostics.md`/generated catalog;
  - `docs/logging.md`;
  - `docs/techdebt.md`;
  - directory README updates;
- ArchUnit final guardrails:
  - smbj only in `adapter-transport-smb`/bootstrap wiring;
  - Spring/JDBC/Jackson boundaries unchanged;
- E2E/integration coverage:
  - remote fetch → inbox → ingest;
  - canonical write → export slice → publish;
  - restart/reconcile;
  - publish duplicate/idempotent success;
  - retention guard blocks until publish terminal;
  - fresh/upgrade service migrations.

**Final gate:**

```bash
./mvnw -B -ntp -T 1C verify
```

<a id="группировка-срезов-для-реализации"></a>
## Grouping implementation slices

Practical grouping — four passes:

1. **Pass 1: S0–S2** — foundation without real SMB: contracts/config/diagnostics,
   service ledgers, verified local slice discovery.
2. **Pass 2: S3–S4** — application behavior: publish saga, fetch use case,
   delivery-aware retention guard, crash/retry invariants.
3. **Pass 3: S5** — SMB adapter separately: external dependency,
   transport-specific IO, credential hygiene and distinct boundary risks.
4. **Pass 4: S6–S8** — runtime/productization: schedulers, CLI, health, docs,
   integration/e2e and final gate. If too large, split pass 4 into `S6–S7` and
   separate `S8` hardening.
