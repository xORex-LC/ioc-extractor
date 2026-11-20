# adapters/adapter-csv

## Purpose and boundary

CSV integration-family adapter, `com.iocextractor:ioc-adapter-csv`. It implements
application ports for strict import parsing, artifact preparation, mutable
canonical projections and immutable local export slices. Commons CSV and
filesystem mechanics remain here; application owns canonical commits, failure
policy, projection generations, export-ledger transitions and delivery sagas.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Module dependencies; versions managed by the parent |
| [Inbound package](src/main/java/com/iocextractor/adapter/in/csv/README.md) | Strict decoding, exact headers, synchronous record streaming and import registries |
| [Outbound package](src/main/java/com/iocextractor/adapter/out/sink/csv/README.md) | Preparation, CSV byte policy, projection replacement, slice publication/verification and filesystem leases |
| [Inbound tests](src/test/java/com/iocextractor/adapter/in/csv/README.md) | Input limits, parser behavior and whole-cell validation |
| [Outbound tests](src/test/java/com/iocextractor/adapter/out/sink/csv/README.md) | Mapping/policy, deterministic bytes, filesystems and recovery |

The two production packages have matching test packages. Synthetic inputs and
filesystem trees are constructed inline or under temporary directories; there is
no module-local resource-fixture tree.

## Dependencies

Production dependencies are `ioc-application`, `ioc-processing`, `ioc-domain`,
platform errors, diagnostics API/catalog and observability, SLF4J API and Commons
CSV. `ioc-application-tck` is test-scoped; shared test dependencies come from the
parent. Commons CSV's transitive dependencies belong to the resolved Maven graph;
there is no direct Commons IO declaration in this POM.

Do not import JDBC, SMB, bootstrap or sibling adapters. Bootstrap composes these
implementations with storage and the manifest codec. JSON passes through the
application `SliceManifestCodec` port, so this module has no compile dependency
on the Jackson manifest adapter. Framework-free core modules must not import
Commons CSV or these filesystem implementations.

## Entry points and contracts

| Path | Contract / authority |
|---|---|
| Inbound CSV | Reads a caller-resolved pinned snapshot; recognition/mapping contracts, delivery ownership and promotion remain in application |
| Artifact preparation | Filters/maps already-classified views before the failure-policy checkpoint; carries deferred canonical ID allocation, not durable writes |
| Mutable projection | Streams canonical truth to one derived CSV and reports the snapshot generation; invocation must pass through the shared application generation owner |
| Immutable export | Streams a profile snapshot into staging, verifies manifest/data/marker bytes and publishes one directory atomically under a caller-held profile lease |

Mutable projections are replaced per artifact, not as an atomic multi-artifact
set. Immutable slices have a separate identity and publication protocol.
Canonical identity and reusable immutable-export slots belong to application /
storage; the CSV formatter does not assign either identity or change TTL.

Processing plans and processed-import input/output bindings are composed in
bootstrap using the router adapter. This module supplies CSV parsing and field
mapping, without inferring processing plans from provider names. Providers,
transforms and predicates live in `ioc-processing`; semantic reuse/classification
is not repeated per mapped column.

See [processing](../../docs/dev/processing.md), [import internals](../../docs/dev/dataframe-import.md), [artifact export](../../docs/dev/artifact-export.md) and [CSV output format](../../docs/dev/csv-output-format.md).
Output-format settings and import-contract dialects are separate policies.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-csv
make test-integration-module MODULE=adapters/adapter-csv
```

The first command covers Surefire; the second covers deterministic Failsafe
filesystem contracts and upstream modules. `make verify` is the reactor gate.
Test-package READMEs describe what local codecs, temporary trees and injected
failures prove. They do not replace deployment-filesystem qualification or
cross-adapter end-to-end tests. General policy is [TESTING.md](../../docs/TESTING.md).
