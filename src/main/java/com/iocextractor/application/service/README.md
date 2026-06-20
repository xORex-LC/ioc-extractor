# com.iocextractor.application.service

## Назначение

Реализация use-case: оркестратор ETL-конвейера, выраженный исключительно через
порты.

**Правило слоя:** зависит на `domain` и на порты `port/in`/`port/out`; не знает о
конкретных адаптерах и фреймворках (framework-free, собирается в `bootstrap`).

## Структура

| Файл | Назначение |
|---|---|
| `IocExtractionService.java` | `read → refang → extract → attribute → dedup → sink(s)` |

## Заметки

Дедуп: внутри пакета по `dedupKey()` + против `LookupRepository`. Запись —
маршрутизация по `IndicatorType` в принимающие `IocSink`-и.
