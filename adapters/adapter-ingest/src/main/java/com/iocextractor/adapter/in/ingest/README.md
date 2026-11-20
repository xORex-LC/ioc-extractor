# com.iocextractor.adapter.in.ingest

## Purpose and structure

Driving document ingestion plus local managed-import file access and protected
stores. Follow the [module dependency rules](../../../../../../../../README.md).
The application owns ordering, canonical policy and durable state transitions;
this package supplies scheduling and physical filesystem operations.

| File | Responsibility |
|---|---|
| [IngestFlowConfiguration.java](IngestFlowConfiguration.java) | Daemon-only Spring Integration beans; explicit-start single-detector flow |
| [IngestAdapterProperties.java](IngestAdapterProperties.java) | Adapter property projection and execution defaults; service preflight owns semantic validation |
| [IngestFileListFilter.java](IngestFileListFilter.java) | Filename globs and modification-age quiet period; discard callbacks for too-young candidates |
| [DurableDocumentDispatcher.java](DurableDocumentDispatcher.java) | Short admission, bounded preparation, oldest-first promotion, durable retry and worker shutdown |
| [OrderedDocumentAdmissionHandler.java](OrderedDocumentAdmissionHandler.java) | Reserve/order/claim bridge, worker seal/hash/link, terminal recovery and read-only inspection |
| [FileDocumentCandidateEvidenceReader.java](FileDocumentCandidateEvidenceReader.java) | NOFOLLOW regular-file metadata evidence before ownership |
| [FileSystemSourceLifecycle.java](FileSystemSourceLifecycle.java) | Private token claim, bounded seal, archive/failure sidecar and processing recovery |
| [StrictAtomicFileOwnership.java](StrictAtomicFileOwnership.java) | Package-private regular-file checks and strict atomic ownership move |
| [FileSourceHasher.java](FileSourceHasher.java) | Streaming SHA-256 content key; separate legacy metadata fingerprint helper |
| [FileDocumentAdmissionJournal.java](FileDocumentAdmissionJournal.java) | Fsync-backed versioned admission/execution records for file-ledger mode |
| [FileIngestionLedger.java](FileIngestionLedger.java) | Per-observation properties records and instance-local serialized ingestion transitions |
| [DocumentCompletionObserver.java](DocumentCompletionObserver.java) | Package-private terminal logs and diagnostic counts, without execution authority |
| [IngestionStartupObserver.java](IngestionStartupObserver.java) | Recovery-barrier observation contract |
| [LoggingIngestionStartupObserver.java](LoggingIngestionStartupObserver.java) | Typed recovery logs and boundary diagnostic delivery |
| [IngestionLifecycleState.java](IngestionLifecycleState.java) | Safely published immutable startup/health snapshots |
| [LocalImportSourceDefinition.java](LocalImportSourceDefinition.java) | Source ID and local inbox trust-boundary descriptor |
| [LocalManagedImportSourceLifecycle.java](LocalManagedImportSourceLifecycle.java) | Complete local listing, stability sampling, claim, snapshot and final claim release |
| [LocalFilesystemImportSnapshotStore.java](LocalFilesystemImportSnapshotStore.java) | Shared protected snapshot publication, inspection, reference resolution and exact purge |
| [LocalImportChangeSignalSource.java](LocalImportChangeSignalSource.java) | Optional source-level WatchService hints for managed import |
| [LocalImportTerminalStore.java](LocalImportTerminalStore.java) | Protected source/report publication, replay snapshot and whole-unit retention |
| [package-info.java](package-info.java) | Java package entry point |

## Document detection and execution

`iocIngestionFlow` is conditional on `ioc.runtime.mode=daemon` and has
`autoStartup(false)`. Bootstrap's
[CanonicalIntakeStartupCoordinator](../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/CanonicalIntakeStartupCoordinator.java)
recovers run saga, document admissions, ordinary sources, lifecycle admission
and managed imports before starting document dispatch and the flow.

Full polling is the default correctness path. The filter matches the basename,
then requires `mtime` to be old enough; it does not prove an unchanged size across
scans or a completed producer write. Use excluded temporary names followed by
atomic handoff after writing. The preliminary `File.isFile` filter is not the
NOFOLLOW ownership check. There is no AcceptOnce filter: unclaimed input stays
eligible after saturation.

Ordinary document WatchService scanning retries quiet-period rejects, but has
no periodic full-listing backstop for lost OS events. The dispatcher's one-second
journal scan recovers already admitted work, not inbox files missed by that
scanner. This differs from the managed-import doorbell below.

The Spring bean named `fileSourceMessageHandler` is a
`DurableDocumentDispatcher`. Its detector path reserves a new observation and
dataframe rank before token claim, then returns. Preparation workers seal/hash
and call `PrepareIngestionUseCase.prepare`; only the oldest unresolved admission
can call `PreparedIngestion.promote`. Content-key exclusion starts at promotion
so a newer ready duplicate cannot monopolize it while waiting for an older job.
The handler's `claim` is the short path; its convenience `admit` includes the
worker-side finish and must not be mistaken for metadata-only admission.

Pending-count, total-byte and per-source limits govern ownership. The effective
preparation window is the smaller of configured window and global workspace
capacity. Queues hold job/handle references; workspace admission independently
bounds preparation resources. These limits alone are not whole-process memory
qualification. Rejected/coalesced enqueue hints cannot erase durable admission.

## Document identity, recovery and failure

Each occurrence has an `ObservationId` and durable dataframe admission rank;
retry/restart preserves both. The source key is SHA-256 of sealed bytes.
Candidate path, size, mtime and optional file key are evidence, not content
identity; `FileSourceHasher.fingerprint` is not the dispatcher's content key.

Claim rejects symbolic links/non-regular files and an already present target,
then requires `ATOMIC_MOVE` with no copy or ordinary-move fallback. It assumes
controlled single-owner directories; it provides no cross-process fencing or
general race-proof no-replace primitive against arbitrary concurrent writers.
`sealClaim` copies within the admitted byte allowance to a new inode, forces it
and atomically publishes it before hashing. A producer's old file descriptor
cannot modify that sealed inode. Recovery reuses an existing seal and reconciles
claim/link progress without allocating another rank. Failure disposition moves
the source to `failed` and writes a separate `.error` sidecar; that sidecar is
not an atomic source/report pair.

The file admission journal compares the expected versioned record, forces a
temporary record, atomically replaces it and forces the directory. It retains
attempt/retry-after and terminal-registration progress. By contrast,
`FileIngestionLedger` serializes transitions per observation within one instance
and prefers atomic replacement, with ordinary-move fallback and no explicit
fsync. Legacy key-named records remain readable. Neither file store replaces
the daemon's JDBC run saga or canonical dataframe authority. Bootstrap selects
the store; the adapter's file-bean fallback is not the production default.

Retry/backoff is durable and does not sleep on the detector. Verified-key
exhaustion can reject a source. Exhausted pre-hash failure keeps the token/rank
blocked, stops newer promotion and makes capacity health DOWN; there is no
automatic clear/requeue API. Pre-reservation disposition (ING-13) and partial-run
resume (ING-11) remain [known limitations](../../../../../../../../../../docs/KNOWN-ISSUES.md).
Document commits can succeed for earlier artifacts before a later failure;
this adapter does not provide whole-document rollback.

Dispatcher close stops scheduling, joins owned coordinator/preparation/promotion
workers with bounded grace and then closes remaining handles outside its monitor.
Failure to terminate is explicit; a live worker's handle is retained rather than
closed underneath it. A fatal worker Error stops scheduling and releases ready
unpromoted handles; active promotion retains its own cleanup. Independently owned
releases are attempted even after another cleanup fails. Durable admissions
survive for restart; the failed dispatcher instance cannot restart itself.

Terminal logs preserve completion/diagnostic counts. Receipt replay without
extraction uses a duplicate/disposition field, not fabricated extraction status.
Observation failure cannot repeat a committed operation. Startup logs expose
safe failure types; detailed causes remain diagnostics. Health uses immutable
lifecycle snapshots and `inspectPending`, not the reconciliation-performing
`pending` operation. Events/logs/health are not completion authorities.

## Local managed import and protected storage

Local import uses complete direct-child listing and repeated unchanged
size/mtime/file-key samples over its quiet period. It rejects symlinks and
revalidates the detected token before claim. Inbox, processing, snapshot,
terminal and quarantine roots must be disjoint. Claim takes atomic ownership,
then a bounded copy validates metadata before/after snapshotting. The parser
receives an adapter-issued immutable reference rather than an inbox path.

`LocalFilesystemImportSnapshotStore` is shared through `ImportSnapshotStore`
by local, SMB and replay flows. It forces/atomically publishes `snapshot.csv`,
checks byte limit, regular-file status and SHA-256, and adopts existing snapshots
without invoking the writer again. `local-snapshot-v1:` and legacy
`smb-snapshot-v1:` references resolve under the private root. Resolution checks
reference shape/containment; it is not itself an integrity read or an arbitrary
path resolver. POSIX permissions restrict directories to the owner and published
files to owner-read; non-POSIX deployments rely on operator ACLs.

`LocalImportTerminalStore` stages `source.csv` and schema-v1 `report.json`, forces
both and publishes the directory atomically in terminal/quarantine. Reports carry
delivery/contract identity, counts and row issue/warning codes rather than input
values or filesystem paths. Local source disposition releases the processing
claim only after both terminal files exist. Local transport purge has no separate
terminal source remnant to remove. Replay creates a distinct bounded private
snapshot from one unambiguous retained unit. Archive moves the whole unit
atomically to a disjoint outcome-specific root; it has no ordinary-move fallback.
Retention eligibility and canonical idempotency remain application decisions.

`LocalImportChangeSignalSource` registers configured inboxes and sends only the
source ID on create/modify/overflow. Repeated/lost hints or a callback runtime
failure are handled by periodic complete listing. Close closes the WatchService
and interrupts its worker; it does not offer the dispatcher's bounded join API.

Bootstrap's [import runtime configuration](../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DataframeImportRuntimeConfiguration.java)
owns composition, delivery ledger and feature admission. See
[managed-import internals](../../../../../../../../../../docs/dev/dataframe-import.md) and the
[operator guide](../../../../../../../../../../docs/guides/dataframe-import.md) for the full protocol,
configuration and retention/replay limits.

## Verification

The [test package](../../../../../../../test/java/com/iocextractor/adapter/in/ingest/README.md) maps local filesystem, journal,
dispatcher and logging checks. [Ingestion internals](../../../../../../../../../../docs/dev/ingestion.md)
owns the cross-module recovery protocol; public Javadoc owns member guarantees.
The separate [maintenance package](../../out/maintenance/README.md) has weaker leaf-file
archive semantics and must not replace these protected stores.
