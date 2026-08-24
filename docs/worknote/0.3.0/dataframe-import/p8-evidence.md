---
title: "DATA-IMPORT-01 P8 evidence"
version: "0.3.0"
status: "Implemented with live SMB contract evidence"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P8 evidence

## 1. Evidence boundary

P8 removes the explicit SMB fail-closed branch from the managed-import
composition. Local and SMB sources now enter the same ledger-first admission,
global durable lane, staging, atomic promotion and finalization state machine.
The SMB adapter owns only transport-specific claim, materialization,
notification and disposition mechanics.

## 2. Ownership and immutable materialization

- Complete listing records leaf, size, last-write time and the server file ID.
  The stability sampler does not depend on a filename convention or a
  notification.
- After the service ledger reserves the occurrence and global sequence, claim
  validates the candidate and performs a server-side rename into
  `inbox/.ioc-managed-import/processing/<delivery-token>.csv`.
- The token is a SHA-256 of the durable delivery ID. Producer and processing
  objects existing together are a fail-closed collision; neither is replaced.
- A processing object left by a disconnect after rename is adopted only by the
  same delivery. Object substitution with equal size and timestamp is rejected
  by the changed server file ID.
- Materialization excludes concurrent write sharing, streams into a private
  `.part`, checks remote evidence before and after the stream, enforces the byte
  limit, hashes and fsyncs locally, and atomically publishes the immutable
  snapshot. A failed stream never publishes canonical input evidence.
- Terminal disposition is idempotent and collision-safe. `REJECTED` objects move
  to remote `quarantine`; successful/partial terminal objects move to
  `terminal`, after the protected local source/report unit is durable.

## 3. Sessions, concurrency and notifications

- `SmbSessionPool` extracts the existing endpoint lock, lazy session cache,
  reconnect invalidation and idle-close policy from `SmbFileTransport`.
  Ordinary sync and managed import share it; one endpoint is serialized while
  different endpoints remain independent.
- Long-poll `CHANGE_NOTIFY` retains dedicated watch sessions so it cannot occupy
  the ordinary endpoint operation lane.
- `SmbImportChangeSignalSource` depends on the transport-neutral watch port and
  maps every notification to a source ID only. The import scheduler performs
  the real complete listing. Watch failure is therefore recoverable through
  watcher reconnect plus periodic polling.
- Bootstrap builds the shared pool/watcher when either sync or dataframe import
  is enabled. SMB import can reference the common endpoint catalog without
  enabling ordinary sync.

## 4. Executable evidence

The default adapter reactor test ran `48` tests: `43` passed and the `5` live
SMB tests were deliberately skipped without `ioc.smb.contract=true`.

The approved `127.0.0.1/test-share` stand was exercised under the operator-made
`import` root with credentials supplied only through environment variables:

```text
SmbManagedImportContractTest       3 passed
SmbChangeNotifyContractTest        2 passed
total                              5 passed, 0 failed, 0 skipped
```

Each live scenario created a unique child directory named
`p8-managed-import-<scenario>-<uuid>` and removed only that child in `finally`.
The contract corpus proved:

- a real producer handle that denies delete sharing blocks claim, leaves the
  producer intact and succeeds after the handle closes;
- server rename precedes snapshot download and terminal disposition;
- a pre-existing processing destination preserves both remote objects and
  publishes no snapshot;
- a new SMB session adopts an already-renamed orphan, with stable file identity
  across sessions, then completes quarantine disposition;
- create/modify notifications reach the transport-neutral doorbell.

Unit fault injection additionally covers disconnect immediately after rename,
disconnect during download, same-metadata object substitution, mutation during
download, lost/disabled notifications, idempotent disposition and sync/import
session reuse.

The 21-project affected reactor passed after the complete production change:
`ioc-app` ran `276` tests and the adapter results above remained green. A
separate focused composition test then proved that enabled SMB import builds the
shared pool, transport and watcher while ordinary sync stays disabled, without
opening network I/O. The final full-reactor `make verify` is recorded on the
committed P8 HEAD so its freshness covers that last test plus both requested P7
and P8 slices.

## 5. Qualification boundary

This evidence qualifies the repository's approved live Samba stand and the
deterministic fake/fault corpus. It does not independently identify or qualify
every production NAS/Windows server family. Any additional supported server
family must run the same opt-in contract corpus during P9 packaging/release
qualification; unsupported notification behavior remains safe because polling
is never disabled.
