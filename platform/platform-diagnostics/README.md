# platform/platform-diagnostics

## Назначение

Framework-free diagnostics subsystem: diagnostic model, codes, catalog, result,
notification, failure policy, rendering and sink ports.

**Правило слоя:** diagnostics core не пишет в SLF4J и не знает про pipeline,
domain, adapters или bootstrap.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/diagnostics/` | Diagnostics API and implementation |
| `src/test/java/com/iocextractor/diagnostics/` | Unit and documentation consistency tests |

## Зависимости

**Зависит от:** `ioc-platform-errors`.

**Не импортируется:** Spring, SLF4J/Logback, `core`, `adapters`, `bootstrap`.
