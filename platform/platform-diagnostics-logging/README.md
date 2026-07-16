# platform/platform-diagnostics-logging

## Назначение

Bridge module that maps diagnostics to operational log events with typed
SLF4J key/value fields.

**Правило слоя:** bridge зависит только на diagnostics and observability
platform modules. Diagnostics core remains logging-free.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/observability/diagnostics/` | logging sink, resilient delivery decorator и raw-IOC redaction formatter using the shared observability sanitizer |
| `src/test/java/com/iocextractor/observability/diagnostics/` | Bridge tests |

## Зависимости

**Зависит от:** `ioc-platform-diagnostics`, `ioc-platform-observability`,
SLF4J API.

**Не импортируется:** domain/application/adapters/bootstrap.
