# adapters/adapter-store-jdbc

## Назначение

Outbound storage adapter for relational stores. It owns JDBC access, SQL dialect
mechanics, SQLite runtime policy, local transactions and schema migration
mechanics behind application ports.

**Правило слоя:** implements storage ports with Spring JDBC/JdbcClient and JDBC
drivers. Domain and application never import JDBC, SQL, Hikari, SQLite driver or
Spring transaction types.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/store/jdbc/` | JDBC storage implementations and internal SQL helpers |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-errors`, Spring JDBC, HikariCP,
runtime JDBC drivers.

**Не импортируется:** domain/application internals and sibling adapters.
