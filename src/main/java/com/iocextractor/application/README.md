# com.iocextractor.application

## Назначение

Прикладной слой: порты (driving/driven) и use-case, оркестрирующий конвейер
извлечения. Описывает *что* делает приложение, не зная *как* (через порты).

**Правило слоя:** зависит только на `domain`. Не зависит на адаптеры, фреймворки
и `bootstrap`. Реализации портов внедряются снаружи (composition root).

## Структура

| Подпапка | Назначение |
|---|---|
| `port/in/` | Driving-порты: `ExtractIocsUseCase`, команда и результат |
| `port/out/` | Driven-порты: `SourceReader`, `IocSink`, `LookupRepository` |
| `service/` | `IocExtractionService` — оркестратор конвейера |

## Зависимости

**Зависит от:** `domain`. **Не импортируется** адаптерами иначе как через порты.
