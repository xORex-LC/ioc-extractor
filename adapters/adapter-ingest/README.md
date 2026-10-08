# adapters/adapter-ingest

Inbound filesystem adapter for daemon document ingestion and local managed CSV
import. It translates stabilized candidates into inward application ports and
owns private file claim/seal/archive/dead-letter mechanics. It does not implement
IOC extraction rules or write canonical artifacts.

## Contents

| Component | Responsibility |
|---|---|
| `IngestFlowConfiguration` | Single detector, full listing/glob/stability filters and explicit startup |
| `DurableDocumentDispatcher` | Bounded durable job references, parallel preparation, ordered promotion, retry and shutdown |
| `OrderedDocumentAdmissionHandler` | Short token claim; worker seal/hash/link and terminal reconciliation |
| `FileDocumentAdmissionJournal` | Fsync-backed CAS admission/execution journal for file-ledger mode |
| `DocumentCompletionObserver` | Terminal structured logging, without execution authority |
| `FileSystemSourceLifecycle` | No-replace private source lifecycle and recoverable disposition |
| `StrictAtomicFileOwnership` | Shared fail-closed regular-file ownership primitives |
| `LocalManagedImportSourceLifecycle` | Local structured-import claim and disposition |
| `LocalFilesystemImportSnapshotStore` | Protected immutable snapshots shared by local, SMB and replay through a port |
| `LocalImportChangeSignalSource` | Optional WatchService doorbell; filenames are not authority |
| `LocalImportTerminalStore` | Atomic protected source/report publication and retention |

## Dependencies and layer rule

Depends on application ports, platform errors/diagnostics/observability/concurrency
and Spring Integration file support. It does not depend on bootstrap, JDBC or
concrete CSV implementation. Cross-module execution is explained in
[ingestion](../../docs/dev/ingestion.md) and
[ADR-0038](../../docs/ADR/0038-durable-bounded-document-execution.md).

## Runtime contracts

The bootstrap startup barrier recovers durable work and lifecycle admission before
starting the dispatcher or file flow. Detector concurrency remains one. Every
daemon document receives durable occurrence/order before atomic claim. Hashing
and snapshot/preparation run on bounded workers, outside poller and canonical
writer ownership. Only the oldest unresolved document promotes; source-key
exclusion applies at that boundary.

Count/source-byte saturation leaves input discoverable in the inbox. Claimed jobs
survive rejected/lost hints because periodic journal discovery is authoritative.
The preparation window holds references and cannot exceed the global workspace
lease capacity, with shared quotas limiting cache/memory/disk independently.
Attempts and backoff survive restart. Exhausted
pre-hash failure retains a blocked token and rank; it never uses path/mtime as a
content identity. A verified-key failure can use the normal terminal rejection.
The pre-reservation disposition seam ING-13 remains open.

Private seal publishes a new inode with fsync/atomic move before SHA-256. The
producer's old descriptor cannot change processed bytes. File and JDBC journals
share one application state machine. Terminal source/registration CAS is
monotonic and recoverable. Startup refuses unresolved legacy rankless work when
registered-field policy needs an order. One daemon per namespace is supported;
cross-process ingestion fencing is not claimed.

Shutdown stops dispatch, joins owned workers with a bounded grace period and
closes remaining unpromoted handles while preserving recoverable sources. A
non-terminating worker fails shutdown explicitly. Diagnostics retain detailed
causes; health snapshots expose bounded metadata and safe failure types.
Fatal worker errors stop intake and scheduling, release ready preparations and
retain durable admissions for restart. Shutdown attempts every owned release
even after a fatal cleanup failure; cleanup I/O runs outside the dispatch monitor.

Terminal logs carry supported completion/diagnostic counts; duplicate receipt
replay has a disposition field without fabricated extraction completion. Logging
failure cannot repeat a committed document. Control events accelerate reconcile
and never become processing authority.
