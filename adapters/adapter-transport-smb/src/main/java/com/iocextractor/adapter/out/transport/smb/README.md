# SMB transport package

## Purpose and boundary

Implement inward file-transport and managed-import source ports while keeping
SMBJ sessions and handles inside this package. Application owns durable work
identity, retry decisions, source readiness orchestration and periodic reconcile;
bootstrap constructs the adapters and owns their runtime lifecycle.

## Structure

| Types | Responsibility |
|---|---|
| [SmbFileTransport](SmbFileTransport.java) | `FileTransport` listing, stat, download, delete and marker-based publish |
| [SmbSessionPool](SmbSessionPool.java) | Lazy endpoint-keyed clients, operation exclusion, invalidation and idle close |
| [SmbEndpointSettings](SmbEndpointSettings.java), [SmbEncryptionPolicy](SmbEncryptionPolicy.java) | Endpoint identity, port, copied credentials, negotiation and timeout policy |
| [SmbShareClient](SmbShareClient.java), [SmbShareClientFactory](SmbShareClientFactory.java) | Package-private file/session seams for production and deterministic fakes |
| [SmbjShareClient](SmbjShareClient.java), [SmbjShareClientFactory](SmbjShareClientFactory.java) | SMBJ operations, authentication, effective encryption check and resource cleanup |
| [ConnectTimeoutSocketFactory](ConnectTimeoutSocketFactory.java) | Bounded TCP connect and failed-socket cleanup |
| [SmbRemoteEntry](SmbRemoteEntry.java) | Server metadata, file ID, directory/reparse flags; managed regular-file predicate |
| [SmbImportSourceDefinition](SmbImportSourceDefinition.java), [SmbManagedImportSourceLifecycle](SmbManagedImportSourceLifecycle.java) | Source location, stability, probe, claim/materialize, disposition and exact retention |
| [SmbChangeNotifyWatcher](SmbChangeNotifyWatcher.java) | One dedicated daemon worker/session per watch, re-arm, lease renewal and reconnect |
| [SmbChangeNotifySessionFactory](SmbChangeNotifySessionFactory.java), [SmbChangeNotifySession](SmbChangeNotifySession.java), [SmbChangeNotifyPending](SmbChangeNotifyPending.java), [SmbChangeNotifyResult](SmbChangeNotifyResult.java) | Internal watch opening, pending result/cancellation and overflow seams |
| [SmbjChangeNotifySessionFactory](SmbjChangeNotifySessionFactory.java) | SMBJ directory handle, asynchronous notification and status decoding |
| [SmbImportChangeSignalSource](SmbImportChangeSignalSource.java) | Import-source hints, partial-start cleanup and reverse watch-close ordering |
| [SmbExceptionMapper](SmbExceptionMapper.java) | SMB/IO failures translated into application transport-error kinds |
| [SmbTransportTelemetry](SmbTransportTelemetry.java) | Thread-safe adapter-owned resource leases and failure snapshots |
| [package-info.java](package-info.java) | Package boundary |

## Dependencies and wiring

SMBJ is the external integration family; application ports/models and transitive
platform errors face inward. No Spring annotations or sibling adapter calls are
needed. [Module README](../../../../../../../../../README.md) links composition roots;
[test README](../../../../../../../../test/java/com/iocextractor/adapter/out/transport/smb/README.md)
maps offline and external coverage. Precise member contracts remain in Javadoc.

## Session ownership and failures

The pool opens at first use and holds one cached client per configured endpoint
name. Its endpoint lock covers the entire action, reconnect invalidation and
idle/shutdown close. A long transfer delays other operations using that name;
a second endpoint name is a separate lane even when it refers to the same server.
The pool itself does not retry an operation. `TRANSIENT`, `UNREACHABLE` and
`RESOURCE_EXHAUSTED` failures invalidate the client so a later attempt reconnects.

The transport's endpoint-list constructor owns its pool; the constructor taking
an existing pool borrows it, and transport `close()` does not close that shared
pool. The composition root closes shared pools and individual watch handles.
`closeIdle()` is explicitly scheduled by callers; there is no internal reaper.
Quiesce callers before shutdown rather than treating `close()` as a permanent
admission fence.

`SmbEndpointSettings` copies password input and accessor output, and redacts it
in `toString()`. Authentication clears its temporary password copy. This is not
a guarantee that the configured secret or SMBJ authentication state is erased
from memory; never log credential values.

| Setting / policy | Scope |
|---|---|
| TCP port | Defaults to 445; the explicit port also reaches dedicated watch connections |
| `connectTimeout` | TCP dial via `Socket.connect(timeout)`; factory accepts 1 ms through `Integer.MAX_VALUE` ms |
| `requestTimeout` | Individual SMB read/write/transact request; not a whole-file deadline |
| `idleTimeout` | Cached client idle lease checked by `closeIdle()` |
| Socket `SO_TIMEOUT=0` | Idle socket reader remains alive; asynchronous pending watch polling has its own lease |
| `REQUIRED` | SMB3 dialects only, effective authenticated-session encryption checked before share I/O |
| `PREFERRED` / `DISABLED` | Allow weaker negotiation; disabled does not prohibit encryption required by the server |

An effective-encryption mismatch is `SECURITY_POLICY_UNMET`. Authentication,
permissions, missing objects, unreachable hosts, transient IO and raw NTSTATUS
resource/quota failures map to distinct `RemoteErrorKind` values. Already mapped
transport failures are preserved. Application policy decides their disposition.

Telemetry tracks established application-owned connection/session/tree leases,
not server limits, total server consumption or file-handle counts. Snapshots are
weakly consistent and aggregate internal owners by endpoint/role; one watch's
recovery cannot hide another owner's capacity failure. Micrometer and actuator
projection belong to bootstrap.

## File operations and publish protocol

Remote paths are share-relative and normalize slash direction/repetition.
Callers supply dedicated configured locations, not arbitrary untrusted paths;
this normalization is not a server ACL or reparse-point sandbox. Sync `list` and
`stat` omit directories; managed import additionally rejects reparse entries via
`regularFile()`. Listing is one directory level, materialized as a list.
`get` writes the caller's destination directly; it does not provide the private
immutable-snapshot contract used for import. General `delete` may remove a tree
and must stay confined to explicit owned cleanup/retention paths.

For `publishAtomically`, the caller supplies an immutable local slice directory
and readable marker. Only immediate regular files are uploaded; subdirectories
are not recursively published. Data files precede the marker in a random
`<final>.tmp-<UUID>` sibling. All uploaded file sizes are checked before the
no-replace server rename to the final location; the final marker is read again.
A marker-less existing final directory is deleted as partial owned output.
Failure cleanup is best-effort, with cleanup exceptions suppressed on the primary
runtime failure. Lost connections/crashes can leave partial remote state.

A pre-existing equal marker is success without another upload or data-file
verification. A differing marker fails and preserves the committed destination.
The transport compares marker text and sizes, not freshly downloaded payload
hashes. Consumer validation and immutable slice identity belong to the
[export/publish protocol](../../../../../../../../../../../docs/dev/artifact-export.md) and application. The atomicity
boundary is the server directory rename plus consumer marker convention, subject
to target qualification; there is no multi-publisher transaction or locking here.

## Managed import ownership

Complete listing stabilizes a candidate by leaf, size, last-write time and server
file ID for the quiet period. Samples are process-local and restart their quiet
period after service restart. Directories and reparse points are not candidates;
file extension is not a contract selector. Detection is serialized per source.

The adapter requires pre-existing `.ioc-managed-import/{processing,terminal,quarantine,probe}`
below the configured inbox. The positive probe creates a unique empty regular
object in `probe`, renames it through `processing` to `terminal`, and deletes it.
It neither creates directories nor audits producer ACLs, and does not exercise
every quarantine/read operation. [The operator guide](../../../../../../../../../../../docs/guides/dataframe-import.md)
owns the complete two-identity matrix and same-filesystem rename requirement.

After application ledger reservation, claim first renames the producer file into
`processing/<delivery-token>.csv` without replacement. A producer/claimed collision
fails closed. A reserved delivery can adopt its matching claimed object after
reconnect without claiming a later producer file. The candidate evidence must
still match, including server file ID.

Materialization checks the configured byte limit, downloads with write sharing
excluded, then compares downloaded size and remote size/time/file-ID evidence.
These metadata checks rely on the private namespace and server semantics; they
are not cryptographic proof against a hostile producer/server. Local fsync and
atomic snapshot publication are delegated to the injected `ImportSnapshotStore`.
A failed materialization leaves remote ownership available for recovery and does
not authorize canonical writes.

Application orders remote disposition after its local terminal source/report
unit. `REJECTED` goes to `quarantine`; other terminal outcomes go to `terminal`.
Disposition rejects conflicting objects and accepts an already-moved destination.
Retention checks for contradictory processing/other-outcome evidence and deletes
only the exact expected regular terminal object; absence is idempotent success.
It never recursively deletes a managed namespace. Canonical commit receipts,
retry order and local terminal retention belong to application/storage.

## Notification lifecycle

Each watch opens a dedicated client/session/share/directory outside the pool and
watches one directory without recursive subtree notifications. Callbacks execute
on its daemon worker: keep them short and do not call blocking watch close from
that worker. A callback asks for ordinary detection, never lists/downloads itself.

Nonempty success and `STATUS_NOTIFY_ENUM_DIR` overflow signal a full re-detect;
empty success only re-arms. Error header status becomes watch failure and triggers
capped-backoff reconnect. Session lease renewal re-establishes the watch and
reports `established`; the sync coordinator uses that as a detection trigger.
Import's bridge forwards only `signal` to its source consumer; its independent
periodic listing recovers startup/reconnect gaps. Neither path disables polling.

Closing cancels a pending future, closes handles, interrupts the worker and joins
it with a timeout, reporting failure if it remains alive. Handle close is still
an SMBJ operation; the join timeout is not a universal wall-clock guarantee for
all server/SDK shutdown behavior. Keep live qualification separate from fake-backed
cancellation tests. See [coordination internals](../../../../../../../../../../../docs/dev/event-coordination.md) and
[ADR 0013](../../../../../../../../../../../docs/ADR/0013-event-driven-coordination.md) for the hint/backstop design.
