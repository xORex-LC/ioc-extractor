# com.iocextractor.adapter

## Назначение

Адаптеры — внешний слой ввода/вывода. Реализуют порты прикладного ядра поверх
конкретных технологий. Здесь (и только здесь, наряду с `bootstrap`) живут внешние
библиотеки: picocli, Apache Tika, RE2/J, commons-csv, Spring JDBC.

**Правило слоя:** зависит внутрь — на `application` (порты) и `domain`. Ядро
никогда не импортирует из `adapter`; адаптеры не зависят друг на друга.

## Структура

| Подпапка | Назначение |
|---|---|
| `in/cli/` | Входной адаптер: CLI на picocli (`ioc extract …`) |
| `out/regex/` | `PatternEngine`: RE2/J (по умолчанию) и JDK |
| `out/source/` | `SourceReader`: Apache Tika |
| `out/sink/csv/` | `IocSink`: запись CSV-артефактов (commons-csv) |
| `out/lookup/` | `LookupRepository`: чтение существующего CSV |
| `out/store/jdbc/` | durable storage ports over relational stores (Spring JDBC/JDBC drivers) |

## Зависимости

**Зависит от:** `application`, `domain` (+ библиотеки адаптера).
**Не импортируется** внутренними слоями.
