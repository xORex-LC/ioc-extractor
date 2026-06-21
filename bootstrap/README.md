# bootstrap

## Назначение

Runtime assembly layer. Contains executable application modules and composition
roots.

**Правило слоя:** bootstrap may depend on all modules to wire them together, but
does not implement business rules or adapter algorithms.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `ioc-app/` | Spring Boot executable application |

## Зависимости

**Зависит от:** platform, core and adapters required by the application.

**Не импортируется:** inner modules do not depend on bootstrap.
