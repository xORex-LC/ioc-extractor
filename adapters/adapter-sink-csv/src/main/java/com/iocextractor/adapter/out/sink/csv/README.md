# com.iocextractor.adapter.out.sink.csv

## Назначение

Выходные адаптеры порта `IocSink`: запись индикаторов в CSV-артефакты. Схема
артефакта (колонки) и заполнение — **декларативны** (конфиг), не зашиты в код.

**Правило слоя:** реализует порт; инкапсулирует commons-csv и диалект CSV.
Новый формат артефакта = блок `columns` в конфиге (без кода); новая семантика
колонки = новый тонкий `ValueProvider`/`Transform`.

## Структура

| Файл | Назначение |
|---|---|
| `CsvIocSink.java` | Запись артефакта: фильтр по типу/признакам, id, делегирование мапперу |
| `ArtifactFilter.java` | Artifact-level `include`/`exclude` фильтр поверх `accepts` |
| `RowMapper.java` | Порт маппинга `Indicator → строка CSV` |
| `ConfigurableRowMapper.java` | Generic-маппер по `ColumnSpec` + реестры провайдеров/трансформаций |
| `ColumnSpec.java` | Декларативная спека колонки (`name/from/value/when-type/transform`) |
| `ValueProvider.java` + `*ValueProvider` | Источники значений: `id`, `value`, `source.label`, `match.url`, `match.host`, `address.url`, `address.ip` |
| `Transform.java` + `*Transform` | Трансформации: `lower`, `lower-host` (только хост), `upper`, `strip-prefix` |
| `IdGenerator.java` | Последовательность id артефакта (ascending/descending) |
| `PartitionedCsvSinkFactory.java` | Daemon partition sinks по source-key/content-hash |
| `CsvArtifactRepositories.java` | Чтение partition CSV и атомарная запись canonical CSV за aggregation ports |
| `CsvStableIdIndex.java` | Sidecar CSV stable id index для stage 11 aggregation |

## Заметки

Диалект: `;`, кавычки на не-null значениях, `null` → литерал `NULL`
(`QuoteMode.ALL_NON_NULL` + `nullString`). DSL ограничен (см.
`docs/output-mapping.md`): artifact `include`/`exclude`, провайдер/`const`,
`when-type`, упорядоченные `transform` — без выражений в конфиге. Реестры
собираются в `bootstrap/AppConfig`.

Stage 11 aggregation использует те же artifact definitions, но держит
ответственности раздельно: application orchestration не знает CSV-диалект, а
adapter отвечает за partition/canonical files, stable id sidecar и key extraction.
