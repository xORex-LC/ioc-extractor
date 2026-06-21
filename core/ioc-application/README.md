# core/ioc-application

## Назначение

Application layer: use-case ports, IOC ETL payloads/stages and
`IocExtractionService` pipeline orchestration.

**Правило слоя:** application работает через ports and domain/platform
contracts. It does not import concrete adapters, Spring or runtime logging.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/application/` | Ports, use cases, payloads and stages |
| `src/test/java/com/iocextractor/application/` | Application and stage tests |

## Зависимости

**Зависит от:** `ioc-domain`, `ioc-platform-etl`,
`ioc-platform-diagnostics`.

**Не импортируется:** adapters, bootstrap, Spring, Tika, CSV, picocli, Logback.
