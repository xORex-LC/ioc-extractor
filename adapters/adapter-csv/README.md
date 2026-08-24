# adapters/adapter-csv

## Назначение

CSV integration-family adapter providing strict inbound record streaming,
artifact row mapping, canonical CSV projection and immutable artifact slices.

**Правило слоя:** owns Commons CSV parsing/writing, artifact row mapping and
local atomic publication; domain/application do not depend on Commons CSV or
filesystem mechanics. Import/export orchestration remains in application ports.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/in/csv/` | Strict decoder, exact-header validation and callback-streaming CSV reader |
| `src/main/java/com/iocextractor/adapter/out/sink/csv/` | CSV projection, export slice writers and mapping components |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-domain`, platform
errors/diagnostics/observability, Commons CSV, SLF4J API.

**Не импортируется:** bootstrap and sibling adapters.

## Контракты

- legacy/current projection path формирует CSV из canonical repository;
  parentless relative leaf поддерживается через current directory, filesystem
  root отклоняется как path без имени файла и в bootstrap preflight, и на
  adapter boundary;
- mutable projection проверяет logical header/cell values до записи и при
  charset replacement возвращает одну `SINK.CHARSET_UNMAPPABLE` diagnostic с
  точными counts; diagnostic доставляет application orchestration, не adapter;
- `CsvArtifactPreparer` выполняет config-driven filtering/mapping до policy
  checkpoint; provider/transform может явно вернуть data-dependent failure
  только через `MappingValueException`. `ConfigurableRowMapper` локализует его
  как `RowMappingException` (column + component kind/name), а preparer создаёт
  element diagnostic. Остальные mapper defects останавливают run;
  route/filter/mapping decisions передаются в gated application TRACE port без
  повторной классификации;
- `CsvProcessedImportRowPreparer` подключает explicit `processed` import mode к
  обычным refang/extract/classify и CSV artifact policies. Он требует ровно один
  whole-cell IOC для каждого semantic carrier, сохраняет compound-row
  correlation и никогда не подменяет `processed` режим поведением `as-is`;
- public id остаётся deferred slot до commit; `from: id` не допускает
  `when-type` или transforms, что проверяется bootstrap config preflight;
  mapping SPI не получает временный id, а id-provider возвращает пустой slot;
- `CsvArtifactSliceWriter` получает callback-stream из `SnapshotSliceReader`,
  пишет data/manifest/`_SUCCESS` в staging и публикует каталог одним
  `ATOMIC_MOVE`; immutable slice encoder использует `REPORT`, потому что lossy
  bytes нарушили бы hash/manifest contract;
- JSON codec внедряется через `SliceManifestCodec`: compile-time зависимости на
  sibling Jackson adapter нет;
- service DB, export-run transitions и delivery в модуль не входят.
