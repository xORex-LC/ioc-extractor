# com.iocextractor.adapter

## Назначение

Адаптеры — внешний слой ввода/вывода. Реализуют порты прикладного ядра поверх
конкретных технологий. Здесь (и только здесь, наряду с `bootstrap`) живут внешние
библиотеки: picocli, Apache Tika, RE2/J, Guava PSL, Commons CSV, Jackson,
Spring JDBC/Integration, SQLite/HikariCP и SMBJ.

**Правило слоя:** зависит внутрь — на `application` (порты) и `domain`. Ядро
никогда не импортирует из `adapter`; адаптеры не зависят друг на друга.

## Структура

| Maven-модуль | Назначение |
|---|---|
| `adapter-cli-picocli/` | Driving CLI (`ioc extract`, `export`, `sync`, `import`, `health`) |
| `adapter-regex-re2j/` | `PatternEngine`: RE2/J по умолчанию и JDK implementation |
| `adapter-psl/` | Public Suffix List host classification на Guava |
| `adapter-source-tika/` | `SourceReader`: Apache Tika |
| `adapter-csv/` | Strict import parsing, mapping, mutable CSV projections и export CSV |
| `adapter-manifest-json-jackson/` | Deterministic versioned export manifest codec |
| `adapter-store-jdbc/` | Canonical/lifecycle storage и durable coordination ledgers |
| `adapter-transport-smb/` | SMB fetch/publish, managed-import transport и change notification |
| `adapter-ingest/` | Spring Integration file-poll daemon и filesystem lifecycle |

## Зависимости

**Зависит от:** `application`, `domain` (+ библиотеки адаптера).
**Не импортируется** внутренними слоями.
