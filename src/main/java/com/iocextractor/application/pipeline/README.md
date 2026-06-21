# com.iocextractor.application.pipeline

## Назначение

Framework-free ядро ETL-конвейера: `Envelope`, `EnvelopeMeta`, `Stage`,
`Pipeline` и runner. Пакет задаёт модель Pipes-and-Filters для application
слоя, не зная о Spring, CLI, Tika, CSV или logging/MDC.

**Правило слоя:** pipeline core зависит только на application/domain contracts и
diagnostics. Порядок стадий задаётся в `Pipeline`, а не внутри stage-классов.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `Envelope.java` | Immutable payload + metadata + diagnostics |
| `EnvelopeMeta.java` | Run/source/stage metadata для pipeline и будущей observability |
| `Stage.java` | Контракт одного filter-шагa |
| `Pipeline.java` | Type-safe список стадий |
| `PipelineRunner.java` | Последовательное исполнение стадий + `FailurePolicy` |
| `StageName.java` | Stable stage identifiers |
| `StageExecutionException.java` | Ошибка неверного исполнения pipeline |
| `payload/` | Типизированные payload records между стадиями |
| `stage/` | Concrete stages текущего ETL |

## Зависимости

**Зависит от:** `application.port.*`, `domain`, `diagnostics`.

**Не импортируется:** `adapter`, `bootstrap`, Spring, Logback/MDC, Tika,
commons-csv, picocli.
