# com.iocextractor.platform.etl

## Назначение

Framework-free ядро ETL-конвейера: `Envelope`, `EnvelopeMeta`, `Stage`,
`StageId`, `Pipeline`, `PipelineRunner` и `PipelineObserver`. Пакет задаёт
универсальную модель Pipes-and-Filters и не знает о IOC-предметке, Spring, CLI,
Tika, CSV или logging/MDC.

**Правило слоя:** ETL core зависит только на diagnostics/errors. Порядок стадий
задаётся в `Pipeline`, а не внутри stage-классов.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `Envelope.java` | Immutable payload + metadata + diagnostics |
| `EnvelopeMeta.java` | Generic run/source/stage metadata + extension attributes |
| `Stage.java` | Контракт одного filter-шагa |
| `Pipeline.java` | Type-safe список стадий |
| `PipelineRunner.java` | Последовательное исполнение стадий + `FailurePolicy` |
| `PipelineObserver.java` | Порт operational events для runner |
| `NoopPipelineObserver.java` | No-op observer для тестов/простых конструкторов |
| `StageId.java` | Generic stable stage identifier |
| `StageExecutionException.java` | Ошибка неверного исполнения pipeline |

## Зависимости

**Зависит от:** `ioc-platform-diagnostics`, `ioc-platform-errors`.

**Не импортируется:** `adapter`, `bootstrap`, Spring, Logback/MDC, Tika,
commons-csv, picocli, `ioc-domain`, `ioc-application`.
