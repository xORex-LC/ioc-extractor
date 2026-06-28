# Каталог сервисов и карта

Вся чистая бизнес-логика выделена в **изолированные сервисы** за портами. Это
DDD-вектор: каждый сервис — самостоятельная единица ответственности, которую
можно поддерживать, тестировать, масштабировать и разрабатывать **параллельно и
независимо**, а позже вынести в отдельный модуль ([modularization.md](modularization.md))
без переписывания.

Принцип: **сервис общается с другими только через порты**, не зашивая
зависимости. Реализация заменяема; границы защищены проверками
([boundaries.md](boundaries.md)).

## Каталог

| Сервис | Ответственность | Порт(ы) | Вход → Выход | Зависит от | Слой | Целевой модуль |
|---|---|---|---|---|---|---|
| **Refanger** | деобфускация defanged-IOC | `Refanger` | text → text | refang-rules (config) | domain | `ioc-domain` |
| **IndicatorExtractor** | детекция индикаторов | `IndicatorExtractor`, `PatternEngine`(SPI) | text → RawIndicator[] | PatternEngine, patterns | domain | `ioc-domain` |
| **PatternEngine** | regex-движок | `PatternEngine` | regex → matches | RE2J / JDK | adapter | `adapter-regex-re2j` |
| **SourceAttributor** | атрибуция `source` по маркерам | `SourceAttributor` | text+raw → Indicator[] | markers (config) | domain | `ioc-domain` |
| **IndicatorNormalizer** | нормализация значения (пунктуация, регистр, host из URL) | `IndicatorNormalizer` | RawIndicator → норм. значение | — | domain | `ioc-domain` |
| **IndicatorFeatureExtractor** | признаки индикатора (`IndicatorFeatures`) | `IndicatorFeatureExtractor` | Indicator → IndicatorFeatures | `HostClassifier` | domain | `ioc-domain` |
| **HostClassifier** | вид хоста по PSL (registrable/subdomain) | `HostClassifier` | host → kind | Guava PSL | adapter | `adapter-psl` |
| **MatchClassifier** | коды масок (4-вар., rule-based) | `MatchPolicy` | IndicatorFeatures → MaskMatch | classify-rules | domain | `ioc-domain` |
| **Dedup** | within-run дедуп (`contains`) + keep-first в сторадже | `LookupRepository`; `ON CONFLICT(row_key) DO NOTHING` | Indicator[] → Indicator[] | LookupRepository, canonical store | application (stage) + adapter | `ioc-application`, `adapter-store-jdbc` |
| **ArtifactFiller** | заполнение колонок (provider/transform) | `RowMapper`, `ValueProvider`/`Transform` | Indicator → row | MatchPolicy, columns (config) | adapter | `adapter-sink-csv` |
| **SourceReader** | чтение источника (формат-агностик) | `SourceReader` | path → text | Tika | adapter | `adapter-source-tika` |
| **IocSink** | запись артефакта | `IocSink` | Indicator[] → canonical store / CSV | commons-csv или JDBC, ArtifactFiller | adapter | `adapter-sink-csv`, `adapter-store-jdbc` |
| **LookupRepository** | artifact-aware лукап/дедуп-стор для masks + hashes | `LookupRepository` | Indicator → bool, maxId | CSV/JDBC | adapter | `adapter-lookup-csv`, `adapter-store-jdbc` |
| **Diagnostics** | сбор/рендер диагностики, каталог | `DiagnosticSink`, `DiagnosticRenderer`, `MessageCatalog` | Diagnostic → msg/report | — | cross-cutting | `platform-diagnostics` |
| **Observability** | operational log events (ECS), MDC-корреляция | `MdcScope`/`LogEvent` | events → ECS-лог | SLF4J/Logback, ECS encoder | cross-cutting | `platform-observability` |
| **Diagnostics logging bridge** | запись диагностик в общий лог-поток | `LoggingDiagnosticSink` | Diagnostic → LogEvent | diagnostics + observability | cross-cutting | `platform-diagnostics-logging` |
| **Pipeline orchestrator** | сборка стадий + политика ошибок | `ExtractIocsUseCase` (in) | command → result | все домен-сервисы (через порты) | application | `ioc-application` |
| **Watch ingest** | приём источников: детект/стабилизация/клейм, вызов use case | `IngestSourceUseCase` (in); `SourceFeed` — adapter-local | dir → SourceUnit | Spring Integration | adapter (in) | `adapter-ingest` |
| **SourceLifecycle** | атомарный claim/archive/fail источника и sidecar ошибок | `SourceLifecycle` | path/status → move/sidecar | filesystem | adapter | `adapter-ingest` |
| **IngestionLedger** | журнал обработанного (идемпотентность, статусы, восстановление) | `IngestionLedger` | unit → status | file/SQLite | adapter | `adapter-ingest`, `adapter-store-jdbc` |
| **RunLedger** | per-file saga checkpoints для write→project recovery | `RunLedger` | run id/status → recovery | SQLite service storage | adapter | `adapter-store-jdbc` |
| **SourceSinkFactory** | per-source `JdbcIocSink`-и, пишущие прямо в canonical с `_source_key` | `SourceSinkFactory` | claimed source → IocSink[] | canonical store, IdGenerator | bootstrap | `ioc-app` |
| **ArtifactIdentityResolver** | единая формула `row_key` из output-значений (JSON-канон, явные null) | `ArtifactIdentityResolver` | artifact row → key | identity config | application | `ioc-application` |
| **ArtifactIdentityStore** | guard дрейфа формулы identity (`identity_hash`/`epoch`) | `ArtifactIdentityStore` | definition → stored marker/HALT | SQLite dataframe storage | adapter | `adapter-store-jdbc` |
| **CanonicalArtifactRepository** | чтение/атомарная запись canonical rows + `<artifact>_sources` | `CanonicalArtifactRepository` | artifact rows ↔ DB | Spring JDBC/sqlite | adapter | `adapter-store-jdbc` |
| **ArtifactProjection** | регенерация CSV-проекций из canonical store | `ArtifactProjection` | artifact names → `*_generated.csv` | commons-csv | adapter | `adapter-sink-csv` |
| **ExportService** | formation saga неизменяемого локального среза: single-flight, snapshot streaming, staging, content-aware `SKIPPED`, публикация и terminal progress | `ExportArtifactsUseCase`; export out-порты | export profile → completed/skipped slice | export plan, canonical snapshot, service ledger, operation guard | application | `ioc-application` |
| **ExportRunRecoveryService** | forward recovery активных `STARTED/STAGED/AVAILABLE` run по durable ledger и filesystem evidence без перечитывания mutable canonical truth | `RecoverExportUseCase`; `ExportRunLedger`, `ArtifactSliceWriter`, `ExportProgressStore` | active checkpoints → terminal run | export ledger, slice inspection, operation guard | application | `ioc-application` |
| **ExportChangeDetector** | чистая двухступенчатая policy изменения: revision/plan pre-gate и authoritative content-hash post-check | — (application policy) | revisions/progress/manifest → emit или skip | `ArtifactRevision`, `ExportProgress`, manifest coverage | application | `ioc-application` |
| **ArtifactSchemaFingerprint** | детерминированный `schemaHash` ordered public columns и normalized declared types для plan/snapshot compatibility | — (application policy) | ordered schema → SHA-256 fingerprint | JDK crypto | application | `ioc-application` |
| **SnapshotSliceReader** | единый consistent multi-artifact JDBC snapshot и потоковая передача metadata/rows без материализации dataframe в heap | `SnapshotSliceReader`, `SnapshotRowConsumer` | resolved plan → metadata + row callbacks | SQLite/JDBC canonical store | adapter | `adapter-store-jdbc` |
| **ArtifactSliceWriter** | deterministic CSV bytes, staging/final protocol, integrity inspection и atomic directory visibility | `ArtifactSliceWriter` | snapshot callbacks → staged/available slice | filesystem, CSV dialect, manifest codec | adapter | `adapter-sink-csv` |
| **SliceManifestCodec** | единственная versioned JSON serialization boundary manifest-а и integrity root среза | `SliceManifestCodec` | `SliceManifest` ↔ JSON bytes | Jackson | adapter | `adapter-manifest-json-jackson` |
| **Export durable state** | CAS state machine formation saga, global single-flight, revisions и terminal progress | `ExportRunLedger`, `ExportRunReader`, `ExportProgressStore`, `ArtifactRevisionReader` | run/progress/revision ↔ durable state | SQLite service/dataframe storage | adapter | `adapter-store-jdbc` |
| **SliceRetentionService** | profile-scoped age/count retention целых completed slice-каталогов с повторным integrity check и delivery-aware veto | `RunSliceRetentionUseCase`; `SliceRetentionStore`, `SliceRetentionGuard` | completed slices → deleted/pinned | `RetentionPolicy`, filesystem store, delivery guard | application | `ioc-application` |
| **CompletedSliceCatalog** | read-only worklist только integrity-valid completed slices; staging/incomplete исключаются, corruption не маскируется | `CompletedSliceCatalog` | export root/profile → verified slices | filesystem, manifest codec/hash-chain | adapter | `adapter-sink-csv` |
| **RemoteFetchService** | read-only remote discovery/filter/dedup и атомарный landing через скрытый staging в local inbox | `RemoteFetchUseCase`; `FileTransport`, `RemoteFetchLedger` | configured source → fetched/skipped/failed | transport, fetch ledger, inbox filesystem | application | `ioc-application` |
| **ArtifactPublishService** | reconcile verified slices × targets и независимая per-target publish saga с forward recovery remote marker-а | `ArtifactPublishUseCase`; `CompletedSliceCatalog`, `PublishLedger`, `FileTransport` | completed slices → per-target publish states | slice catalog, publish ledger, retrier | application | `ioc-application` |
| **Retrier** | transport-neutral micro-retry только для `RETRY_NOW`; `RETRY_LATER` оставляет следующему scheduler tick | `RetryPolicy`, `RemoteErrorKind`/`RemoteErrorDisposition` | remote operation → result/final failure | sleeper policy | application | `ioc-application` |
| **Remote sync ledgers** | durable idempotency fetch по `(path,size,mtime)` и CAS publish saga по `(slice_id,target_id)` | `RemoteFetchLedger`, `PublishLedger` | remote identity/slice-target → status | SQLite service storage | adapter | `adapter-store-jdbc` |
| **FileTransport / SMB** | transport-neutral list/stat/get/delete и atomic multi-file publish; SMB2/3 session/reconnect/marker-last остаются внутри адаптера | `FileTransport` | remote path/local files ↔ remote objects | smbj, SMB endpoint settings | adapter | `adapter-transport-smb` |
| **PublishLedgerSliceRetentionGuard** | запрещает local slice delete при missing/`PENDING`/`IN_PROGRESS`/`FAILED` pair любого configured target; terminal pair разрешает | `SliceRetentionGuard` | slice descriptor → allow/veto | configured targets, `PublishLedger` | application | `ioc-application` |
| **Export/sync daemon lifecycle** | cadence, startup reconcile/recovery, fixed-delay single-flight, phase ordering fetch→export→publish→retention и controlled shutdown | application use cases | scheduler ticks → isolated profile/source/target runs | Clock, config, Spring lifecycle | bootstrap | `ioc-app` |
| **Daemon health** | состояние ledger/storage/run recovery; экспонируется по HTTP (`/actuator/health`) только в daemon | `HealthIndicator` | runtime state → health | Spring Boot Actuator + web (daemon-only) | bootstrap | `ioc-app` |
| **RetentionService** | reaper: возраст/количество → delete/archive для `done`/`failed` | `RunRetentionUseCase`, `RetentionStore` | targets → reaped | `RetentionPolicy` | application | `ioc-application` |
| **RetentionStore** | листинг/удаление/архив записей (рекурсивно листовые файлы) | `RetentionStore` | dir → entries; delete/archive | filesystem | adapter | `adapter-ingest` |

Категории: **domain** — чистая логика; **adapter** — реализация порта на
технологии; **cross-cutting** — сквозная подсистема; **application** —
оркестрация. Подробности диагностики — [diagnostics.md](diagnostics.md),
классификации/заполнения — [output-mapping.md](output-mapping.md), извлечения —
[extraction.md](extraction.md), инжеста — [ingestion.md](ingestion.md), формирования
срезов — [dev/0012-streaming-dataframe-emission.md](dev/0012-streaming-dataframe-emission.md),
remote delivery — [sync.md](sync.md).

## Карта (поток данных и зависимости)

```
                         ┌───────────────── cross-cutting ──────────────────┐
                         │  Diagnostics (каталог + sinks + renderer)         │
                         └───────────▲───────────────────────▲──────────────┘
                                     │ Diagnostic(code,ctx)   │
  [source] ─SourceReader─▶ text ─Refanger─▶ text ─IndicatorExtractor─▶ raw[]
                                                   │(PatternEngine SPI)
        SourceAttributor ◀──────────────────────── ┘
            │ Indicator[]
            ▼
       Deduplicator ──(LookupRepository)──▶ Indicator[]
            │
            ▼
   IocSink ◀─ ArtifactFiller(MatchClassifier+HostClassifier(PSL), provider/transform) ─▶ [artifact]

  orchestration: ExtractIocsUseCase (application) собирает стадии и применяет FailurePolicy
  daemon: watch ─IngestSourceUseCase─▶ [тот же конвейер] ─▶ JDBC dataframe truth ─▶ CSV projection
          run-ledger: write→project checkpoints and startup recovery
          housekeeping: RetentionService⟳ (по возрасту/количеству) чистит done/failed

  artifact emission:
    JDBC truth ─SnapshotSliceReader─▶ ExportService ─ArtifactSliceWriter─▶ immutable slice
                     revisions/progress + export-run-ledger ◀──────┘          │
                    ExportRunRecoveryService ────────────────────────▶ recovery
                                                                             │
  remote sync: source ─RemoteFetchService─▶ inbox                 CompletedSliceCatalog
               (FileTransport + fetch-ledger)                              │
                                                    ArtifactPublishService─┘
                                                       │ FileTransport + publish-ledger
                                                       ▼
                                                 remote target(s)

  slice retention: SliceRetentionService ─▶ SliceRetentionGuard(publish-ledger) ─▶ delete/veto
```

Стрелки = направление данных; все межсервисные связи идут **через порты**
(стрелки не создают прямых зависимостей реализаций). Cross-cutting Diagnostics
подключается к любой стадии без связности.

## Правила для сервисов

- Один сервис — одна причина изменения (SRP); имя отражает способность.
- Общение — только через порты; никаких прямых ссылок реализация→реализация.
- Доменные сервисы — без фреймворков; технологии — в adapter-сервисах.
- Сервис module-ready: вынос в модуль не требует правки логики (готовит
  параллельную разработку и масштабирование).
- Правила/специфика — декларативны ([principles.md](principles.md)); код сервиса
  тонкий и переиспользуемый.
