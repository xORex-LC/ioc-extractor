# adapters/adapter-store-jdbc

## Назначение

Outbound storage adapter for relational stores. It owns JDBC access, SQL dialect
mechanics, SQLite runtime policy, local transactions, schema migration mechanics,
dataframe schema reconciliation, artifact repositories, run-ledger checkpoints
and legacy artifact import. As an edge module it also emits storage diagnostics
and operational ECS log events for startup/storage actions.

**Правило слоя:** implements storage ports with Spring JDBC/JdbcClient and JDBC
drivers. Domain and application never import JDBC, SQL, Hikari, SQLite driver or
Spring transaction types.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/store/jdbc/` | JDBC storage implementations and internal SQL helpers |
| `src/main/resources/com/iocextractor/adapter/out/store/jdbc/dataframe/` | Versioned dataframe format migrations |
| `src/main/resources/com/iocextractor/adapter/out/store/jdbc/service/` | Versioned service storage migrations |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-errors`,
`ioc-platform-diagnostics`, `ioc-platform-observability`, Spring JDBC, HikariCP,
runtime JDBC drivers.

**Не импортируется:** domain/application internals and sibling adapters.

## Runtime Notes

- The initial service datasource factory opens one Hikari pool. `writeMax` and
  `readMax` are retained as a capacity budget; dedicated read/write pools and
  the strict write-pool=1 topology belong to bootstrap wiring in the ledger
  selector slice.
- SQLite connection PRAGMAs are installed as Xerial `SQLiteConfig` driver
  properties on physical connection creation, not on every pool borrow.
- Dataframe business tables are reconciled additively from `ioc.sink.artifacts`:
  missing tables/columns are created, order changes are ignored, and
  drop/rename/type drift fails before mutation. Internal `_`-prefixed columns are
  excluded from config drift checks.
- `JdbcCanonicalArtifactRepository` writes rows with canonical `row_key` and
  `ON CONFLICT(row_key) DO NOTHING`, preserving explicit legacy ids when present.
  `JdbcLookupRepository` serves indexed-style SQL lookups and `maxId` without
  loading CSVs into memory.
- `JdbcLegacyArtifactImporter` is a one-shot migration helper that reads existing
  rows through a source `CanonicalArtifactRepository` (the CSV adapter owns CSV
  parsing) and writes them via `JdbcCanonicalArtifactRepository`, then raises
  SQLite sequences to the imported maximum id.
- `JdbcRunLedger` stores durable aggregation checkpoints in `aggregation_run`.
  Startup recovery treats `DB_COMMITTED` as recoverable by replaying the derived
  CSV projection from dataframe truth; failures before that checkpoint are marked
  `FAILED`.
