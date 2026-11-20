# Remote storage synchronization

[Русская версия](ru/sync.md)

Sync connects remote file storage to local capabilities while preserving their
responsibilities:

```text
remote source -> fetch -> local inbox -> ingestion -> canonical SQLite
canonical SQLite -> export slice -> publish -> remote target
```

Fetch does not extract IOC; publish does not form artifacts. `FileTransport`
hides remote I/O; the current SMB2/3 implementation lives in `adapter-transport-smb`.
The [operator guide](../guides/remote-storage-sync.md) owns configuration and
server setup. Managed import has separate ownership and mutation rights.

## Fetch flow

```text
periodic/startup/CHANGE_NOTIFY hint
  -> detect: list + include/exclude + ledger/in-flight filtering
  -> RemoteChangeBatchDetected
  -> endpoint-keyed admission
  -> download into hidden .sync-staging/*.part
  -> force file + atomic move into inbox
  -> remote_fetch_ledger = FETCHED
```

Detection and execution are separate. `RemoteSourceMonitor` lists, filters and
forms bounded batches; event-driven `RemoteFetchService.fetch(FetchRemoteObjectsCommand)`
does not list again. The CLI selection-based fetch performs its own listing.
Process-local in-flight claims reduce duplicate submissions; the durable ledger
survives restart. Ordinary fetch reads remote sources without moving/deleting them.

The implemented identity is `(path, size, modifiedAt)`, not a content hash. A
re-upload is new only when this tuple changes. Neither source nor endpoint is
part of that key: two endpoints with the same tuple can suppress the second
fetch (`OPS-11`). See [known issues](../KNOWN-ISSUES.md). Do not infer a separate
idempotency namespace from a configured endpoint name.

Fetch writes staging bytes, forces the file and atomically moves it to the inbox
before marking the ledger fetched. Directory forcing is best effort on platforms
that reject directory channels. A crash after landing but before ledger closure
can land another copy; this is at-least-once transfer, not distributed exactly-once.
Canonical row deduplication does not imply that all repeated observations are inert.

An already occupied final name normally gets a stable identity suffix. This is
not a no-replace guarantee: the existence check precedes the atomic move, and
parallel endpoints or another writer can claim that name in between. On Linux,
two successful moves can leave only one source (`OPS-12`). Use disjoint producer
filenames while the implementation gap remains open. Atomic visibility alone
neither fences writers nor proves producer completion. Producers must close an
excluded temporary file and rename it before discovery; debounce cannot supply
that contract. Fetch does not revalidate listed size/mtime around the download.

## Publish flow

Publish accepts only a completed local slice that passed `_SUCCESS`, manifest
decode, membership and hash/size verification by the completed-slice catalog.

```text
completed slice x configured target
  -> publish-ledger discovery/retryable work
  -> endpoint-keyed admission
  -> upload non-marker files into a private remote staging directory
  -> upload _SUCCESS last within staging -> verify uploaded sizes
  -> rename staging to final slice directory -> verify final marker
  -> publish_ledger = SUCCEEDED
```

The ledger owns one saga for `(slice_id, target_id)`:

```text
PENDING -> IN_PROGRESS -> SUCCEEDED
                   \-> FAILED -> IN_PROGRESS
PENDING|IN_PROGRESS|FAILED -> ABANDONED
```

`SUCCEEDED` and `ABANDONED` are terminal. Stale `IN_PROGRESS` work becomes retryable
after the recovery threshold; a fresh attempt is not immediately stolen. After a
crash, a remote marker matching the bound manifest hash can complete the ledger
without uploading again. A mismatching marker fails verification and is not
silently overwritten. Matching marker recovery does not download and hash every
remote CSV; the receiver must verify manifest membership, sizes and hashes itself.

The SMB adapter uploads into a random `.tmp-…` sibling, checks file sizes and
renames the directory into its final location. Consumers ignore staging directories
and accept only final slices with a matching marker and independently checked
manifest/files. An uncommitted final directory without the marker can be removed
before retry; the configured publish namespace must belong to this flow.
Committed remote slices are not a remote retention service.

Retention waits for a terminal pair for every currently configured target of the
profile. Missing discovery, pending, in-progress or failed work pins the local
slice; `ABANDONED` releases the pin without proving delivery. Removing a target
changes the retention obligation and is an operational decision. Count/age limits
are best effort while slices remain pinned. The delivery veto is wired only while
daemon sync/publish is enabled with JDBC service storage. Disabling
`ioc.sync.enabled` or `ioc.sync.publish.enabled` removes that veto; preserve
unresolved slices before reconfiguring rather than assuming their pins survive.
There is no public abandon/requeue
CLI; do not manufacture terminal states by editing ledger tables.

## Coordination and retry

Events and SMB `CHANGE_NOTIFY` accelerate work; durable state and periodic
reconcile remain the correctness path for eligible objects still available at
reconciliation time. A file created and deleted between listings is not recoverable
from a notification. There is no durable event queue.

- Periodic detection rediscovers unfetched remote tuples.
- Publish reconcile discovers missing slice/target pairs and retries durable work.
- Fast paths and daemon backstops submit endpoint work to the same
  `KeyedSerialExecutor`; different endpoints can progress in parallel.
- Bounded admission may shed hints; it does not delete durable work. Detection is
  single-flight per source, with triggers coalesced by its coordinator.
- The shared SMB session pool also serializes regular operations per endpoint.
  These are process-local controls, not a multi-process lease.

`Retrier` counts the initial call in `max-attempts` and retries only `TRANSIENT`
(`RETRY_NOW`) within a cycle. `UNREACHABLE` and `RESOURCE_EXHAUSTED` use
`RETRY_LATER`; authentication, permission, not-found and security-policy errors
use `FAIL`. A later scheduled cycle can try unresolved work again; permanent
classification does not make credentials or a broken configuration repair itself.
A durable failed publish pair can make health DOWN even when its transport cause
was transient. The watch has its own reconnect loop and does not use
`max-attempts` as a lifetime limit.

## Configuration and transport boundary

Credentials come from environment placeholders. Operational logs/health use logical
identifiers and sanitized failure summaries; raw endpoint secrets and remote IOC
values do not belong there. Strict preflight rejects unknown references before I/O.

For SMB:

- `encryption=required` offers only SMB3 and checks effective authenticated-session
  encryption before share I/O; failure is `SECURITY_POLICY_UNMET`, without an
  immediate network retry.
- `preferred` requests encryption but permits an unencrypted SMB2/3 fallback;
  `disabled` does not request client-preferred encryption.
- `port` defaults to `445` for both regular and dedicated watch sessions.
- `connect-timeout` bounds TCP connect, not DNS or the whole synchronization cycle.
- `request-timeout` bounds one regular SMB request, not the idle notification wait.
- `idle-timeout` closes unused cached transport clients; the long-lived watch has
  a separate session lease/reconnect policy.
- Unsupported legacy `read-timeout` gets a migration hint. Temporary rollback alias
  `encrypt` maps true to `required`, false to `disabled`, emits a value-free warning
  and conflicts with an explicitly supplied `encryption` (ADR-0027).

Transport failures become `RemoteErrorKind` and stable `SYNC.*` diagnostics.
Where a work record exists, failure state is persisted before diagnostics.
Raw numeric SMB capacity/resource NTSTATUS becomes `RESOURCE_EXHAUSTED` and
`SYNC.REMOTE_RESOURCE_EXHAUSTED`, including values missing from SMBJ's enum.
The client cannot derive server-wide usage or configured headroom from this error.

## Lifecycle, health and CLI

Daemon lifecycle ordering starts fetch before export, publish after export and
retention after publish discovery. Exact phase numbers belong to bootstrap code.
Recovery and remote availability are distinct: ordinary sync does not perform a
complete authentication/write/ACL probe solely to turn startup health green.

`sync` health combines durable publication backlog, source/target observations,
keyed executor and watch state. Some observations reset with the process; ledgers
do not. Reconnecting watch grace and bounded shed-to-reconcile are degradation
signals; failed durable delivery or permanent outcomes can produce DOWN.

`smbTransport` reports application-owned established connections/sessions/trees,
`pooled_transport`/`change_notify` roles, planned steady demand and capacity
failures. A capacity failure remains DEGRADED until confirmed success for its
internal owner; bounded endpoint/role labels do not expose that owner. This is
not server-wide accounting or a capacity acceptance test.

CLI `sync fetch`, `sync publish` and `sync all` use the same application contracts.
`sync all` preflights both selections, then fetches and publishes existing slices;
it does not wait for ingestion or run export between them. A separate CLI JVM shares neither the daemon's endpoint lanes nor its session
pool.
Coordinate manual writes with the daemon and other operators.

`--dry-run` avoids fetch landing, publish mutations and ledger transitions in the
use cases. Fetch can list remote metadata; publish inspects local completed slices
and the delivery ledger without testing remote write permissions. Full Spring CLI
startup can still create/migrate the service schema; it is not a zero-I/O sandbox.
See the operator guide for executable commands and exit semantics.

## Invariants and limits

1. Landing makes acquired bytes atomically visible; `OPS-12` limits collision safety.
2. Publish starts from a verified completed local slice.
3. Final visibility follows staging upload/size verification; the marker binds the manifest.
4. Durable transition takes precedence over an event or log observation.
5. Endpoint exclusion is process-local; different endpoints can run concurrently.
6. Lost hints are recovered from available source/catalog/ledger state, not event payloads.
7. Sync does not own extraction, canonical schema or export formation.
8. `encryption=required` refuses share I/O without effective SMB3 encryption.

## Extension

New protocols implement `FileTransport` and optionally `RemoteChangeSignalSource`.
The watch receives only `RemoteWatchTarget(sourceId, endpoint, remotePath)`;
include/exclude policy stays in application detection. Remote delete/move/retention
requires an explicit ownership decision. New targets reuse slice/target delivery
semantics. Cross-process fencing or durable message delivery requires an explicit
adapter/protocol decision, not an implicit expansion of `platform-events`.

## Sources of truth

- [Fetch](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteFetchService.java),
  [monitor](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteSourceMonitor.java)
  and [publish](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/ArtifactPublishService.java)
  own application behavior.
- [SMB adapter](../../adapters/adapter-transport-smb/README.md) owns session/wire details;
  [composition](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SyncConfig.java)
  wires scheduling, listeners, storage and transport.
- [Defaults](../../bootstrap/ioc-app/src/main/resources/application.yml) and
  [CLI](../../adapters/adapter-cli-picocli/README.md) own configuration/command surfaces.

## When to update

Update this document, its translation and affected guide when remote identity,
marker/ledger state, retry/reconcile, endpoint exclusion or ownership changes.
Keep exact field inventories with configuration references.

## Related documents

- [Artifact export](artifact-export.md) — completed-slice contract.
- [Event coordination](event-coordination.md) — fast paths and backstops.
- [Configuration](configuration.md) — strict reference validation.
- [Observability](observability.md) — diagnostics and sensitive fields.
- [ADR-0011](../ADR/0011-remote-sync.md) — remote sync decision.
