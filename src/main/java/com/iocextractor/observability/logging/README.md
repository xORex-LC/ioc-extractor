# com.iocextractor.observability.logging

## Назначение

Тонкие helpers для записи operational log events через SLF4J без ручного
дублирования MDC boilerplate в producer-коде.

**Правило слоя:** helpers только выставляют event fields вокруг одного log call.
Они не формируют JSON вручную и не выбирают appenders.

## Структура

| Файл | Назначение |
|---|---|
| `LogEvent.java` | Builder одного log event |
| `LogEvents.java` | Factory methods для уровней |
| `LoggingPipelineObserver.java` | Bridge `PipelineObserver` → stage log events |

## Зависимости

**Зависит от:** `observability`, SLF4J.  
**Не импортируется:** domain, adapter, bootstrap.
