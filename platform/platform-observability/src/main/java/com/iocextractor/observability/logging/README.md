# com.iocextractor.observability.logging

## Назначение

Тонкие helpers для записи operational log events через typed SLF4J key/value
pairs без ручного дублирования transport boilerplate в producer-коде.

**Правило слоя:** helpers нормализуют catalogued event fields и выполняют один
log call. Они не формируют JSON вручную и не выбирают appenders. Ambient
correlation остаётся в string-only MDC.

## Структура

| Файл | Назначение |
|---|---|
| `LogEvent.java` | Builder одного typed log event; event-local fields побеждают одноимённый MDC |
| `LogValueNormalizer.java` | Fail-fast normalization по `LogValueType` |
| `LogEvents.java` | Factory methods для уровней |
| `LoggingPipelineObserver.java` | Bridge `PipelineObserver` → stage log events |

## Зависимости

**Зависит от:** `observability`, SLF4J.  
**Не импортируется:** domain, adapter, bootstrap.
