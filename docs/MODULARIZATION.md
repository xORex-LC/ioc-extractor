# Многомодульность

Проект переведён в **Maven-реактор**: агностичные platform-подсистемы, core,
adapters и bootstrap собираются как отдельные Maven-модули с явными
зависимостями.

> Статус: **реализовано на этапе 9**. Фактическая структура ниже. Границы
> модулей подкрепляются Maven-зависимостями, Maven Enforcer и ArchUnit
> ([boundaries.md](BOUNDARIES.md)).

## Зачем

- **Защита границ компиляцией.** Если `domain` — отдельный модуль без зависимости
  на Spring, нарушить правило «domain framework-free» становится физически
  нельзя: класс просто не найдётся.
- **Выделяемость.** Агностичные подсистемы (диагностика, ETL, control events,
  keyed concurrency и observability) можно переиспользовать в других
  приложениях/сервисах без копипасты.
- **Управляемость зависимостей.** Каждый модуль декларирует ровно то, что ему
  нужно; направление зависимостей видно в `pom.xml`, а не «на доверии».
- **Параллельная сборка и изоляция тестов** по модулям.

## Фактическая структура реактора

```
ioc-extractor/                     (parent pom: <packaging>pom</packaging>, <modules>)
├── platform/                      ← агностичные, переиспользуемые подсистемы
│   ├── platform-errors            (базовые ошибки/common-типы)
│   ├── platform-diagnostics       (диагностика: catalog, result/policy, sinks/renderer)
│   ├── platform-etl               (generic Envelope/Stage/Pipeline/PipelineRunner)
│   ├── platform-events            (framework-free control-event contracts)
│   ├── platform-concurrency       (keyed single-flight primitives)
│   ├── platform-observability     (MdcScope, LogEvent, taxonomy, PipelineObserver impl)
│   └── platform-diagnostics-logging (bridge: DiagnosticSink → LogEvent)
├── core/
│   ├── ioc-domain                 (единый IOC bounded context; capability = пакеты + ArchUnit-DAG)
│   ├── ioc-application            (порты in/out + use cases + IOC ETL stages)
│   └── ioc-application-tck        (переиспользуемые contract tests driven-портов)
├── adapters/
│   ├── adapter-regex-re2j         (PatternEngine → RE2J/JDK fallback)
│   ├── adapter-source-tika        (SourceReader → Tika)
│   ├── adapter-sink-csv           (ArtifactPreparer + CSV projection/export slices → commons-csv)
│   ├── adapter-manifest-json-jackson (SliceManifestCodec → Jackson)
│   ├── adapter-store-jdbc         (service/dataframe storage → Spring JDBC + sqlite-jdbc)
│   ├── adapter-transport-smb      (FileTransport → smbj)
│   ├── adapter-psl                (HostClassifier → Guava PSL)
│   ├── adapter-ingest             (daemon file ingest → Spring Integration)
│   └── adapter-cli-picocli        (входной адаптер CLI)
├── bootstrap/
│   └── ioc-app                    (Spring Boot, composition root, исполняемый jar)
└── build-support/
    └── coverage-report            (build-only JaCoCo aggregate; не runtime/library)
```

> ArtifactId имеют префикс `ioc-*`, например `ioc-platform-etl`,
> `ioc-application`, `ioc-adapter-sink-csv`, `ioc-app`.

### Направление зависимостей между модулями

```
ioc-app ─▶ adapters/* ─▶ ioc-application ─▶ ioc-domain
   │              │             │              └────────────▶ platform/*
   │              │             └───────────────────────────▶ platform/*
   │              └─────────────────────────────────────────▶ platform/*
   └────────────────────────────────────────────────────────▶ platform/*
```

- `platform/*` образуют собственный направленный DAG и не тянут
  application/domain/adapters: events, concurrency и errors автономны;
  diagnostics зависит от errors; ETL — от diagnostics/errors; observability —
  от ETL; diagnostics-logging bridge — от diagnostics/observability.
- `platform-etl` зависит только на diagnostics/errors и не знает про IOC domain.
- `ioc-domain` не зависит на application/adapters/bootstrap/platform-etl и
  остаётся framework-free.
- `ioc-application` зависит на `ioc-domain`, `platform-etl`, `platform-events`,
  diagnostics и errors contracts.
- Driving и infrastructure adapters зависят на нужный внутренний контракт:
  большинство — на `ioc-application`, а domain SPI adapters (`adapter-regex-re2j`,
  `adapter-psl`) — непосредственно на `ioc-domain`. Каждый адаптер подключает
  только свою технологическую библиотеку/integration family.
- `ioc-application-tck` содержит test-scope contract tests; реализации портов
  подключают его только в тестовом scope.
- `ioc-app` (bootstrap) зависит на всё и собирает исполняемый артефакт.
- `coverage-report` зависит на все production-модули только для формирования
  полного JaCoCo aggregate после их сборки. Он не входит в runtime-архитектуру,
  не публикуется как библиотека и не может быть зависимостью production-кода.

## Принципы нарезки на модули

1. **Один модуль — одна выделяемая ответственность** (подсистема/слой), с явной
   ролью и API.
2. **Агностичность внутрь.** Чем «глубже» модуль (ближе к platform/domain), тем
   меньше у него зависимостей; технологическая специфика — наружу, в адаптеры.
3. **Зависимости только вниз/внутрь.** Реактор + проверки запрещают обратные и
   циклические связи.
4. **Версии — централизованно** через root parent `dependencyManagement`; модули
   не дублируют версии.

## Карта ответственности по модулям

Ответственности распределены по модулям так:

| Модуль | Ответственности |
|---|---|
| `platform-diagnostics` | Diagnostics (модель, каталог, порты, sinks/renderer); может зависеть на `platform-errors` для `DiagnosticException` |
| `platform-etl` | Generic ETL kernel: `Envelope`, `Stage`, `Pipeline`, `PipelineRunner`, `PipelineObserver` |
| `platform-events` | Framework-free publish-only control-event contracts и observers; без broker/durable delivery mechanics |
| `platform-concurrency` | Keyed single-flight execution, bounded admission и health snapshots |
| `platform-observability` | Observability/logging: MdcScope, LogEvent, logging taxonomy, `LoggingPipelineObserver` |
| `platform-diagnostics-logging` | Bridge `DiagnosticSink` → LogEvent/SLF4J (`LoggingDiagnosticSink`); зависит на `platform-diagnostics` + `platform-observability` |
| `platform-errors` | базовые ошибки/common-типы и трансляция; нижний слой для `DiagnosticException` |
| `ioc-domain` | Refanger, IndicatorExtractor, SourceAttributor, MatchPolicy, модели, feature extraction |
| `ioc-application` | Pipeline/ingest use cases; framework-free Artifact Emission и Remote Sync models, retry/cadence, formation/delivery sagas, ports и policies |
| `ioc-application-tck` | Переиспользуемые JUnit contract tests application ports, исполняемые каждой реализацией |
| `adapter-regex-re2j` | PatternEngine implementation (RE2J + JDK fallback) |
| `adapter-source-tika` | SourceReader (Tika) |
| `adapter-sink-csv` | Artifact mapping, canonical CSV projection, callback-streaming immutable slices, integrity verification, atomic local publish и directory-level slice retention |
| `adapter-manifest-json-jackson` | Deterministic versioned JSON codec for immutable slice manifests |
| `adapter-store-jdbc` | Service/dataframe SQLite: canonical/revision storage, schema-aware id baseline, strict snapshot reader, ingest/export/fetch/publish ledgers + progress, migrations и health |
| `adapter-transport-smb` | smbj boundary: lazy SMB2/3 sessions, streaming get и atomic slice publish за `FileTransport` |
| `adapter-psl` | HostClassifier (PSL/Guava) |
| `adapter-ingest` | Watch ingest: `IngestSourceUseCase`(in), `SourceLifecycle`, file `IngestionLedger`; SourceFeed adapter-local (Spring Integration); `FileSystemRetentionStore` (reaper IO) |
| `adapter-cli-picocli` | входной CLI: `extract`, lazy `export`, `sync fetch|publish|all`, remote daemon `health` |
| `ioc-app` (bootstrap) | composition root, lazy export/sync graphs, transport registry; fetch/export/publish/retention schedulers, conditional web и health |
| `coverage-report` (build-support) | Непубликуемый Maven report-модуль: полный JaCoCo HTML/XML aggregate для production bytecode reactor |

## Гранулярность

Решение: **средняя гранулярность**.

- `platform-*` и `adapter-*` вынесены в отдельные артефакты, потому что у них
  независимые роли и/или внешние зависимости.
- `ioc-domain` оставлен единым Maven-модулем: это один bounded context с общим
  языком IOC. Capability (`model/refang/extract/feature/classify/attribute`)
  разделены пакетами и защищены внутридоменным ArchUnit-DAG.
- Первым кандидатом на будущий вынос из domain остаётся `refang`, если появится
  реальное переиспользование вне этого приложения.

Подробное решение: [dev/0009](ADR/0009-modularization-granularity.md).

Artifact Emission не образует новый Maven-модуль: его framework-free модель и
ports принадлежат `ioc-application`; технологические границы уже разнесены по
JDBC, CSV/filesystem и Jackson adapters. ArchUnit отдельно запрещает JDBC/Spring
во внутренних слоях, Jackson вне manifest adapter/bootstrap и JDBC-зависимость
у callback slice writer.

## Поэтапный переход

Этап 9 выполнен инкрементально:

1. parent reactor + `platform-etl`/`StageId`;
2. platform modules;
3. core modules;
4. adapter modules;
5. Enforcer + ArchUnit guardrails + CI reactor build.

Дальнейшее дробление domain capability — только по критерию независимого
жизненного цикла/переиспользования.

## Отложенный долг

Не входит в реализацию этапа 9:

- Spring Modulith/canvas для дополнительной верхнеуровневой визуализации модулей;
- `dependencyConvergence` и более жёсткие Maven Enforcer build-hygiene правила;
- JPMS `module-info.java`.

Эти проверки можно добавить отдельным техническим шагом после стабилизации
reactor-структуры. Базовая защита границ уже обеспечена Maven module graph,
текущими Enforcer rules и ArchUnit.
