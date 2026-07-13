# platform/platform-etl

## Назначение

Generic Pipes-and-Filters ETL kernel: `Envelope`, `EnvelopeMeta`, `Stage`,
`StageId`, `Pipeline`, `PipelineRunner` and `PipelineObserver`.

`PipelineRunner` доставляет stage-local diagnostic delta exactly once до
`FailurePolicy`, сохраняет stopping code/cause и возвращает bounded summary.

**Правило слоя:** ETL kernel не знает о IOC-предметке, конкретных payload,
stage names, logging implementation или IO adapters.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/platform/etl/` | Generic ETL contracts and runner |
| `src/test/java/com/iocextractor/platform/etl/` | ETL kernel tests |

## Зависимости

**Зависит от:** `ioc-platform-diagnostics`, `ioc-platform-errors`.

**Не импортируется:** `ioc-domain`, `ioc-application`, adapters, bootstrap,
Spring, SLF4J/Logback.
