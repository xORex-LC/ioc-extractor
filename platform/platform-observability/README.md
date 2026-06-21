# platform/platform-observability

## Назначение

Operational logging helpers: MDC scope, stable log fields/actions/outcomes and
pipeline logging observer.

**Правило слоя:** observability не содержит domain/application business rules и
не настраивает runtime appenders; `logback-spring.xml` живёт в `ioc-app`.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/observability/` | Logging taxonomy and MDC helpers |
| `src/test/java/com/iocextractor/observability/` | Taxonomy and MDC tests |

## Зависимости

**Зависит от:** `ioc-platform-etl`, SLF4J API.

**Не импортируется:** domain, adapters, bootstrap, Logback appenders in main.
