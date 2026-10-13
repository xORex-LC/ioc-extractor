# adapters/adapter-store-jdbc

The outbound relational-storage adapter implements inward-facing application
ports with Spring JDBC, sqlite-jdbc and HikariCP. It owns Native canonical
transactions, schemas/migrations, lifecycle/history/receipts, identities,
provenance, export slots, durable coordination ledgers and storage health.
Domain/application code never imports JDBC, SQL or Spring transaction types.

## Contents

| Location | Responsibility |
|---|---|
| `src/main/java/com/iocextractor/adapter/out/store/jdbc/` | Native repositories, transaction-scoped SQL helpers and private document/import workspaces |
| `src/main/resources/com/iocextractor/adapter/out/store/jdbc/dataframe/` | Versioned canonical dataframe migrations |
| `src/main/resources/com/iocextractor/adapter/out/store/jdbc/service/` | Versioned coordination migrations |
| `src/test/` | File-backed Native contracts, failure/recovery, lifecycle, matching, slot and workspace checks |

## Dependencies and boundaries

Depends on `ioc-application`, platform errors/diagnostics/observability, Spring
JDBC/transactions, HikariCP and sqlite-jdbc. The application TCK is test-only.
There is one production SQLite backend. Document preparation and sealed CSV
import staging are retained private input mechanisms, not alternate canonical
storage. The service DB owns coordination; the dataframe DB owns canonical
facts and receipts. Bootstrap owns datasource lifetime.

## Runtime contracts

- Artifact schema reconciliation is additive and excludes internal `_` columns
  from public-schema drift. Record/match identity migration stages collision
  checks before durable changes. Normal migrations reject unsupported formats.
- Ordinary lifecycle confirmation is atomic per artifact. Managed import is
  atomic across its branches and receipt. Canonical/lifecycle ID reservations
  are durable and never reused after rollback; external sparse slots are a
  separate `(profile, artifact)` contract.
- Fair writer admission does not preempt active transactions. Safe lifecycle
  UTC is sampled after obtaining Native write ownership; active reads use a
  strict deadline predicate.
- Indexed complete-key matching and mutation sessions reuse at most 32
  thread-confined statements. Sessions own statements; callers own connections,
  transactions, ID ranges, revision aggregation and receipt publication.
- Private document leases account aggregate memory, disk and pins. Disk-backed
  source pages/extraction scratch and the global reducer feed sealed repeatable
  cursors. Source scratch is removed before seal. File leases and ownership
  markers prevent cleanup of live or foreign workspaces.
- Bounded row decoding and 128-row/1-MiB receipt batches avoid a retained
  document-wide payload. Receipt replay merges ordered row/position cursors in
  one snapshot and rejects missing, duplicate or orphan position evidence.
- Mutable projection streaming returns count and generation from one read
  transaction. Acknowledgement records installed coverage only, leaving newer
  work pending. CSV installation/acknowledgement is application-serialized.
- Connection PRAGMAs are physical-driver settings, not repeated pool-borrow
  mutations. Logging uses the shared typed event/field catalog.

For cross-module algorithms, recovery, migrations and limits, see
[storage](../../docs/dev/storage.md),
[canonical lifecycle](../../docs/dev/canonical-record-lifecycle.md),
[dataframe import](../../docs/dev/dataframe-import.md) and
[native capacity](../../docs/dev/native-capacity.md).
The storage decision is [ADR 0042](../../docs/ADR/0042-native-sqlite-production-storage.md).
