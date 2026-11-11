# Outbound CSV tests

## Purpose and structure

Contracts for [CSV preparation and output](../../../../../../../../main/java/com/iocextractor/adapter/out/sink/csv/README.md).
Synthetic rows, inline expected CSV bytes and temporary filesystem trees keep
these suites independent of operator data and installed services.

| Suite | Lifecycle / contract |
|---|---|
| [CsvArtifactPreparerTest.java](CsvArtifactPreparerTest.java) | Surefire: routed/default views, expected mapping diagnostics, deferred IDs, provenance, candidate preservation and TRACE behavior |
| [TypedMappingFailurePolicyTest.java](TypedMappingFailurePolicyTest.java) | Surefire: application collect/fail-fast behavior around real preparation; no writes/ID reservation before rejection; unexpected defects propagate |
| [ConfigurationValueImmutabilityTest.java](ConfigurationValueImmutabilityTest.java) | Surefire: defensive configuration snapshots |
| [CsvRecordFormatTest.java](CsvRecordFormatTest.java) | Surefire: independent expected output for endings, quoting, nulls and strict NONE rejection |
| [CsvArtifactProjectionIT.java](CsvArtifactProjectionIT.java) | Failsafe: encoding-loss counts, strict encoding, atomic replacement/failure cleanup, stream failure and relative-path behavior |
| [CsvArtifactSliceWriterIT.java](CsvArtifactSliceWriterIT.java) | Failsafe: deterministic bytes/tree, callback sequence, durability ordering, corruption, marker recovery, atomic publication and streamed rows |
| [NioExportOperationGuardIT.java](NioExportOperationGuardIT.java) | Failsafe: exclusion between instances/threads, profile independence, lease release and idempotent close |
| [FileSystemSliceRetentionStoreIT.java](FileSystemSliceRetentionStoreIT.java) | Failsafe: verified directory deletion and rejection of incomplete/corrupt finals |
| [FileSystemCompletedSliceCatalogIT.java](FileSystemCompletedSliceCatalogIT.java) | Failsafe: deep versus names-only discovery, targeted lookup, invalid-child diagnostics and staging isolation |

## Fixtures and evidence boundaries

Keep explicit expected bytes/rows and failure injection separate from the
production formatter. Slice tests inject `SliceFileOperations` for ordering and
unsupported-move/durability failures. Their local manifest codecs encode
synthetic record text and retain a decode lookup; they do not test the production
Jackson JSON wire format. That codec belongs to its own adapter and cross-module
tests. Do not add a sibling adapter dependency just to reuse its implementation.

Projection generation ownership and cancellation are production constraints;
the local projection suite does not independently qualify their full application
scheduling/recovery boundary.

The writer's large streamed-row test exercises 50,000 callbacks without retaining
rows inside the writer. It is a bounded contract test, not a production capacity
benchmark. Local leases and temporary filesystem success do not establish
cross-host fencing, crash/power-loss durability on every filesystem, remote
publish or SQLite recovery. Failsafe names and composed integration annotations
must remain aligned; follow [TESTING.md](../../../../../../../../../../../docs/TESTING.md) for bounded
workers/waits and cleanup.

## Verification

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-csv
make test-integration-module MODULE=adapters/adapter-csv
```

Both commands include upstream dependencies. Full acceptance uses `make verify`.
See [artifact export](../../../../../../../../../../../docs/dev/artifact-export.md) and [CSV output format](../../../../../../../../../../../docs/dev/csv-output-format.md) for cross-module contracts.
