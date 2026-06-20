# com.iocextractor.domain.classify

## Назначение

Классификация сетевой маски: определение кодов `url_match` / `host_match` для
индикатора.

**Правило слоя:** чистое доменное правило. Конвенция кодов — конфигурируема
(шаблоны `MaskMatch` внедряются), а не захардкожена.

## Структура

| Файл | Назначение |
|---|---|
| `MatchPolicy.java` | Порт: `MaskMatch classify(Indicator)` |
| `DefaultMatchPolicy.java` | Правило: голый хост/IP → `bareHost`; полный URL → `fullUrl` |

## Заметки

«Полный URL» = есть путь и/или порт. Таблицу соответствий см. в
`docs/architecture.md` и `application.yml` (`ioc.classify`).
