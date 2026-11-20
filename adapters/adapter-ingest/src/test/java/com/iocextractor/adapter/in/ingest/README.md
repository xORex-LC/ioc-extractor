# Ingestion and local managed-import tests

## Purpose and structure

Behavioral checks for the [production package](../../../../../../../main/java/com/iocextractor/adapter/in/ingest/README.md).
Two Surefire suites cover properties/logging; nine Failsafe suites exercise
real local files and adapter coordination. Failsafe suites carry
`@IntegrationTest`. Inputs and fault injectors are inline, with private
`@TempDir` roots; there is no fixture resource directory.

| Suite | Observable contracts |
|---|---|
| [IngestAdapterPropertiesTest.java](IngestAdapterPropertiesTest.java) | Pattern lists snapshot caller data while preserving nulls for collect-all validation |
| [LoggingIngestionStartupObserverTest.java](LoggingIngestionStartupObserverTest.java) | Typed start/completion counts, failure classification, safe lifecycle fields and already-delivered recovery diagnostic handling |
| [IngestFileListFilterIT.java](IngestFileListFilterIT.java) | Include/exclude, modification-age boundary, non-files, discard retry and real Spring Integration WatchService quiet-period retry |
| [FileSourceHasherIT.java](FileSourceHasherIT.java) | Independent expected lowercase SHA-256 and separate metadata-fingerprint behavior |
| [FileSystemSourceLifecycleIT.java](FileSystemSourceLifecycleIT.java) | Claim/archive/error sidecar, NOFOLLOW evidence, existing-target rejection, unsupported atomic move, bounded seal, open producer descriptor and admission restart/terminal reconciliation |
| [FileIngestionLedgerIT.java](FileIngestionLedgerIT.java) | File persistence and inherited `IngestionLedgerContractTest` state-transition contracts |
| [FileSourceMessageHandlerIT.java](FileSourceMessageHandlerIT.java) | Durable dispatch order, coalescing, saturation, journal rediscovery, identity-preserving retry, blocked pre-hash failure, workspace window, cleanup failure and fatal/graceful shutdown recovery |
| [LocalManagedImportSourceLifecycleIT.java](LocalManagedImportSourceLifecycleIT.java) | Complete listing/stability, loss/duplication of hints, claim replay, changed/oversized candidates, link/path/ownership rejection, copy failures and terminal-before-release |
| [LocalFilesystemImportSnapshotStoreIT.java](LocalFilesystemImportSnapshotStoreIT.java) | Existing-byte adoption, legacy/current reference prefixes, byte cap and partial cleanup |
| [LocalImportChangeSignalSourceIT.java](LocalImportChangeSignalSourceIT.java) | Real WatchService hints, callback failure containment, close during notification, duplicate paths and registration failure |
| [LocalImportTerminalStoreIT.java](LocalImportTerminalStoreIT.java) | Complete escaped source/report units, disclosure boundary, warnings, separate replay, ambiguous/missing/oversized evidence, archive and exact purge |

`FileSourceMessageHandlerIT` retains its historical class name but tests
`DurableDocumentDispatcher`, not a current production class named
`FileSourceMessageHandler`. It uses real files and a file admission journal with
controlled application preparation/promotion implementations. Latches/timeouts
make ordering, release and restart outcomes observable. These fixtures do not
execute the complete extraction-to-JDBC transaction.

## Evidence boundaries

Temporary local files qualify this filesystem and its supported atomic
operations. Injected move/copy failures establish adapter reaction; they do not
qualify another filesystem, mount layout, disk-exhaustion environment or SMB
server. The ordinary WatchService test proves quiet-period retry, not recovery
of all lost OS events. Managed-import listing tests establish a separate
reconciliation contract. Tests use synthetic document/CSV content without
external credentials or remote services.

Bootstrap recovery ordering, actual property binding, readiness, JDBC journals
and canonical transactions are checked in their owning modules. Whole-process
capacity and production deployment require separate evidence. Per-namespace
single-daemon ownership is a deployment assumption, not a cross-process fencing
guarantee demonstrated here. The sibling
[maintenance tests](../../out/maintenance/README.md) cover ordinary leaf retention.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-ingest
make test-integration-module MODULE=adapters/adapter-ingest
```

Full reactor acceptance uses `make verify`.
[TESTING.md](../../../../../../../../../../docs/TESTING.md) owns suite discovery, meaningful assertions
and async worker cleanup requirements. The
[ingestion capability](../../../../../../../../../../docs/dev/ingestion.md) and
[managed-import capability](../../../../../../../../../../docs/dev/dataframe-import.md) explain the
cross-module protocols without promoting local fixtures to deployment evidence.
