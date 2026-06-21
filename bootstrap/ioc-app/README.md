# bootstrap/ioc-app

## Назначение

Spring Boot executable jar and composition root for the IOC extractor.

**Правило слоя:** owns runtime configuration, Spring wiring and executable
packaging. Business rules stay in domain/application; IO details stay in
adapters.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Executable app Maven module |
| `src/main/java/com/iocextractor/` | Application entrypoint and bootstrap config |
| `src/main/resources/` | Runtime `application.yml` and `logback-spring.xml` |
| `src/test/java/com/iocextractor/` | Context, architecture and golden e2e tests |

## Зависимости

**Зависит от:** selected platform/core/adapters modules, Spring Boot, ECS
Logback encoder.

**Не импортируется:** no inner module depends on `ioc-app`.
