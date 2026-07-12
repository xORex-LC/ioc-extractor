# com.iocextractor.domain.extract

## Назначение

Извлечение индикаторов из деобфусцированного текста и абстракция regex-движка.

**Правило слоя:** домен зависит только на порт `PatternEngine`, не на конкретный
движок. Паттерны — RE2-совместимы (`\b`, без lookaround/backref), чтобы работать
на обоих адаптерах движка.

## Структура

| Файл | Назначение |
|---|---|
| `PatternEngine.java` | Порт SPI движка regex: `compile` → `findAll` |
| `IndicatorExtractor.java` | Порт извлечения индикаторов |
| `RegexIndicatorExtractor.java` | Реализация: приоритеты типов + «захват» диапазонов |
| `Span.java` | Диапазон совпадения `[start,end)` + текст |
| `RawIndicator.java` | Найденный индикатор до атрибуции (значение, тип, позиция) |
| `ExtractionOutcome.java` | Accepted indicators + полный поток match decisions |
| `ExtractionDecision.java` | Pattern/type/span и решение overlap resolver-а |
| `ExtractionDecisionStatus.java` | `ACCEPTED` / `DROPPED_OVERLAP` |

## Заметки

Порядок паттернов = приоритет: URL/IP «забирают» диапазоны раньше, чем голый
домен (хост URL не переэмитится как отдельный домен).
Scalar/list-only API не используется: pure outcome сохраняет факты raw match и
overlap-решения для diagnostics/TRACE, не связывая домен с observability.
