# com.iocextractor.domain.model

## Назначение

Неизменяемые доменные value objects, на которых работает весь конвейер.

**Правило слоя:** чистые `record`-типы, без зависимостей и поведения
инфраструктуры. Равенство — по значению.

## Структура

| Файл | Назначение |
|---|---|
| `Indicator.java` | Готовый индикатор: значение + тип + провенанс; `dedupKey()` |
| `IndicatorType.java` | Типы IOC (IPV4/DOMAIN/URL/MD5/SHA1/SHA256) + STIX-словарь |
| `IndicatorCategory.java` | Семейство: NETWORK / FILE (маршрутизация по артефактам) |
| `SourceContext.java` | Провенанс: метка `source` + (опц.) секция |
| `MaskMatch.java` | Коды `url_match` / `host_match` сетевой маски |
