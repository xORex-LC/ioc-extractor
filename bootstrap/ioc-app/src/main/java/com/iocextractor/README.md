# com.iocextractor

## Назначение

Корневой пакет приложения. Содержит точку входа Spring Boot и разбит на слои
гексагональной архитектуры (Clean Hex + Onion).

**Правило слоя:** зависимости направлены внутрь (`adapter`/`bootstrap` →
`application` → `domain`). Внутренние слои не импортируют из внешних.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `domain/` | Чистое доменное ядро (без фреймворков) |
| `application/` | Порты (in/out) и use-case оркестратор |
| `adapter/` | Входные/выходные адаптеры (CLI, Tika, CSV, regex-движки) |
| `bootstrap/` | Конфигурация, composition root и daemon schedulers (ingest/export/sync/retention) |
| `common/` | Общие типы (исключения) |
| `diagnostics/` | Диагностика обработки данных в pipeline |
| `observability/` | Operational logging taxonomy, MDC scope и bridge diagnostics→logs |
| `IocExtractorApplication.java` | Точка входа (Spring Boot, CLI, без web) |

## Точка входа

`IocExtractorApplication.main` → `CliRunner` → picocli (`ioc extract …`).
Архитектура целиком — [docs/architecture.md](../../../../../docs/architecture.md).
