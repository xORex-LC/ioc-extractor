# com.iocextractor.observability

## Назначение

Framework-free контракты operational observability: имена полей, action/outcome
таксономия и `MdcScope`. Пакет задаёт стабильный logging contract, но не
форматирует JSON и не выбирает appenders.

**Правило слоя:** observability может знать о SLF4J MDC как о logging boundary,
но не содержит бизнес-правил IOC, adapter IO деталей или diagnostics core logic.

## Структура

| Файл / подпапка | Назначение |
|---|---|
| `LogField.java` | Stable ECS/custom field names |
| `EventAction.java` | Stable `event.action` values |
| `EventOutcome.java` | Stable `event.outcome` values |
| `ObservabilityMode.java` | Runtime mode: `oneshot` / `daemon` |
| `MdcScope.java` | Try-with-resources MDC scope with restore-on-close |
| `logging/` | Thin SLF4J event helper and pipeline observer |
| `diagnostics/` | Diagnostic-to-log bridge |

## Зависимости

**Зависит от:** SLF4J API/MDC.  
**Не импортируется:** adapters, bootstrap, domain business packages.
