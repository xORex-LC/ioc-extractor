# com.iocextractor.domain.attribute

## Назначение

Атрибуция провенанса: проставление `source` индикаторам по ближайшему
предшествующему заголовку-маркеру секции (`БИБ-…`, `Письмо ФСТЭК России …`).

**Правило слоя:** чистый доменный сервис; маркеры компилируются через порт
`PatternEngine`, список маркеров — из конфигурации.

## Структура

| Файл | Назначение |
|---|---|
| `SourceAttributor.java` | Порт: `attribute(text, rawIndicators)` |
| `MarkerSourceAttributor.java` | Ближайший предшествующий маркер → метка `source` |
| `AttributionOutcome.java` | Найденные маркеры + решения по каждому indicator |
| `AttributionDecision.java` | Raw indicator и выбранный preceding marker |
| `SourceMarker.java` | Нормализованный marker label + позиция |

## Заметки

Индикаторы до первого маркера получают `SourceContext.UNKNOWN`. Нормализация
метки схлопывает пробелы, включая неразрывный ` ` из Word-экспорта.
