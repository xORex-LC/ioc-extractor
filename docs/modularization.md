# Многомодульность

Проект переведён в **Maven-реактор**: агностичные platform-подсистемы, core,
adapters и bootstrap собираются как отдельные Maven-модули с явными
зависимостями.

> Статус: **реализовано на этапе 9**. Фактическая структура ниже. Границы
> модулей подкрепляются Maven-зависимостями, Maven Enforcer и ArchUnit
> ([boundaries.md](boundaries.md)).

## Зачем

- **Защита границ компиляцией.** Если `domain` — отдельный модуль без зависимости
  на Spring, нарушить правило «domain framework-free» становится физически
  нельзя: класс просто не найдётся.
- **Выделяемость.** Агностичные подсистемы (логирование, ошибки, конфиг, движок
  паттернов) можно переиспользовать в других приложениях/сервисах без копипасты.
- **Управляемость зависимостей.** Каждый модуль декларирует ровно то, что ему
  нужно; направление зависимостей видно в `pom.xml`, а не «на доверии».
- **Параллельная сборка и изоляция тестов** по модулям.

## Целевая структура реактора

```
ioc-extractor/                     (parent pom: <packaging>pom</packaging>, <modules>)
├── platform/                      ← агностичные, переиспользуемые подсистемы
│   ├── platform-errors            (базовые ошибки/common-типы)
│   ├── platform-diagnostics       (диагностика: catalog, result/policy, sinks/renderer)
│   ├── platform-etl               (generic Envelope/Stage/Pipeline/PipelineRunner)
│   ├── platform-observability     (MdcScope, LogEvent, taxonomy, PipelineObserver impl)
│   └── platform-diagnostics-logging (bridge: DiagnosticSink → LogEvent)
├── core/
│   ├── ioc-domain                 (единый IOC bounded context; capability = пакеты + ArchUnit-DAG)
│   └── ioc-application            (порты in/out + use cases + IOC ETL stages)
├── adapters/
│   ├── adapter-regex-re2j         (PatternEngine → RE2J/JDK fallback)
│   ├── adapter-source-tika        (SourceReader → Tika)
│   ├── adapter-sink-csv           (IocSink + ArtifactFiller → commons-csv)
│   ├── adapter-lookup-csv         (LookupRepository → CSV)
│   ├── adapter-psl                (HostClassifier → Guava PSL)
│   ├── adapter-ingest             (daemon file ingest → Spring Integration)
│   └── adapter-cli-picocli        (входной адаптер CLI)
└── bootstrap/
    └── ioc-app                    (Spring Boot, composition root, исполняемый jar)
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

- Базовые `platform/*` ни от кого внутри проекта не зависят. Интеграционные
  platform-модули с явным названием bridge (например,
  `platform-diagnostics-logging`) могут зависеть только на те platform-модули,
  которые они связывают, и не должны тянуть application/domain/adapter.
- `platform-etl` зависит только на diagnostics/errors и не знает про IOC domain.
- `ioc-domain` не зависит на application/adapters/bootstrap/platform-etl и
  остаётся framework-free.
- `ioc-application` зависит на `ioc-domain`, `platform-etl` и diagnostics.
- `adapters/*` зависят на `ioc-application` (+ свои библиотеки).
- `ioc-app` (bootstrap) зависит на всё и собирает исполняемый артефакт.

## Принципы нарезки на модули

1. **Один модуль — одна выделяемая ответственность** (подсистема/слой), с явной
   ролью и API.
2. **Агностичность внутрь.** Чем «глубже» модуль (ближе к platform/domain), тем
   меньше у него зависимостей; технологическая специфика — наружу, в адаптеры.
3. **Зависимости только вниз/внутрь.** Реактор + проверки запрещают обратные и
   циклические связи.
4. **Версии — централизованно** через root parent `dependencyManagement`; модули
   не дублируют версии.

## Карта «сервис → модуль»

Сервисы из [services.md](services.md) ложатся в модули так:

| Модуль | Сервисы |
|---|---|
| `platform-diagnostics` | Diagnostics (модель, каталог, порты, sinks/renderer); может зависеть на `platform-errors` для `DiagnosticException` |
| `platform-etl` | Generic ETL kernel: `Envelope`, `Stage`, `Pipeline`, `PipelineRunner`, `PipelineObserver` |
| `platform-observability` | Observability/logging: MdcScope, LogEvent, logging taxonomy, `LoggingPipelineObserver` |
| `platform-diagnostics-logging` | Bridge `DiagnosticSink` → LogEvent/SLF4J (`LoggingDiagnosticSink`); зависит на `platform-diagnostics` + `platform-observability` |
| `platform-errors` | базовые ошибки/common-типы и трансляция; нижний слой для `DiagnosticException` |
| `ioc-domain` | Refanger, IndicatorExtractor, SourceAttributor, MatchPolicy, модели, feature extraction |
| `ioc-application` | Pipeline orchestrator (`ExtractIocsUseCase`), aggregation use case (`AggregatePartitionsUseCase`), ports, IOC stage implementations, payload records |
| `adapter-regex-re2j` | PatternEngine implementation (RE2J + JDK fallback) |
| `adapter-source-tika` | SourceReader (Tika) |
| `adapter-sink-csv` | IocSink + ArtifactFiller (provider/transform), partition/canonical CSV repositories, stable id sidecar CSV |
| `adapter-lookup-csv` | artifact-aware `LookupRepository` for masks + hashes |
| `adapter-psl` | HostClassifier (PSL/Guava) |
| `adapter-ingest` | Watch ingest: `IngestSourceUseCase`(in), `SourceLifecycle`, `IngestionLedger`; SourceFeed adapter-local (Spring Integration); `AGGREGATED` ledger support |
| `adapter-cli-picocli` | входной CLI |
| `ioc-app` (bootstrap) | composition root, исполняемый jar |

## Гранулярность

Решение: **средняя гранулярность**.

- `platform-*` и `adapter-*` вынесены в отдельные артефакты, потому что у них
  независимые роли и/или внешние зависимости.
- `ioc-domain` оставлен единым Maven-модулем: это один bounded context с общим
  языком IOC. Capability (`model/refang/extract/feature/classify/attribute`)
  разделены пакетами и защищены внутридоменным ArchUnit-DAG.
- Первым кандидатом на будущий вынос из domain остаётся `refang`, если появится
  реальное переиспользование вне этого приложения.

Подробное решение: [dev/0009](dev/0009-modularization-granularity.md).

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
