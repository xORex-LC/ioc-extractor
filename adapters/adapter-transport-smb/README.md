# adapters/adapter-transport-smb

## Purpose and boundary

Outbound SMB2/3 integration for remote sync and managed dataframe import. The
internal reactor artifact is `com.iocextractor:ioc-adapter-transport-smb`; it is
neither a runnable application nor a separately published library.

The module owns SMBJ clients, sessions, share handles, encryption negotiation,
file operations and optional change notifications. Application ports own fetch
and publish orchestration, delivery identity, retries, ledgers and reconciliation.
A notification only requests detection; it never establishes delivery or commit.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Application ports, SMBJ and test-support dependencies |
| [Production package](src/main/java/com/iocextractor/adapter/out/transport/smb/README.md) | Shared sessions, file transport, managed ownership, notifications and telemetry |
| [Test package](src/test/java/com/iocextractor/adapter/out/transport/smb/README.md) | Offline fake-backed contracts and opt-in live-server qualification |

Production and test sources each occupy one package. There are no module-local
resource trees. The production README maps the concrete types; the test README
owns fixture requirements and commands for external suites.

## Dependencies

Production directly depends on `ioc-application` and SMBJ. Application supplies
inward ports/models and transitively supplies domain/platform contracts.
`ioc-application-tck` is test-scoped for shared test annotations. The parent owns
versions and the Java 21 baseline; inspect the POM/dependency tree for the current
SMBJ runtime dependencies rather than maintaining another version list here.

SMBJ types remain local. Production has no Spring, bootstrap, metrics-framework
or sibling-adapter dependency. The shared local snapshot implementation is
injected through `ImportSnapshotStore`, without coupling to `adapter-ingest`.

## Entry points and contracts

[SyncConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SyncConfig.java)
constructs a lazy `SmbSessionPool`, `SmbFileTransport`, `SmbChangeNotifyWatcher`
and shared telemetry when sync or import is enabled. Import uses the same endpoint
catalog and pool through
[DataframeImportRuntimeConfiguration](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DataframeImportRuntimeConfiguration.java).
Bootstrap owns configuration binding, idle-close scheduling, metrics and health.

One pool serializes operations for each logical endpoint name; different names
have separate lanes. Watch workers use dedicated connections so long-lived
`CHANGE_NOTIFY` requests do not occupy a pooled file-operation lane. This is
process-local coordination, not fencing against another service or SMB client.

`REQUIRED` encryption restricts negotiation to SMB3 and checks effective session
encryption before connecting the share. `PREFERRED` allows unencrypted fallback;
`DISABLED` removes the client preference and does not forbid server-required
encryption. Custom TCP ports apply to both pooled operations and watches.
Connect, request and idle timeouts have distinct purposes, described in the
production README.

Publish uploads into a temporary sibling directory, writes the marker last,
checks remote sizes and renames to the final slice path. Matching committed
markers are idempotent success. Marker-less final directories are treated as
adapter-owned partial output and may be deleted recursively: dedicate the target
to the publisher. This protocol does not supply cross-process publication locks
or a fresh remote checksum audit on an idempotent retry.

Managed import claims producer files by server rename into an operator-provisioned
private namespace before downloading. It checks server identity/metadata around
materialization and delegates durable local snapshot publication to the snapshot
store. Its positive capability probe cannot certify producer denial or qualify
an arbitrary server's ACL/rename/share-mode behavior. Keep ordinary sync-fetch
sources read-only; managed import has a separate mutating ownership contract.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-transport-smb
make test-integration-module MODULE=adapters/adapter-transport-smb
```

Full reactor acceptance uses `make verify`. External suites are discovered but
skip unless enabled; a green offline gate is not live SMB qualification.
See [sync internals](../../docs/dev/sync.md),
[event coordination](../../docs/dev/event-coordination.md),
[remote-sync operations](../../docs/guides/remote-storage-sync.md),
[managed-import operations](../../docs/guides/dataframe-import.md) and
[OPS-8](../../docs/KNOWN-ISSUES.md#ops-8) for deployment requirements and open qualification.
