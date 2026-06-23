# com.iocextractor.adapter.out.store.jdbc

## Назначение

JDBC storage adapter internals: datasource creation, SQLite runtime policy,
schema migration mechanics and repository implementations for storage ports.

**Правило слоя:** this package may use JDBC, Hikari, SQL and database-specific
mechanics. It must expose only application-port implementations and storage VO
types to bootstrap; domain/application do not import this package.

## Структура

| Файл / группа | Назначение |
|---|---|
| `Sqlite*` | SQLite-specific datasource and PRAGMA policy |
| `Jdbc*` | Future JDBC implementations of storage ports |
| `*Schema*` | SQLite `user_version` runner and migration support |

## Зависимости

**Зависит от:** application ports, platform errors, platform diagnostics,
platform observability, Spring JDBC/JDBC, Hikari.

**Не импортирует:** bootstrap and sibling adapters.

## Ограничения

- `SqliteUserVersionSchemaMigrator` currently splits `vN.sql` files as simple
  semicolon-delimited DDL. This is intentional for the service v1 schema; add a
  proper SQL script parser before migrations contain triggers, `BEGIN...END`
  blocks, seed data with semicolons, or string literals containing semicolons.
