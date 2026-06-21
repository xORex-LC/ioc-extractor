# com.iocextractor.observability.diagnostics

## Назначение

Bridge из diagnostics-модели в operational logging stream. Диагностика остаётся
отдельной подсистемой; этот пакет только публикует её как log event при
необходимости.

**Правило слоя:** bridge зависит на diagnostics и observability, но diagnostics
core не зависит на logging.

## Структура

| Файл | Назначение |
|---|---|
| `LoggingDiagnosticSink.java` | `DiagnosticSink`, пишущий diagnostic log events |

## Зависимости

**Зависит от:** `diagnostics`, `observability`, SLF4J.  
**Не импортируется:** domain, adapters, bootstrap.
