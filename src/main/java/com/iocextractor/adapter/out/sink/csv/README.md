# com.iocextractor.adapter.out.sink.csv

## Назначение

Выходные адаптеры порта `IocSink`: запись индикаторов в CSV-артефакты. Один
артефакт — своя схема (маппер), своя последовательность id и нормализация.

**Правило слоя:** реализует порт; инкапсулирует commons-csv и диалект CSV.
Новый артефакт = новый `RowMapper` + запись в конфиг, без правки конвейера (OCP).

## Структура

| Файл | Назначение |
|---|---|
| `CsvIocSink.java` | Запись артефакта: фильтр по типу, id, делегирование мапперу |
| `RowMapper.java` | Порт маппинга `Indicator → строка CSV` (один на схему) |
| `NetworkMaskRowMapper.java` | Схема масок (lower-case, `url_match`/`host_match`) |
| `FileHashRowMapper.java` | Схема хэшей (UPPER-case, `hash_md5/sha256/sha1`) |
| `IdGenerator.java` | Последовательность id артефакта (ascending/descending) |

## Заметки

Диалект: `;`, кавычки на не-null значениях, `null` → литерал `NULL`
(`QuoteMode.ALL_NON_NULL` + `nullString`).
