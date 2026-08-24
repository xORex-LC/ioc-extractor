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
│   ├── adapter-csv                (strict CSV parsing + ArtifactPreparer/projection/export → commons-csv)
│   ├── adapter-manifest-json-jackson (SliceManifestCodec → Jackson)
│   ├── adapter-store-jdbc         (service/dataframe storage → Spring JDBC + sqlite-jdbc)
│   ├── adapter-transport-smb      (FileTransport → smbj)
│   ├── adapter-psl                (HostClassifier → Guava PSL)
│   ├── adapter-ingest             (daemon file ingest → Spring Integration)
│   └── adapter-cli-picocli        (входной адаптер CLI)
├── bootstrap/
│   └── ioc-app                    (Spring Boot, composition root, исполняемый jar)
└── build-support/
    ├── build-quality              (JDK-only verifier/tests; не reactor module)
    ├── coverage-report            (build-only JaCoCo aggregate; не runtime/library)
    ├── spotbugs-report            (build-only SpotBugs aggregate + report integrity; не runtime/library)
    ├── cpd-report                 (build-only repository-wide PMD CPD report + integrity; не runtime/library)
    └── pmd-report                 (build-only PMD source policy/watchlist + integrity; не runtime/library)
```

> ArtifactId имеют префикс `ioc-*`, например `ioc-platform-etl`,
> `ioc-application`, `ioc-adapter-csv`, `ioc-app`.

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
- `ioc-application` зависит внутрь от `ioc-domain` и framework-free platform
  contracts для ETL, diagnostics, control events и keyed concurrency. Точный
  прямой Maven-граф принадлежит [POM модуля](../core/ioc-application/pom.xml),
  а не дублируется здесь; errors contract доступен транзитивно через platform DAG.
- Driving и infrastructure adapters зависят на нужный внутренний контракт:
  большинство — на `ioc-application`, а domain SPI adapters (`adapter-regex-re2j`,
  `adapter-psl`) — непосредственно на `ioc-domain`. Каждый адаптер подключает
  только свою технологическую библиотеку/integration family.
- `ioc-application-tck` содержит test-scope contract tests; реализации портов
  подключают его только в тестовом scope.
- `ioc-app` (bootstrap) зависит на всё и собирает исполняемый артефакт.
- `build-quality` не является Maven-модулем: root `validate` компилирует его
  JDK-only verifier и synthetic-reactor contract harness напрямую.
- `coverage-report` зависит на все production-модули только для формирования
  полного JaCoCo aggregate.
- `spotbugs-report` зависит на те же 19 production-модулей для reactor ordering,
  формирует общий SpotBugs XML/HTML и проверяет наличие всех module/aggregate
  reports.
- `cpd-report` зависит на 19 production-модулей только для reactor ordering и
  анализирует единым PMD CPD execution явный allowlist их `src/main/java`;
  fail-closed registry сверяет reactor, ordering dependencies, source roots и
  итоговый XML source universe.
- `pmd-report` зависит на те же 19 production-модулей только для reactor
  ordering и type resolution. Поимённая 22-rule policy выполняется
  отдельным regular CI job, а 3-rule ownership/size watchlist остаётся
  локально opt-in. Оба профиля анализируют явный allowlist
  `src/main/java`; fail-closed registry проверяет reactor, source roots,
  engine/ruleset contract и итоговые XML/HTML. Обычный `make verify`
  не активирует ни один из PMD source профилей.
  Все четыре build-support модуля не входят в runtime-архитектуру, не публикуются
  как библиотеки и не могут быть зависимостями production-кода.

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
| `ioc-application` | Pipeline/ingest use cases; framework-free Artifact Emission, Remote Sync, canonical lifecycle и managed dataframe-import contracts/models/ports, exact recognition, tri-state mapping, staging orchestration, retry/cadence, sagas и policies |
| `ioc-application-tck` | Переиспользуемые JUnit contract tests application ports, включая import delivery ledger и canonical promotion, исполняемые каждой реализацией |
| `adapter-regex-re2j` | PatternEngine implementation (RE2J + JDK fallback) |
| `adapter-source-tika` | SourceReader (Tika) |
| `adapter-csv` | Strict delimited-row parsing, import transforms/processed-row preparation, artifact mapping, cursor-streamed canonical CSV projection, callback-streaming immutable slices, integrity verification, atomic local publish и directory-level slice retention |
| `adapter-manifest-json-jackson` | Deterministic versioned JSON codec for immutable slice manifests |
| `adapter-store-jdbc` | Service/dataframe SQLite: canonical/revision/lifecycle storage, typed history/receipts, reusable export-slot registry, bounded reconcile checkpoint, strict active snapshot reader, ingest/export/fetch/publish/import ledgers, private sealed import staging, migrations и health |
| `adapter-transport-smb` | smbj boundary: shared lazy SMB2/3 sessions, streaming get, atomic slice publish, server-side managed-import claim/materialization/disposition и `CHANGE_NOTIFY` doorbells за application ports |
| `adapter-psl` | HostClassifier (PSL/Guava) |
| `adapter-ingest` | Watch ingest: `IngestSourceUseCase`(in), `SourceLifecycle`, file `IngestionLedger`; SourceFeed adapter-local (Spring Integration); local managed-import claim/snapshot/disposition; `FileSystemRetentionStore` (reaper IO) |
| `adapter-cli-picocli` | входной CLI: `extract`, lazy `export`, `sync fetch|publish|all`, remote daemon `health` |
| `ioc-app` (bootstrap) | composition root, lazy export/sync graphs, transport registry; fetch/export/publish/retention/lifecycle/import schedulers, strict lifecycle/import config compilation, local/SMB import routing, conditional web и health |
| `build-quality` (build-support tooling) | Общий JDK-only fail-fast scope/report verifier и synthetic-reactor contract matrix; не является Maven reactor project |
| `coverage-report` (build-support) | Непубликуемый Maven report-модуль: полный JaCoCo HTML/XML aggregate |
| `spotbugs-report` (build-support) | Непубликуемый Maven report-модуль: полный SpotBugs XML/HTML aggregate и report-integrity gate для production bytecode reactor |
| `cpd-report` (build-support) | Непубликуемый Maven report-модуль: repository-wide PMD CPD XML/HTML и report-integrity gate для production Java sources |
| `pmd-report` (build-support) | Непубликуемый Maven report-модуль: regular report-only PMD source policy, локально opt-in watchlist и fail-closed scope/ruleset/report integrity для production Java sources |

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

Canonical record lifecycle также не образует новый Maven-модуль или shared
library: это application capability внутри текущего bounded context, а его
SQLite и Spring runtime mechanics уже принадлежат существующим JDBC adapter и
composition root. Выделение оправдано только при появлении второго реального
consumer с независимым release/dependency lifecycle; текущая package/port
граница обеспечивает extension без нового artifact graph.

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
