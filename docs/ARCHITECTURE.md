# Архитектура

Стиль — **Clean Hexagonal (порты и адаптеры) + Onion**. Приложение — мини-ETL
по извлечению индикаторов компрометации (IOC): читает документ, деобфусцирует
(refang), извлекает/нормализует индикаторы, классифицирует сетевые маски,
проставляет `source` из заголовков секций, дедуплицирует и пишет в выходные
артефакты.

## Правило зависимостей

Зависимости направлены **внутрь**:

```
bootstrap ─▶ adapters ─▶ application ─▶ domain
    │           │             └──────────────▶ platform/*
    │           └─────────────────────────────────▶ platform/*
    └─────────────────────────────────────────────▶ platform/*
```

- `platform` содержит переиспользуемые подсистемы: errors, diagnostics,
  generic ETL kernel, observability, control-event contracts, keyed concurrency
  primitives and diagnostics-logging bridge.
- `domain` не зависит от application/adapters/bootstrap/platform и не тянет
  фреймворки или IO-библиотеки.
- `application` зависит внутрь от `domain` и framework-free platform contracts
  для ETL, diagnostics, control events и keyed concurrency. Точный прямой
  Maven-граф принадлежит [POM модуля](../core/ioc-application/pom.xml), а не
  дублируется здесь; errors contract приходит транзитивно через platform DAG.
- Технологии parsing/storage/transport/CLI и Spring wiring живут в adapters и
  bootstrap (Tika, RE2/J, commons-csv, picocli, JDBC, SMBJ). Platform-модуль
  observability осознанно владеет общей SLF4J API boundary, не бизнес-логикой.
- Внутренние слои **никогда** не импортируют из внешних.

## Слои и пакеты

| Слой | Пакет | Содержимое |
|---|---|---|
| Domain | `domain/model` | `Indicator`, `IndicatorType`, `IndicatorCategory`, `SourceContext`, `MaskMatch` |
| Domain | `domain/refang` | `Refanger` (порт) + `ReplacementRefanger`, `RefangRule` |
| Domain | `domain/extract` | `PatternEngine` (порт), `IndicatorExtractor` (порт) + `RegexIndicatorExtractor`, `Span`, `RawIndicator` |
| Domain | `domain/feature` | Normalization and feature extraction (`IndicatorFeatures`, `HostClassifier`) |
| Domain | `domain/classify` | `MatchPolicy`, rule-based classification |
| Domain | `domain/attribute` | `SourceAttributor` (порт) + `MarkerSourceAttributor` |
| Platform | `platform/etl` | `Envelope`, `EnvelopeMeta`, `Stage`, `StageId`, `Pipeline`, `PipelineRunner` |
| Platform | `diagnostics` | Diagnostic model, catalog, `Result`/`Notification`, `FailurePolicy`, sink ports |
| Platform | `observability` | MDC/log event helpers and logging taxonomy |
| Platform | `events` | Framework-free control-event marker, metadata envelope, publish port and observers; no broker/delivery mechanics |
| Platform | `concurrent` | In-memory keyed single-flight executor, observer hooks and health snapshots |
| Application | `application/port/in` | `ExtractIocsUseCase`, `ExtractionCommand`, `ExtractionResult` (driving) |
| Application | `application/port/out` | `SourceReader`, `ArtifactPreparer`, canonical artifact/id-baseline ports, ingestion/export/sync ports, `RetentionStore` (driven) |
| Application | `application/pipeline/payload` | IOC-specific payload records between stages |
| Application | `application/pipeline/stage` | IOC ETL stage implementations |
| Application | `application/service` | `IocExtractionService` — use-case orchestrator |
| Application | `application/ingest` | daemon ingestion orchestration (`IngestionService`) |
| Application | `application/artifact` | storage-neutral artifact row identity, canonical artifact snapshots and ingest run recovery model |
| Application | `application/maintenance` | retention reaper: `RetentionPolicy` (pure) + `RetentionService` |
| Application | `application/sync`, `application/port/*/sync` | Transport-neutral fetch/publish use cases, remote source monitor, control events, retry/error policy и delivery ledgers/catalog ports |
| Adapter (in) | `adapter/in/cli` | `IocRootCommand`, `ExtractCommand`, `CliRunner` (picocli) |
| Adapter (out) | `adapter/out/regex` | `Re2jPatternEngine` (default), `JdkRegexPatternEngine` |
| Adapter (out) | `adapter/out/source` | `TikaSourceReader` |
| Adapter (out) | `adapter/out/sink/csv` | `CsvArtifactPreparer`, `RowMapper` + мапперы, `CsvArtifactProjection`, export slice writers |
| Adapter (out) | `adapter/out/store/jdbc` | `JdbcCanonicalArtifactRepository`, `JdbcArtifactIdBaseline`, ledgers, migrations, health |
| Adapter (in) | `adapter/in/ingest` | Spring Integration file-poll daemon, filesystem lifecycle, file ledger |
| Adapter (out) | `adapter/out/maintenance` | `FileSystemRetentionStore` (reaper IO; в модуле `adapter-ingest`) |
| Adapter (out) | `adapter/out/transport/smb` | SMB2/3 `FileTransport` на smbj; session/reconnect/atomic publish внутри адаптера |
| Bootstrap | `bootstrap` | `IocProperties` (конфиг), `AppConfig` (composition root), daemon schedulers, `DaemonWebEnvironmentPostProcessor` (web only in daemon), health indicators |
| Platform | `common` | `IocExtractorException` (`ioc-platform-errors`) |

## Конвейер обработки

Выражен через generic `platform-etl` contracts и IOC-specific application
stages:

```
read (SourceReader)
  → refang (Refanger)
  → extract (IndicatorExtractor / PatternEngine)
  → attribute source (SourceAttributor)
  → de-duplicate (within-batch)
  → classify NETWORK (один materialized ClassificationDecision на retained IOC)
  → prepare rows (ArtifactPreparer, без IO и финальных id)
  → failure-policy checkpoint
  → commit canonical rows → project derived CSV
```

Подход к конвейеру (Pipes-and-Filters + `Envelope`/diagnostics) —
[processing.md](dev/processing.md).

Конвейер — цепочка независимых стадий: новую стадию/реализацию добавляем, не
трогая остальные (OCP). Маршрутизация по типу индикатора и декларативным
`include`/`exclude`-фильтрам позволяет одному прогону наполнять несколько
артефактов: сетевые маски, bare-IP list, address blacklist и файловые хэши.

## Порты (контракты)

| Порт | Тип | Назначение |
|---|---|---|
| `ExtractIocsUseCase` | driving (in) | Единая точка входа прикладного ядра |
| `SourceReader` | driven (out) | Формат-независимая граница извлечения текста из документа |
| `ArtifactPreparer` | driven (out) | Side-effect-free routing/mapping одного артефакта до policy checkpoint |
| `PipelineDecisionTracer` | driven (out) | Gated TRACE уже вычисленных per-item outcomes без logging dependency в application/domain |
| `ArtifactIdBaseline` | driven (out) | Чтение текущего public `max(id)` из canonical storage для продолжения id-последовательностей |
| `CanonicalArtifactRepository` / `ArtifactProjection` | driven (out) | Canonical write/read с provenance и генерация CSV-проекций |
| `PatternEngine` | domain SPI | Движок regex (RE2/J по умолчанию, JDK — замена) |
| `IngestSourceUseCase` | driving (in) | Обработка одного daemon source unit |
| `RunRetentionUseCase` / `RetentionStore` | driving (in) / driven (out) | Reaper растущих каталогов по возрасту/количеству (delete/archive) |
| `RemoteFetchUseCase` / `ArtifactPublishUseCase` | driving (in) | Remote → inbox и verified export slice → targets |
| `ControlEventPublisher` | platform driving port | Publish-only control-plane notification; delivery adapter живёт в bootstrap |
| `FileTransport` | driven (out) | Stateless remote file operations + atomic multi-file publish intent |
| `RemoteFetchLedger` / `PublishLedger` | driven (out) | Fetch idempotency и независимая per-slice/per-target delivery saga |

Pipeline возвращает `ExtractionResult` с `CompletionStatus`, bounded diagnostics и
`DiagnosticSummary`. `fail-fast`/`collect-and-continue` применяются до canonical
commit после preparation stage. Saga-контуры ingest/export/sync остаются
ledger-first: diagnostic наблюдает final transition, но не заменяет его.

Remote sync не меняет extraction pipeline: fetch заканчивается в штатном inbox, а
publish начинается только после локального export `_SUCCESS`. Подробный protocol и
операторская модель — [sync.md](dev/sync.md).

## Классификация сетевых масок

`MatchPolicy` (домен) определяет коды `url_match`/`host_match` по варианту
совпадения значения с маской. Решение (какой вариант) — доменная логика,
заменяемая реализацией; сами коды и их колонки — конфигурируемы.

| # | url_match | host_match | Когда |
|---|---|---|---|
| 1 | `u:hAS` | `h:dAS` | Регистрируемый домен (или IP): домен + все поддомены и содержимое |
| 2 | `u:hEX` | `h:dEX` | Поддомен (точный хост 3-го уровня) |
| 3 | `u:hEX,dEX` | `null` | Хост с путём/файлом или портом, без вложенных папок |
| 4 | `u:hAS,pEX` | `null` | URL с параметрами (query) |

Разграничение «регистрируемый домен vs поддомен» (вариант 1 vs 2) — по
**Public Suffix List** (Guava `InternetDomainName`), что корректно для
многосоставных суффиксов (`com.br`, `co.uk`, `workers.dev`). Триггеры и
открытые случаи — в [dev/0002](ADR/0002-output-mapping-and-matching.md).

Политика — **rule-based и декларативная**: тонкий вычислитель + реестр
предикатов над признаками индикатора; сами правила и коды задаются конфигом
(не зашиты в код). Модель — в
[processing.md](dev/processing.md).

> Ручной эталон использует только варианты 1 и 3; варианты 2 и 4 — по
> авторитетному правилу, поэтому вывод на поддоменах/параметрах закономерно
> расходится с ручной заливкой.

## Артефакты и заполнение

Колонки и правила заполнения артефактов **декларативны в конфиге**, не в коде
(provider/transform-модель). Детали — [processing.md](dev/processing.md).

Текущие артефакты:

| Артефакт | Содержимое | Id-space |
|---|---|---|
| `masks` | сетевые IOC кроме голых IP: домены, URL, IP с port/path | свой, baseline из canonical SQLite `max(id)` |
| `ip_list` | только голые IPv4 | свой, baseline из canonical SQLite `max(id)` |
| `address_blacklist` | простой список `forbidden_url` / `forbidden_ip`; без id | нет id |
| `hashes` | MD5/SHA1/SHA256 по разным колонкам | свой, baseline из canonical SQLite `max(id)` |

**Словарь колонок (masks):**

| Колонка | Назначение |
|---|---|
| `id` | идентификатор записи (ascending, продолжается от max в canonical storage) |
| `mask` | маска: адрес (URL) или имя домена (FQDN) |
| `url_match` | вариант совпадения адреса (URL) с маской |
| `host_match` | вариант совпадения имени домена с маской |
| `score` | значение интегральной уязвимости |
| `time_last_seen` | дата последнего изменения информации об артефакте |
| `time_first_seen` | дата появления информации об артефакте |
| `threat_type` | тип угрозы |
| `source` | источник перечня IoC |
| `description` | дополнительная информация о записи |

В текущих эталонах `score`/`time_*`/`threat_type`/`description` всегда `NULL`
(обогащение — опционально, на будущее).

### Canonical record lifecycle

Fixed TTL является свойством одной lifecycle canonical DB-записи, а не IOC type
или source provenance. Успешная canonical transaction атомарно создаёт либо
продлевает lifecycle с абсолютным UTC `valid_until`; подтверждение после
deadline архивирует прежнюю lifecycle и создаёт новую с новым service-owned ID.
Все active reads используют half-open predicate `valid_until > asOf`.

Expiry обслуживается aggregate nearest-deadline scheduler: event hint и
five-second read-only backstop обновляют один timer из deadline index, а только
due timer запускает bounded SQLite batches. Последний реальный reconcile
хранится в constant-cardinality checkpoint; typed history удаляется independent
hourly scheduler-ом, а mutable CSV сходится через durable projection generation.
Timer/job на каждую IOC отсутствует. Expiry не меняет insert-driven `artifact_revision`, поэтому сам
по себе не формирует immutable export slice; следующий обычный new-row trigger
читает уже актуальный active snapshot. Lifecycle SQL/history остаются в
`adapter-store-jdbc`, storage-neutral policy/use cases — в `ioc-application`, а
clock/config/scheduler/health — в bootstrap. Подробности:
[dev/canonical-record-lifecycle.md](dev/canonical-record-lifecycle.md).

**Кодировки I/O.** Вход декодируется по `ioc.source.charset` (`auto` = детект Tika/ICU;
явное имя форсит text/HTML, docx/pdf — по дизайну нет); внутри — Unicode `String`.
Выход всех CSV-проекций и export-срезов — в `ioc.sink.csv.charset`;
непредставимые символы заменяются с WARN-сигналом, неизвестное имя кодировки —
fail-fast. Детали — [processing.md](dev/processing.md).

**Форматы источников.** Контрактными тестами адаптера закреплены HTML (включая
legacy `cp1251` при явном charset), PDF, DOCX и XLSX. Другие форматы, которые
распознаёт установленный набор Tika parsers, обрабатываются best-effort и не
считаются поддерживаемым release-контрактом, пока не добавлены в corpus/contract
tests.

## Immutable artifact export

Canonical SQLite остаётся системой записи, а export — отдельным bounded context
**Artifact Emission**. Он не копирует мутабельные `*_generated.csv`, а потоково
читает один consistent multi-artifact snapshot и формирует неделимый immutable
срез profile:

```
artifact_revision + ExportProgress ──▶ cheap change gate
canonical SQLite (one WAL read tx) ──▶ CSV files ──▶ manifest.json ──▶ _SUCCESS
                                                staging ──ATOMIC_MOVE──▶ final
```

- `ioc export --profile <name>` запускает тот же `ExportService`, что и daemon
  scheduler; v1 выполняет только `complete`, `append` отклоняется до IO.
- `export_run` в service SQLite хранит CAS-сагу
  `STARTED → STAGED → AVAILABLE → COMPLETED`; `SKIPPED`/`FAILED` terminal.
  Partial unique index обеспечивает global single-flight.
- `artifact_revision` увеличивается в транзакции фактической canonical-вставки.
  Snapshot metadata (`revision`, `changed_at`, `upper_id`) читается в той же
  read transaction, что и строки. Concurrent commit попадает в следующий срез.
  Byte-identical candidate с более новой covered revision всё равно становится
  новым completed slice: новая lifecycle является новой delivery occurrence, а
  content hash не заменяет revision/business-occurrence identity.
- Writer не материализует rows: JDBC callback-stream идёт прямо в CSV digest.
  `_SUCCESS` содержит SHA-256 точных bytes manifest; manifest содержит hashes и
  coverage всех data files. Final становится видимым одним atomic directory move.
- Startup recovery продвигается только вперёд по ledger + filesystem evidence и
  никогда не перечитывает mutable canonical snapshot.
- Daemon поддерживает `interval` и `quiet-period` с обязательным `max-cap`.
  Export health показывает последний success/failure, возраст среза и revision lag.
- Slice retention ранжирует завершённые каталоги отдельно по profile и удаляет
  каталог целиком. Guard проверяется непосредственно перед delete; standalone
  разрешает удаление, а `PublishLedgerSliceRetentionGuard` pin-ит недоставленные
  срезы при включённом remote publish.

Полный протокол, crash-матрица и rationale: [dev/0012](ADR/0012-streaming-dataframe-emission.md).

## Composition root

Модуль `bootstrap/ioc-app` — composition root: здесь Spring связывает
агностичное ядро с конкретными адаптерами. Основной extraction/storage graph
собирает `AppConfig`; remote sync и control-event wiring вынесены в `SyncConfig`
и `EventCoordinationConfig`, а startup config boundary — в
`ConfigPreflightConfiguration`. `IocProperties` задаёт типобезопасную привязку
дерева конфигурации `ioc.*`.

## Связанные карты

- Многомодульная структура и правила зависимостей —
  [modularization.md](MODULARIZATION.md).
- Диагностика, failure policy и structured logging —
  [observability.md](dev/observability.md).
- Автоматическая защита границ — [boundaries.md](BOUNDARIES.md).
