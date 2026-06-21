# core

## Назначение

Внутреннее ядро IOC-приложения: предметный domain и application use cases.

**Правило слоя:** core не зависит от технических adapters или bootstrap.
`ioc-domain` не зависит от `ioc-application`; `ioc-application` зависит внутрь
на domain and platform contracts.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `ioc-domain/` | IOC bounded context and domain capabilities |
| `ioc-application/` | Use cases, ports and IOC ETL stages |

## Зависимости

**Зависит от:** `platform/*` по необходимости.

**Не импортируется:** adapters, bootstrap, Spring/Tika/CSV/picocli/Logback.
