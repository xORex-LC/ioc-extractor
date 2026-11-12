# JDBC storage tests

## Purpose and structure

Local conformance for the [production package](../../../../../../../../main/java/com/iocextractor/adapter/out/store/jdbc/README.md).
Four `*Test` suites run in Surefire. Twenty-eight `*IT` suites are Failsafe-discovered:
27 carry `@IntegrationTest`; the reference-load shell carries `@ExternalTest` /
`@SlowTest` and requires `ioc.import.load.enabled=true`. Tests create old/current
schemas and rows inline in temporary local databases; no test-resource tree,
provisioned database server or remote credentials are required by normal suites.

| Suite | Observable contracts |
|---|---|
| [SqlitePragmaPolicyTest.java](SqlitePragmaPolicyTest.java) | Correctness floors and tuning presets |
| [SqlTrustBoundaryTest.java](SqlTrustBoundaryTest.java) | Reject SQL-shaped artifact/column/type input before schema generation |
| [JdbcStatementScopeTest.java](JdbcStatementScopeTest.java) | Bounded thread-confined statement leases, reset, eviction and close ownership |
| [JdbcWriterAdmissionTest.java](JdbcWriterAdmissionTest.java) | Operation ordering/aging, non-preemption, interruption and telemetry |
| [SqliteDataSourceFactoryIT.java](SqliteDataSourceFactoryIT.java) | Physical connection PRAGMAs and pooled SQLite configuration |
| [SqliteUserVersionSchemaMigratorIT.java](SqliteUserVersionSchemaMigratorIT.java) | Catalog upgrades/current skip, SQL rollback and downgrade rejection |
| [DataframeSchemaReconcilerIT.java](DataframeSchemaReconcilerIT.java) | Additive configured schemas and destructive/type drift rejection |
| [JdbcStorageHealthProbeIT.java](JdbcStorageHealthProbeIT.java) | Healthy and degraded local PRAGMA/quick-check observations |
| [JdbcArtifactIdentityStoreIT.java](JdbcArtifactIdentityStoreIT.java) | Identity backfill/collision guards and inherited CanonicalIdentityStore TCK |
| [JdbcArtifactRepositoriesIT.java](JdbcArtifactRepositoriesIT.java) | Canonical repository, provenance, revision and projection-generation behavior |
| [JdbcCanonicalMatchAndMutationIT.java](JdbcCanonicalMatchAndMutationIT.java) | Indexed complete-key matching and shared mutation-session outcomes |
| [JdbcCanonicalLifecycleWriterIT.java](JdbcCanonicalLifecycleWriterIT.java) | Ordinary lifecycle confirmation, safe-time ordering, receipt and atomic rollback |
| [JdbcCanonicalRecordLifecycleContractIT.java](JdbcCanonicalRecordLifecycleContractIT.java) | Inherited CanonicalRecordLifecycle TCK against JDBC |
| [JdbcLifecycleStorageFoundationIT.java](JdbcLifecycleStorageFoundationIT.java) | Lifecycle schema/control/allocators, activation and receipt foundations |
| [JdbcLifecycleRuntimeIT.java](JdbcLifecycleRuntimeIT.java) | Safe clock, expiry/reconciliation/status and history/receipt retention |
| [JdbcObservationRegistrationStoreIT.java](JdbcObservationRegistrationStoreIT.java) | Dataframe registration order/namespace and cross-store admission references |
| [JdbcDocumentProcessingPolicyGateIT.java](JdbcDocumentProcessingPolicyGateIT.java) | Policy pin/replacement only after earlier admissions drain |
| [JdbcDocumentPreparationWorkspaceIT.java](JdbcDocumentPreparationWorkspaceIT.java) | Private ownership, quotas, row codec, reducer, pins, seals and repeatable cursors |
| [JdbcDocumentSourceWorkspaceIT.java](JdbcDocumentSourceWorkspaceIT.java) | Paged UTF-16/refang text and indexed extraction/overlap/marker scratch |
| [JdbcImportWorkspaceIT.java](JdbcImportWorkspaceIT.java) | Streaming stage, tri-state rows, duplicate handling, capacity, seal and verification |
| [JdbcCanonicalImportWriterContractIT.java](JdbcCanonicalImportWriterContractIT.java) | Inherited import TCK plus merge/fan-out, slots, warnings, pre/post-commit and fatal-error recovery |
| [JdbcImportDeliveryLedgerContractIT.java](JdbcImportDeliveryLedgerContractIT.java) | Inherited delivery-ledger TCK plus durable sequence/state-version coordination |
| [JdbcManagedImportLoadProfileIT.java](JdbcManagedImportLoadProfileIT.java) | Property-gated reference load; skipped in the ordinary gate |
| [JdbcExportSlotRegistryIT.java](JdbcExportSlotRegistryIT.java) | Sparse slots/ranges, survivor/preference conflicts and bounded SQL planning |
| [JdbcSnapshotSliceReaderIT.java](JdbcSnapshotSliceReaderIT.java) | Profile snapshot/coverage, generation races, active slots and concurrency |
| [JdbcExportRunLedgerContractIT.java](JdbcExportRunLedgerContractIT.java) | Inherited export-ledger TCK |
| [JdbcExportRunLedgerIT.java](JdbcExportRunLedgerIT.java) | Per-profile active-run CAS, terminal progress and lookup behavior |
| [JdbcIngestionLedgerIT.java](JdbcIngestionLedgerIT.java) | Inherited ingestion-ledger TCK and JDBC persistence |
| [JdbcRunLedgerIT.java](JdbcRunLedgerIT.java) | Per-source ingest-run saga checkpoints and persisted transitions |
| [JdbcRemoteFetchLedgerIT.java](JdbcRemoteFetchLedgerIT.java) | Current fetch identity/persistence and result transitions |
| [JdbcPublishLedgerIT.java](JdbcPublishLedgerIT.java) | Slice/target CAS, attempts, reconcile and terminal retention evidence |
| [LegacyLedgerImporterIT.java](LegacyLedgerImporterIT.java) | Legacy properties import, checksum/status tracking and replay behavior |

## Evidence and limits

The inherited TCK bindings exercise the same inward port contracts against this
implementation; added local assertions cover SQLite/schema/resource behavior.
Fault-injection fixtures check canonical promotion before/after commit and Error
paths, including pooled-connection TEMP cleanup and preservation of same-named
main tables. Workspace tests cover owned/foreign/live directories, sidecars,
corrupt bounded row bytes, admission release, cursor lifetimes and source/text
processing. Concurrent fixtures use timed coordination and close owned resources.

These tests qualify local file-backed SQLite with the configured dependency
baseline. They do not qualify another mount/OS/native binary, SMB storage,
power-loss durability, an entire deployment or total process memory. Some normal
tests use large deterministic datasets; that does not turn an ordinary gate into
the separate reference-load profile or close ING-15/OUT-4 acceptance.

Migration tests currently demonstrate SQL-exception rollback, not universal
unchecked/Error atomicity. [OPS-15](../../../../../../../../../../../docs/KNOWN-ISSUES.md#ops-15)
records the reproduced partial-DDL defect. NULL import-status and fetch-namespace
issues also remain open. A green TCK/gate is evidence for its exercised scope,
not proof that every ledger field or failure window is correct.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-store-jdbc
make test-integration-module MODULE=adapters/adapter-store-jdbc
```

Full reactor acceptance uses `make verify`, including bootstrap composition.
The load shell normally skips; an offline skip is not load qualification.
`make dataframe-import-load` is a separate intentionally expensive facade with
explicit profile/size selection; consult [tools](../../../../../../../../../../../tools/README.md) before
running it. No load run is implied by a regular verification command.

[TESTING.md](../../../../../../../../../../../docs/TESTING.md) owns test discovery, evidence and worker rules;
[storage](../../../../../../../../../../../docs/dev/storage.md),
[managed import](../../../../../../../../../../../docs/dev/dataframe-import.md) and
[native capacity](../../../../../../../../../../../docs/dev/native-capacity.md) own cross-module semantics
and the limits of historical measurements. Migration references are separate:
[dataframe](../../../../../../../../main/resources/com/iocextractor/adapter/out/store/jdbc/dataframe/README.md) and [service](../../../../../../../../main/resources/com/iocextractor/adapter/out/store/jdbc/service/README.md).
