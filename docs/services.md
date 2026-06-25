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
| **Daemon health** | состояние ledger/storage/run recovery; экспонируется по HTTP (`/actuator/health`) только в daemon | `HealthIndicator` | runtime state → health | Spring Boot Actuator + web (daemon-only) | bootstrap | `ioc-app` |
| **RetentionService** | reaper: возраст/количество → delete/archive для `done`/`failed` | `RunRetentionUseCase`, `RetentionStore` | targets → reaped | `RetentionPolicy` | application | `ioc-application` |
| **RetentionStore** | листинг/удаление/архив записей (рекурсивно листовые файлы) | `RetentionStore` | dir → entries; delete/archive | filesystem | adapter | `adapter-ingest` |

Категории: **domain** — чистая логика; **adapter** — реализация порта на
технологии; **cross-cutting** — сквозная подсистема; **application** —
оркестрация. Подробности диагностики — [diagnostics.md](diagnostics.md),
классификации/заполнения — [output-mapping.md](output-mapping.md), извлечения —
[extraction.md](extraction.md), инжеста — [ingestion.md](ingestion.md).

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
