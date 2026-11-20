# adapters/adapter-ingest

## Purpose and boundary

Filesystem integration for daemon document ingestion, local managed CSV import
and ordinary file retention. The Maven artifact is
`com.iocextractor:ioc-adapter-ingest`, an internal service JAR. The runnable
application and composition root live in `bootstrap/ioc-app`.

This module detects and owns files, schedules bounded document preparation and
ordered promotion through application ports, and implements local file storage
mechanics. IOC extraction, CSV parsing, canonical mutation, admission-order
allocation and retention policy belong to their inward application owners or
other adapters. Neither a filesystem event nor a successful rename establishes
a canonical commit.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Reactor identity and integration dependencies |
| [Inbound production package](src/main/java/com/iocextractor/adapter/in/ingest/README.md) | Document detector/dispatcher, file ledgers, source lifecycle and local import stores |
| [Outbound maintenance package](src/main/java/com/iocextractor/adapter/out/maintenance/README.md) | Leaf-file listing, deletion and archival behind `RetentionStore` |
| [Inbound tests](src/test/java/com/iocextractor/adapter/in/ingest/README.md) | Properties/logging contracts, real private files, durable dispatch and managed import fixtures |
| [Maintenance tests](src/test/java/com/iocextractor/adapter/out/maintenance/README.md) | Recursive file units and archive-relative paths |

Both production packages have matching test packages. Test documents and CSV
bytes are created inline in temporary directories; there is no module-local
test-resource tree.

## Dependencies

Direct production dependencies are the application ports, platform
errors/diagnostics/observability/concurrency, Spring Boot Integration starter,
Spring Integration File and SLF4J API. Versions come from the parent; Java 21 is
the project baseline. The application TCK is test-scoped.

There is no direct dependency on bootstrap, JDBC, Tika, concrete CSV parsing or
SMB transport. Keep Spring Integration and filesystem mechanics here; inward
application/domain code must not import this adapter. Shared local snapshot and
terminal stores can serve SMB-origin deliveries through application ports
without depending on the SMB adapter.

## Entry points and contracts

`IngestFlowConfiguration` enables the document flow only in daemon mode and
leaves it stopped until bootstrap recovery opens intake. One detector performs
metadata checks, durable reservation/order and atomic token claim. Preparation
workers seal/hash/prepare privately; one promotion worker processes the oldest
unresolved document. Count/byte saturation leaves unclaimed input discoverable;
the durable journal and periodic scan recover admitted work after lost hints.

The preparation window is capped by global workspace capacity. Attempts and
backoff survive restart. Exhausted pre-hash failure retains its owned token and
rank, blocks later document promotion and requires investigation; it cannot be
turned into a content-key rejection using path/mtime. Shutdown joins dispatcher
workers within a bounded grace period and preserves durable recovery references.
The inbound README explains fatal errors, cleanup and terminal observation.

Local managed import has a separate delivery/snapshot/report lifecycle. Its
WatchService is only a doorbell for complete listing. Ordinary document
WatchService scanning has a different lost-event limitation; full polling is
the default. Generic retention works on individual leaves, whereas managed
import retains a source/report unit. Do not interchange these storage APIs.

Supported ownership is one daemon per inbox/database namespace with controlled
private directories. Cross-process fencing is not provided. Claim, seal and
protected import publication require atomic filesystem operations; ordinary
retention and the older ingestion ledger have explicitly weaker fallbacks.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-ingest
make test-integration-module MODULE=adapters/adapter-ingest
```

The first command runs Surefire; the second runs Failsafe and upstream modules.
Full reactor acceptance uses `make verify`. The test READMEs distinguish local
filesystem evidence from bootstrap, database, remote-share and deployment checks.

[Daemon ingestion](../../docs/dev/ingestion.md) owns the cross-module
admission/run/receipt protocol; [managed import](../../docs/dev/dataframe-import.md)
owns its separate transaction and recovery model. See the
[import operator guide](../../docs/guides/dataframe-import.md),
[deployment guide](../../docs/guides/deployment.md) and
[known issues](../../docs/KNOWN-ISSUES.md) for operation and open limitations.
[ADR 0038](../../docs/ADR/0038-durable-bounded-document-execution.md)
records the durable bounded document design. Precise member contracts remain
in Java Javadoc.
