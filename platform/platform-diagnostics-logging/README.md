# platform/platform-diagnostics-logging

## Назначение

Bridge module that maps diagnostics to operational log events.

**Правило слоя:** bridge зависит только на diagnostics and observability
platform modules. Diagnostics core remains logging-free.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/observability/diagnostics/` | `LoggingDiagnosticSink` |
| `src/test/java/com/iocextractor/observability/diagnostics/` | Bridge tests |

## Зависимости

**Зависит от:** `ioc-platform-diagnostics`, `ioc-platform-observability`,
SLF4J API.

**Не импортируется:** domain/application/adapters/bootstrap.
