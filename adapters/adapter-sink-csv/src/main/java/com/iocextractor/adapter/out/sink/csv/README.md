# com.iocextractor.adapter.out.sink.csv

## Назначение

Выходные CSV-адаптеры для двух разных контрактов: текущая запись/проекция
canonical dataframe и формирование immutable export slices. Схема артефакта
(колонки) и заполнение остаются **декларативными**, не зашитыми в код.

**Правило слоя:** пакет реализует application-порты и инкапсулирует commons-csv,
кодировки и filesystem publication protocol. Он не меняет export-ledger, не
читает service DB и не координирует saga. Новый формат артефакта = блок `columns`
в конфиге (без кода); новая семантика колонки = новый тонкий
`ValueProvider`/`Transform`.

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
| `CsvArtifactProjection.java` | Производная CSV-проекция из canonical JDBC storage |
| `CsvArtifactSliceWriter.java` | Реализация `ArtifactSliceWriter`: staging, inspection/recovery и atomic publish; синхронный `SnapshotRowConsumer` |
| `CsvSliceMaterialization.java` | Состояние одного callback-сеанса: `ArtifactRow → CSVPrinter → DigestOutputStream`, row count и SHA-256 за один проход |
| `SliceTreeVerifier.java` | Независимая проверка manifest identity, точного состава каталога, data hashes и `_SUCCESS → manifest` hash-chain |
| `FileSystemSliceRetentionStore.java` | Profile-scoped discovery и recursive directory-as-unit delete только verified final slices |
| `SliceDirectoryLayout.java` | Безопасное разрешение `.staging/<runId>` и `<profile>/<sliceName>` внутри configured root |
| `NioSliceFileOperations.java`, `SliceFileOperations.java` | `force(true)`, directory fsync и `ATOMIC_MOVE` без copy/rename fallback |
| `SliceHashes.java` | Потоковые SHA-256 helpers без загрузки data files в heap |

## Заметки

Диалект: `;`, кавычки на не-null значениях, `null` → литерал `NULL`
(`QuoteMode.ALL_NON_NULL` + `nullString`). DSL ограничен (см.
`docs/output-mapping.md`): artifact `include`/`exclude`, провайдер/`const`,
`when-type`, упорядоченные `transform` — без выражений в конфиге. Реестры
собираются в `bootstrap/AppConfig`.

DB truth switch использует те же artifact definitions, но держит ответственности
раздельно: application orchestration не знает CSV-диалект, а adapter отвечает за
projection-файл и key extraction.

## Immutable slice protocol

`stage` создаёт новый каталог `root/.staging/<runId>/` и принимает строки только
через синхронные callbacks `SnapshotSliceReader`. Для каждого artifact сначала
пишется header, затем строки в порядке snapshot reader; writer не хранит список
строк. Закрытый data file принудительно сбрасывается на диск, после всех data
files записывается и fsync-ится `manifest.json`, а `_SUCCESS` с точным SHA-256
записанных manifest bytes создаётся последним. После этого fsync-ятся staging и
его parent directory.

`inspect` не доверяет одному наличию marker: повторно декодирует manifest,
сверяет `runId/profile/planHash`, запрещает symlink/лишние файлы и пересчитывает
SHA-256 каждого data file. Staging без marker, но с полностью валидной hash-chain,
имеет состояние `RECOVERABLE`; `recoverStaging` дописывает только `_SUCCESS` и не
перечитывает canonical storage.

`makeAvailable` выполняет единственный `ATOMIC_MOVE` staging-каталога в
`root/<profile>/<sliceName>/`. Copy fallback и неатомарный rename запрещены:
если filesystem не поддерживает atomic directory move, операция завершается
`EXPORT.ATOMIC_PUBLISH_UNSUPPORTED`, а staging остаётся для recovery.

## Slice retention

Retention adapter рассматривает только непосредственные каталоги
`root/<profile>/<slice>/`; `.staging` не входит в discovery. Каждый final-каталог
должен пройти полную проверку marker → manifest → data hashes. Incomplete или
corrupt final не становится кандидатом и завершает sweep ошибкой, чтобы
corruption была видима оператору. Перед recursive delete descriptor и hash-chain
проверяются повторно; symlink и подмена каталога запрещены.

## Инварианты и ошибки

- data CSV использует charset/delimiter/quote/null literal из immutable
  `ExportPlan`; record separator фиксирован как CRLF;
- manifest hash считается по фактически записанным bytes codec-а;
- final slice считается доступным только при валидных data/manifest/marker;
- одновременное наличие staging и final классифицируется как `CONFLICT`;
- I/O записи, invalid manifest/tree и отсутствие atomic move различаются кодами
  `SLICE_WRITE_FAILED`, `MANIFEST_INVALID`, `ATOMIC_PUBLISH_UNSUPPORTED`;
- `discardStaging` удаляет только derived staging path и никогда не затрагивает
  опубликованный каталог.

## Тесты

`CsvArtifactSliceWriterTest` фиксирует deterministic bytes/tree, порядок marker,
невидимость final до rename, corruption detection, forward recovery,
идемпотентную inspection/publication, unsupported atomic move и поток из 50 000
строк без накопления rows внутри writer.
`FileSystemSliceRetentionStoreTest` проверяет целостное удаление каталога,
изоляцию staging и отказ удалять incomplete/corrupt final.
