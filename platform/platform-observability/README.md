# platform/platform-observability

## Назначение

Operational logging helpers: executable structured-field type schema, MDC
scope, stable log fields/actions/outcomes, sensitive-value sanitization and
pipeline logging observer with nested run/stage scopes.

**Правило слоя:** observability не содержит domain/application business rules и
не настраивает runtime appenders; `logback-spring.xml` живёт в `ioc-app`.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/observability/` | Logging taxonomy with JSON scalar types, MDC helpers and shared sensitive-value sanitizer |
| `src/test/java/com/iocextractor/observability/` | Taxonomy and MDC tests |

## Зависимости

**Зависит от:** `ioc-platform-etl`, SLF4J API.

**Не импортируется:** domain, adapters, bootstrap, Logback appenders in main.
