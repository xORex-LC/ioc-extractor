# platform

## Назначение

Переиспользуемые платформенные подсистемы, не привязанные к IOC-бизнес-логике:
ошибки, diagnostics, ETL kernel, control-events, concurrency, observability и
bridge diagnostics→logging.

**Правило слоя:** platform-модули не зависят от `core/`, `adapters/` и
`bootstrap/`. Bridge-модули могут зависеть только на platform-модули, которые
они связывают.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `platform-errors/` | Базовая иерархия ошибок |
| `platform-diagnostics-api/` | JDK-only модель diagnostics, result/policy, bounded collectors и sinks |
| `platform-diagnostics/` | IOC-каталог, metadata, exception и renderer |
| `platform-etl/` | Generic Pipes-and-Filters kernel |
| `platform-events/` | Framework-free control-event model and publish-only port |
| `platform-concurrency/` | Framework-free keyed execution/admission primitives |
| `platform-logging-slf4j/` | Общие typed SLF4J events, restoring MDC, пять ECS scalar fields и явное маскирование; IOC использует библиотеку через обёртки observability |
| `platform-observability/` | Каталоги IOC, обёртки над logging-slf4j и ETL logging observer |
| `platform-diagnostics-logging/` | Bridge `DiagnosticSink` → operational logs |

## Зависимости

**Зависит от:** JDK и точечных external API по модулю.

**Не импортируется:** `core`, `adapter`, `bootstrap`.
