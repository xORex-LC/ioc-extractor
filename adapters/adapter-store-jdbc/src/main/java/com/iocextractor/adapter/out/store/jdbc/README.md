# com.iocextractor.adapter.out.store.jdbc

## Назначение

JDBC storage adapter internals: datasource creation, SQLite runtime policy,
schema migration mechanics, dataframe schema reconciliation, repository
implementations for storage ports and legacy artifact import.

**Правило слоя:** this package may use JDBC, Hikari, SQL and database-specific
mechanics. It must expose only application-port implementations and storage VO
types to bootstrap; domain/application do not import this package.

## Структура

| Файл / группа | Назначение |
|---|---|
| `Sqlite*` | SQLite-specific datasource and PRAGMA policy |
| `Jdbc*` | JDBC implementations of storage ports |
| `*Schema*` | SQLite `user_version` runner, migration support and dataframe reconciler |
| `Dataframe*` | Table-per-artifact desired schema, additive plan and reconciliation |
| `JdbcLegacyArtifactImporter` | One-shot import of legacy rows (read via a source `CanonicalArtifactRepository`) into dataframe tables |

## Зависимости

**Зависит от:** application ports, platform errors, platform diagnostics,
platform observability, Spring JDBC/JDBC, Hikari.

**Не импортирует:** bootstrap and sibling adapters.

## Ограничения

- `SqliteUserVersionSchemaMigrator` currently splits `vN.sql` files as simple
  semicolon-delimited DDL. This is intentional for the service v1 schema; add a
  proper SQL script parser before migrations contain triggers, `BEGIN...END`
  blocks, seed data with semicolons, or string literals containing semicolons.
- `DataframeSchemaReconciler` accepts only additive changes. Existing unexpected
  non-internal columns or type drift are startup-fatal guardrails; `_`-prefixed
  internal columns are intentionally ignored by config drift detection.
