# platform/platform-errors

## Назначение

Нижний platform-модуль с базовыми exception contracts.

**Правило слоя:** не зависит от других внутренних модулей и не содержит
diagnostics/logging/domain-логику.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/common/` | Базовые error/common-типы |

## Зависимости

**Зависит от:** JDK.

**Не импортируется:** Spring, SLF4J, domain/application/adapters/bootstrap.
