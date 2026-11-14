# core/ioc-application

## Purpose and boundary

Framework-free application layer for document extraction, canonical artifact
lifecycle, ordered observations, managed CSV import, immutable export and remote
fetch/publish coordination. The reactor artifact is `com.iocextractor:ioc-application`;
it is neither a runnable application nor an admitted standalone library.

Driving adapters invoke use-case ports; driven ports describe required storage,
parsing, transport and reporting behavior. Bootstrap supplies implementations,
validated policy and runtime scheduling. Model invariants and orchestration live
here; SQL, Spring, Camel/CSV/Tika/SMBJ integration and concrete encoders stay outward.
Framework-free does not imply pure computation: services invoke side-effecting
ports, and the current sync package also owns JDK NIO local landing/marker IO.

## Structure and reading order

| Directory | Responsibility |
|---|---|
| [Application package](src/main/java/com/iocextractor/application/README.md) | Capability map and local package ownership |
| [Driving ports](src/main/java/com/iocextractor/application/port/in/README.md) | Commands/results and supported entry operations |
| [Driven ports](src/main/java/com/iocextractor/application/port/out/README.md) | Adapter contracts, callback/resource lifetimes and atomicity boundaries |
| [Extraction service](src/main/java/com/iocextractor/application/service/README.md) | Workspace-owned extraction and split prepare/promote |
| [Pipeline](src/main/java/com/iocextractor/application/pipeline/README.md) | Application metadata/payloads and streamed versus batch stages |
| [Artifacts](src/main/java/com/iocextractor/application/artifact/README.md) | Identity, preparation, canonical writes and lifecycle |
| [Managed import](src/main/java/com/iocextractor/application/dataframeimport/README.md) | Contract compilation, staging, global-head processing, receipts and recovery |
| [Tests](src/test/java/com/iocextractor/application/README.md) | Local fake-backed contracts and downstream integration boundaries |

There are no module-local resource trees. Follow a capability's package README
for its important types and port map, rather than treating a root summary as the
complete contract. Existing compatibility surfaces remain documented until their
retirement is separately accepted.

## Dependencies

The POM directly selects domain, pure `ioc-processing` and platform ETL,
IOC diagnostics, diagnostics API, control-event and keyed-concurrency contracts.
Platform errors arrive transitively. Parent management owns versions/Java 21;
parent test dependencies supply JUnit/AssertJ. This module does not depend on the
application TCK, adapters or bootstrap.

Module Enforcer rules reject Spring, CSV, SMBJ, JDBC/TX, Hikari and SQLite
integration dependencies transitively; architecture tests additionally enforce
inward imports and port/package rules. A framework-free class may carry JDK
`Path`/`Clock` values. Do not describe that as a runtime SDK or storage
implementation guarantee. The POM/dependency tree owns exact coordinates.

## Key execution contracts

The factory-built document path is streamed source read/refang/extraction/
attribution, required selected-plan preparation, failure-policy checkpoint,
then canonical write and projection. Prepared workspaces are private precommit
evidence. Only promotion reserves IDs or changes canonical rows. Dry-run still
reads/prepares scratch; it skips canonical writing/ID reservation at this boundary.

Document commit proceeds artifact by artifact, with projection after each commit;
it is not one cross-artifact transaction. A later failure can follow earlier
canonical effects and requires forward recovery. Managed import has a different
port: one integrity-pinned delivery is promoted atomically across affected
artifacts, with a dataframe commit receipt as idempotency authority.

Canonical IDs, lifecycle IDs, content keys, delivery observation IDs, dataframe
observation ranks, import FIFO sequence and reusable export slots are distinct.
A repeated file is a new delivery observation even when its content key matches.
Expiry affects active membership/projection generation; it is not automatically
a new-row revision or a new immutable slice. Events are hints; durable ledgers,
receipts, revisions/deadlines/generations and periodic reconcile own recovery.

Services depend on configured resource bounds supplied by workspace/transport
implementations. Repeatable cursors and bounded diagnostic detail do not prove
whole-process memory or full-service capacity. Current qualified limits and
open issues remain in the linked capability docs and registry.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=core/ioc-application
```

Local suites run in Surefire with synthetic/fake port implementations. JDBC TCK,
actual parser/transport/Camel and composed runtime scenarios live downstream;
full reactor acceptance uses `make verify`. A green local suite is not live SMB,
installation, reference-load or complete compatibility qualification.

See [processing](../../docs/dev/processing.md), [ingestion](../../docs/dev/ingestion.md),
[lifecycle](../../docs/dev/canonical-record-lifecycle.md), [import](../../docs/dev/dataframe-import.md),
[export](../../docs/dev/artifact-export.md), [sync](../../docs/dev/sync.md),
[test policy](../../docs/TESTING.md) and [known issues](../../docs/KNOWN-ISSUES.md).
