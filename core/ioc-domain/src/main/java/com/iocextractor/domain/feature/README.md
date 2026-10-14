# com.iocextractor.domain.feature

## Назначение

Нормализация значения индикатора и извлечение его структурных признаков
(`IndicatorFeatures`: host/port/path/query + вид хоста), на которых работает
классификация масок — вместо повторного строкового парсинга в маппере.

**Правило слоя:** чистый домен, без фреймворков и PSL-библиотек. Вид хоста
(registrable/subdomain) определяется через **порт `HostClassifier`** (реализация —
адаптер `adapter.out.psl` на Guava PSL).

## Структура

| Файл | Назначение |
|---|---|
| `IndicatorNormalizer.java` | Порт нормализации значения |
| `DefaultIndicatorNormalizer.java` | Обрезка хвостовой/окружающей пунктуации (`; , " '`, пробелы) |
| `IndicatorFeatures.java` | Признаки: `value/host/hasPort/hasPath/hasQuery/hostKind` |
| `IndicatorFeatureExtractor.java` | Порт извлечения признаков из `Indicator` |
| `DefaultIndicatorFeatureExtractor.java` | Парсинг scheme/authority/path/query; `:`-порт только в authority |
| `NetworkAddressParser.java` | Проверка полного HTTP(S) или scheme-free адреса без DNS и сетевого I/O |
| `NetworkHostDeriver.java` | Получение bare host/IP с сохранением источника |
| `NetworkSchemeDeriver.java` | Удаление только HTTP(S) с сохранением порта, пути, query, fragment и источника |
| `HostClassifier.java` | Порт классификации хоста (PSL за адаптером) |
| `HostKind.java` | `IP / REGISTRABLE / SUBDOMAIN / ONION / UNKNOWN` |

## Заметки

Признаки потребляет `MatchPolicy` (доменная классификация масок). Связь и
4-вариантное правило и извлечение/нормализация — `docs/dev/processing.md`.
