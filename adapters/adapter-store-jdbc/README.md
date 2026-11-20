# adapters/adapter-store-jdbc

## Purpose and boundary

SQLite implementation of inward application storage ports using Spring JDBC,
sqlite-jdbc and HikariCP. The internal reactor artifact is
`com.iocextractor:ioc-adapter-store-jdbc`; it is not independently published or
runnable. Native SQLite is the production canonical backend. Private document
and CSV-import workspaces are preparation mechanisms, not alternate canonical
stores or publication backends.

The adapter owns SQL, local transactions, schemas/migrations, identities,
lifecycle/history/receipts, export slots, coordination ledgers and storage health
observations. Application owns policies, orchestration and recovery; bootstrap
owns datasource composition, shared writer admission and pool shutdown. Domain
and application remain free of JDBC/SQL/Spring transaction types.

## Structure

| Location | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Storage integration family and inward/test dependencies |
| [Production package](src/main/java/com/iocextractor/adapter/out/store/jdbc/README.md) | Grouped map of repositories, transaction helpers, workspaces and ledgers |
| [Dataframe migrations](src/main/resources/com/iocextractor/adapter/out/store/jdbc/dataframe/README.md) | Canonical metadata/receipt/identity/slot migration chain |
| [Service migrations](src/main/resources/com/iocextractor/adapter/out/store/jdbc/service/README.md) | Durable coordination migration chain and retained upgrade history |
| [Test package](src/test/java/com/iocextractor/adapter/out/store/jdbc/README.md) | Unit/SQL trust checks, file-backed SQLite contracts and opt-in import load |

There is one production Java package and one matching test package. Migration
SQL is packaged under two separate resource families; test data/old-schema
fixtures are built inline rather than stored in a test-resource tree.

## Store authority and dependencies

| Store | Owns |
|---|---|
| Dataframe DB | Canonical rows/keys/aliases, provenance and ordered origins, lifecycle/history, observation order, canonical receipts, ID allocators, projection generations and export-slot state |
| Service DB | Ingest/run, document admission/policy pins, import delivery/recovery references, export progress, fetch and publish coordination |
| Private workspaces | Source/policy-pinned preparation and sealed import evidence; disposable under their ownership/recovery rules |

These stores do not share a transaction. A service-ledger status or CSV file
cannot substitute for canonical receipt authority. In particular, nullable
`import_delivery.public_mutations` is neither populated nor read by the current
delivery ledger: NULL is not a zero count or failed-commit signal. Committed
mutation evidence comes from dataframe `import_commit` through
`JdbcImportCommitEvidenceStore`.

Direct production dependencies are `ioc-application`, platform diagnostic API,
errors/diagnostics/observability, SLF4J, Spring JDBC/transactions, HikariCP and
sqlite-jdbc. The application TCK is test-scoped. Versions and Java 21 come from
the parent. There is no sibling-adapter, CSV parser, Camel, transport or bootstrap
dependency; composition supplies those collaborators through inward contracts.

## Entry points and contracts

Bootstrap opens service/dataframe pools, runs the explicit version catalogs,
reconciles configured artifact schemas/identity and completes lifecycle admission
before intake. Artifact reconciliation is additive and excludes internal `_`
columns from public drift checks; destructive/type drift requires an explicit
migration. Identity backfill preflights collisions before durable mutation.

Ordinary confirmation is atomic per artifact; managed import promotes accepted
branches and its receipt in one dataframe transaction. Independently committed
canonical/lifecycle ID ranges stay burned after rollback. Reusable sparse
immutable-export slots are a separate `(profile, artifact)` identity contract.
Native canonical writers roll back unsuccessful transactions, including Error
paths, before restoring auto-commit; post-commit failures remain receipt-owned.
This is not a blanket guarantee for every transaction helper: the migrator's
unchecked-failure gap is [OPS-15](../../docs/KNOWN-ISSUES.md#ops-15).

Writer admission is shared, in-process and non-preemptive. Native lifecycle writes
sample safe UTC after SQLite write ownership; active membership uses a strict
deadline. Statement/mutation sessions are thread-confined and close before their
caller commits. Workspaces account memory/disk/pins independently; SQL-backed
planning and streaming remove retained delivery-wide Java payloads without
establishing a total process RSS bound.

Mutable projection streaming returns count/generation from one read transaction;
acknowledgement installs only that generation, leaving newer work pending. CSV
installation/acknowledgement is application-serialized. Immutable export captures
profile coverage and rows in one snapshot after slot reconciliation. Filesystem
publication and service finalization remain separate recoverable effects.

The production package explains receipt completeness/batching, slot validation,
connection ownership and workspace cleanup. PRAGMAs are physical-driver settings,
not mutations repeated on each pool borrow. Storage health is a local observation,
not proof that all application ledgers, clocks or delivery state are healthy.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-store-jdbc
make test-integration-module MODULE=adapters/adapter-store-jdbc
```

Surefire and Failsafe own separate cohorts; the opt-in managed-import load shell
normally skips. Full reactor acceptance uses `make verify`. The test README maps
local evidence, inherited TCKs and workload/target limits. A passing gate does not
close the [known storage/operational issues](../../docs/KNOWN-ISSUES.md).

See [storage](../../docs/dev/storage.md),
[canonical lifecycle](../../docs/dev/canonical-record-lifecycle.md),
[dataframe import](../../docs/dev/dataframe-import.md),
[native capacity](../../docs/dev/native-capacity.md) and
[ADR 0042](../../docs/ADR/0042-native-sqlite-production-storage.md).
