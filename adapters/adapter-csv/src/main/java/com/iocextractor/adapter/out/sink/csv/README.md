# com.iocextractor.adapter.out.sink.csv

## Purpose and boundary

Artifact preparation, CSV byte formatting and local projection/slice publication.
The package implements application ports; it does not access the service DB,
transition export ledgers or coordinate remote delivery. Follow the
[module dependency rules](../../../../../../../../../README.md).

## Structure

| Files | Responsibility |
|---|---|
| [CsvArtifactDefinition.java](CsvArtifactDefinition.java), [CsvArtifactPreparer.java](CsvArtifactPreparer.java) | Artifact policy, filtering and configured field preparation |
| [IdValueProvider.java](IdValueProvider.java) | `id` provider returning a deferred null placeholder |
| [CsvArtifactProjection.java](CsvArtifactProjection.java) | Canonical stream to mutable CSV, generation result and encoding diagnostics |
| [CsvRecordFormat.java](CsvRecordFormat.java) | Shared record endings, independent header/value quoting and strict NONE |
| [CsvIo.java](CsvIo.java), [CsvValueEncodingInspector.java](CsvValueEncodingInspector.java) | Charset-aware I/O and logical replacement counts |
| [CsvArtifactSliceWriter.java](CsvArtifactSliceWriter.java) | Stage, inspect/recover, publish and discard-staging operations |
| [CsvSliceMaterialization.java](CsvSliceMaterialization.java) | One synchronous row-callback session, file digests and manifest/marker writes |
| [SliceDirectoryLayout.java](SliceDirectoryLayout.java) | Derived safe staging/final paths |
| [SliceTreeVerifier.java](SliceTreeVerifier.java), [VerifiedSlice.java](VerifiedSlice.java) | Shared exact-membership/hash-chain verification and result |
| [SliceHashes.java](SliceHashes.java) | Streaming SHA-256 helpers |
| [SliceFileOperations.java](SliceFileOperations.java), [NioSliceFileOperations.java](NioSliceFileOperations.java) | Durability/move seam and Linux-oriented NIO implementation |
| [NioExportOperationGuard.java](NioExportOperationGuard.java) | Caller-held profile formation/recovery lease |
| [FileSystemSliceRetentionStore.java](FileSystemSliceRetentionStore.java) | Verified final-directory discovery and deletion |
| [FileSystemCompletedSliceCatalog.java](FileSystemCompletedSliceCatalog.java) | Read-only completed-slice worklist for publish |
| [InvalidSliceException.java](InvalidSliceException.java), [SliceWriteException.java](SliceWriteException.java) | Internal integrity and callback-I/O failure bridges |
| [package-info.java](package-info.java) | Package-level ownership rule |

## Preparation and identity

`CsvArtifactPreparer` filters already-classified views and delegates field mapping
to the configured mapper. It returns templates and diagnostics before the
application failure-policy checkpoint, without reserving IDs or writing data.
`prepareRouted` maps one selected branch with per-column views and occurrence
positions for managed fields. Gated TRACE reports routing decisions without
reclassifying each field.

`MappingValueException` identifies expected input-dependent provider/transform
failure; `ConfigurableRowMapper` adds column/component location in
`RowMappingException`, which the preparer converts to an element diagnostic.
The reason must be operator-safe; the preparer does not attach its exception
chain. Other runtime defects stop the run. Application policy decides whether
valid candidates can commit or the run must stop before any durable write.

The DSL supports type gates (`when-type`/`when-types`), named `when` predicates,
constants/providers and ordered transforms; it is not an expression evaluator.
Filters, providers, transforms and shared semantics belong to `ioc-processing`,
with registries/preflight composed in bootstrap. See
[processing](../../../../../../../../../../../docs/dev/processing.md).

`IdValueProvider` does not allocate identity. Prepared plans carry an application
ID sequence; canonical reservation and commit belong to application/storage.
Reusable immutable-export slots are a separate snapshot/export contract. The
formatter does not derive IDs from CSV row order or compact sparse slots.

## Shared byte policy and mutable projection

`CsvRecordFormat` uses the supplied `ExportFormat`: LF/CRLF and independent
ALL/MINIMAL/NONE header/value quoting. Defaults are semicolon, CRLF, ALL/ALL and
`NULL` for a missing value. MINIMAL still quotes literal text equal to the null
marker. NONE rejects cells containing syntax, literal null-marker collisions and
an ambiguous single empty field before appending that record. The full contract
is [CSV output format](../../../../../../../../../../../docs/dev/csv-output-format.md).

`CsvArtifactProjection` must be invoked through the shared application generation
owner. It reads canonical rows through `CanonicalArtifactStreamReader`, writes a
sibling temporary file and installs it with atomic replacement. It returns row
count, snapshot generation and outcome diagnostics. Application decides whether
the generation is current and delivers the diagnostics.

Replacement occurs per artifact, not across the artifact set. Cancellation is
checked per row and immediately before rename. Failures before installation
remove incomplete temporary output; cleanup errors are suppressed on the primary
failure. A relative leaf path uses the current directory; a filesystem root
without a filename is rejected. Atomic replacement has no non-atomic fallback.

Only ALL/ALL mutable projection permits charset replacement. The logical encoding
inspector counts affected header values, data values and rows and returns one
`SINK.CHARSET_UNMAPPABLE` diagnostic when loss occurs. MINIMAL/NONE projection uses
strict encoding, preventing a replacement byte from creating unquoted CSV syntax.
Immutable slice encoding always uses `REPORT` for malformed/unmappable content.

## Immutable slice protocol

`CsvArtifactSliceWriter.stage` requires a matching STARTED run/plan and unused
staging/final paths. It creates `root/.staging/<runId>/`; synchronous callbacks
from `SnapshotSliceReader` then drive one `CsvSliceMaterialization` on its owner
thread. The callback sequence and ordered artifact metadata are checked. Rows are
written and hashed as they arrive, without retaining the snapshot's row list.
Per-invocation state allows unrelated profiles to materialize concurrently under
the application reader budget; callers must hold the appropriate profile lease.

For each artifact, the header precedes rows in snapshot order. After closing the
file, the adapter forces it to disk. It then writes/forces `manifest.json`, writes
`_SUCCESS` last with the exact manifest-byte SHA-256 plus LF, and forces staging
and its parent. Creating a nested export root also persists each newly linked
directory entry. Format/charset are validated before opening an artifact file.

`inspect` verifies run/profile/plan identity, safe physical files, exact directory
membership, every data hash and the marker-to-manifest hash. Presence of a marker
alone is insufficient. Staging with a valid manifest/data tree but no marker is
RECOVERABLE: `recoverStaging` adds the marker without rereading canonical data.
Missing/partial/corrupt, staged, available and conflicting paths remain distinct;
simultaneous staging/final paths are CONFLICT.

`makeAvailable` moves the staged directory to `root/<profile>/<sliceName>/` using
one `ATOMIC_MOVE` and forces the affected directories. Unsupported atomic move
fails with `EXPORT.ATOMIC_PUBLISH_UNSUPPORTED`, leaving staging for recovery;
there is no copy or ordinary-rename fallback. An already available valid slice
is returned idempotently. `discardStaging` targets only the derived staging path.

Manifest serialization is injected through `SliceManifestCodec`; this package
knows no Jackson implementation. Integrity failures use `EXPORT.MANIFEST_INVALID`,
write/durability failures use `EXPORT.SLICE_WRITE_FAILED`. Ledger recovery and
state transitions remain application-owned. A valid hash-chain proves byte
integrity, not source authentication or canonical-data authority.

## Leases, retention and publish discovery

`NioExportOperationGuard` uses nonblocking local file locks keyed by profile in
one configured root. The caller closes the lease; busy acquisition fails rather
than waiting. Different profiles can proceed independently. This is local
filesystem-backed exclusion, not a distributed ownership/fencing protocol.
NIO file/directory forcing is Linux-oriented; temporary-directory tests do not
qualify arbitrary network mounts or other deployment filesystems.

Retention scans only immediate final children of `root/<profile>/`, outside
`.staging`. Corrupt/incomplete finals fail the sweep and remain ineligible.
Deletion rechecks the descriptor and integrity chain, then removes the directory
tree. Directory-as-unit selection does not make recursive deletion transactional.
The protocol assumes a controlled export root, not arbitrary concurrent writers.

`FileSystemCompletedSliceCatalog` deliberately has different failure semantics:
invalid children are diagnosed as `SYNC.LOCAL_SLICE_INVALID` and skipped by list
operations. `listCompletedSliceNames` only checks physical directories/markers;
it does not rehash payloads. `listCompleted` and targeted `find` perform deep
verification; corrupt targeted lookup throws, while a missing target is empty.
This lets reconcile anti-join known names before verifying new candidates, without
turning marker-only candidates into trusted completed slices.

## Verification and related docs

The [test package](../../../../../../../../test/java/com/iocextractor/adapter/out/sink/csv/README.md) maps byte/protocol and failure-path
contracts. Cross-module lifecycle, identity, snapshot and delivery rules belong to
[artifact export](../../../../../../../../../../../docs/dev/artifact-export.md) and [storage](../../../../../../../../../../../docs/dev/storage.md).
