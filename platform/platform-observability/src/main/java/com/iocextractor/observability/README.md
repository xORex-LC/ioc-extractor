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

Artifact emission расширяет стабильную taxonomy actions
`export_start|export_slice_write|export_complete|export_recover` и fields
`ioc.export.profile`, `ioc.export.slice.id`, `ioc.export.revision`. Конкретный
producer adapter находится в bootstrap, чтобы generic observability module не
зависел от application export model.

Remote sync расширяет taxonomy actions
`sync_fetch_start|sync_fetch_complete|sync_publish_start|sync_publish_complete|remote_fetch|remote_publish`
и fields `ioc.sync.endpoint`, `ioc.sync.files`, `ioc.sync.target`. Transport-specific
details и credentials в observability core не попадают.

## Зависимости

**Зависит от:** SLF4J API/MDC.  
**Не импортируется:** adapters, bootstrap, domain business packages.
