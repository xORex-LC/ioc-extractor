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
| **Deduplicator** | дедуп within-batch + lookup | `Deduplicator`, `LookupRepository` | Indicator[] → Indicator[] | LookupRepository | domain | `ioc-domain` |
| **ArtifactFiller** | заполнение колонок (provider/transform) | `RowMapper`, `ValueProvider`/`Transform` | Indicator → row | MatchPolicy, columns (config) | adapter | `adapter-sink-csv` |
| **PartitionSinkFactory** *(stage 10)* | создание daemon-scoped CSV sinks для партиций по source-key/content-hash | `PartitionSinkFactory` | SourceUnit → IocSink[] | partition config, CSV mapping | adapter | `adapter-sink-csv` |
| **SourceReader** | чтение источника (формат-агностик) | `SourceReader` | path → text | Tika | adapter | `adapter-source-tika` |
| **IocSink** | запись артефакта | `IocSink` | Indicator[] → file | commons-csv, ArtifactFiller | adapter | `adapter-sink-csv` |
| **LookupRepository** | artifact-aware лукап/дедуп-стор для masks + hashes | `LookupRepository` | Indicator → bool, maxId | CSV | adapter | `adapter-lookup-csv` |
| **Diagnostics** | сбор/рендер диагностики, каталог | `DiagnosticSink`, `DiagnosticRenderer`, `MessageCatalog` | Diagnostic → msg/report | — | cross-cutting | `platform-diagnostics` |
| **Observability** | operational log events (ECS), MDC-корреляция | `MdcScope`/`LogEvent` | events → ECS-лог | SLF4J/Logback, ECS encoder | cross-cutting | `platform-observability` |
| **Diagnostics logging bridge** | запись диагностик в общий лог-поток | `LoggingDiagnosticSink` | Diagnostic → LogEvent | diagnostics + observability | cross-cutting | `platform-diagnostics-logging` |
| **Pipeline orchestrator** | сборка стадий + политика ошибок | `ExtractIocsUseCase` (in) | command → result | все домен-сервисы (через порты) | application | `ioc-application` |
| **Watch ingest** *(stage 10)* | приём источников: детект/стабилизация/клейм, вызов use case | `IngestSourceUseCase` (in); `SourceFeed` — adapter-local | dir → SourceUnit | Spring Integration | adapter (in) | `adapter-ingest` |
| **SourceLifecycle** *(stage 10)* | атомарный claim/archive/fail источника и sidecar ошибок | `SourceLifecycle` | path/status → move/sidecar | filesystem | adapter | `adapter-ingest` |
| **IngestionLedger** *(stage 10/11)* | журнал обработанного (идемпотентность, статусы, восстановление, `AGGREGATED`) | `IngestionLedger` | unit → status | file/SQLite | adapter | `adapter-ingest` |
| **AggregationService** *(stage 11)* | process manager: source records → partition read → canonical write → mark `AGGREGATED` | `AggregatePartitionsUseCase` | ready records → AggregationResult | aggregation ports | application | `ioc-application` |
| **ArtifactIdentityResolver** *(stage 11)* | artifact-specific stable row key extraction | `ArtifactIdentityResolver` | artifact row → key | artifact schema/config | adapter | `adapter-sink-csv` |
| **StableIdIndex** *(stage 11)* | stable id per artifact key | `StableIdIndex` | artifact+key → id | sidecar CSV (SQLite later) | adapter | `adapter-sink-csv` |
| **CanonicalArtifactRepository** *(stage 11)* | чтение/атомарная запись канонических CSV артефактов | `CanonicalArtifactRepository` | artifact rows ↔ CSV | commons-csv | adapter | `adapter-sink-csv` |
| **PartitionArtifactRepository** *(stage 11)* | чтение source-scoped partition CSV | `PartitionArtifactRepository` | ledger partitions → rows | commons-csv | adapter | `adapter-sink-csv` |
| **Daemon health** *(stage 11)* | состояние ledger/storage/aggregation для Actuator | `HealthIndicator` | runtime state → health | Spring Boot Actuator | bootstrap | `ioc-app` |
| **PartitionReaper** *(future, opt)* | очистка/архив партиций | `RetentionPolicy` | partitions → purge | — | application/adapter | `ioc-application` |

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
  daemon: watch ─IngestSourceUseCase─▶ [тот же конвейер] ─▶ партиции ─AggregationService─▶ артефакт
          future: PartitionReaper⟳ only after AGGREGATED + grace
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
