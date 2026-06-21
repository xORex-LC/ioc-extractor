# Многомодульность

Дорожная карта перехода от единого модуля к **Maven-реактору**: вся агностичная,
выделяемая бизнес-логика, сервисы, подсистемы и инфраструктура — в отдельных
модулях, оркестрируемых Maven. Аналогия — workspace-модель `uv` в Python:
один корень управляет набором независимо собираемых пакетов с явными
зависимостями.

> Статус: **план**. Сейчас проект — один модуль. Ниже — целевая структура и
> поэтапный переход. Границы модулей подкрепляются проверками из
> [boundaries.md](boundaries.md).

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
│   ├── platform-bom               (BOM: управление версиями зависимостей)
│   ├── platform-errors            (базовые ошибки/common-типы + порты трансляции)
│   ├── platform-diagnostics       (диагностика: каталог, порты, sinks/renderer; зависит на platform-errors при DiagnosticException)
│   ├── platform-observability     (логирование: MdcScope, LogEvent, ECS-конфиг)
│   ├── platform-diagnostics-logging (bridge: DiagnosticSink → LogEvent)
│   └── platform-regex             (PatternEngine SPI + re2j/jdk адаптеры)
├── core/
│   ├── ioc-domain                 (чистый домен; зависит только на platform-*)
│   └── ioc-application            (порты in/out + use cases; зависит на ioc-domain)
├── adapters/
│   ├── adapter-source-tika        (SourceReader → Tika)
│   ├── adapter-sink-csv           (IocSink + ArtifactFiller → commons-csv)
│   ├── adapter-lookup-csv         (LookupRepository → CSV)
│   ├── adapter-psl                (HostClassifier → Guava PSL)
│   └── adapter-cli-picocli        (входной адаптер CLI)
└── bootstrap/
    └── ioc-app                    (Spring Boot, composition root, исполняемый jar)
```

> Имена выше — артефакты Maven; в реальных `artifactId` несут общий префикс
> (`ioc-…`). Карта «сервис → модуль» — ниже.

### Направление зависимостей между модулями

```
ioc-app ─▶ adapters/* ─▶ ioc-application ─▶ ioc-domain ─▶ platform/*
                     └────────────────────────────────────▶ platform/*
```

- Базовые `platform/*` ни от кого внутри проекта не зависят. Интеграционные
  platform-модули с явным названием bridge (например,
  `platform-diagnostics-logging`) могут зависеть только на те platform-модули,
  которые они связывают, и не должны тянуть application/domain/adapter.
- `ioc-domain` зависит максимум на `platform/*` (errors/regex SPI) — без фреймворков.
- `ioc-application` зависит на `ioc-domain`.
- `adapters/*` зависят на `ioc-application` (+ свои библиотеки).
- `ioc-app` (bootstrap) зависит на всё и собирает исполняемый артефакт.

## Принципы нарезки на модули

1. **Один модуль — одна выделяемая ответственность** (подсистема/слой), с явной
   ролью и API.
2. **Агностичность внутрь.** Чем «глубже» модуль (ближе к platform/domain), тем
   меньше у него зависимостей; технологическая специфика — наружу, в адаптеры.
3. **Зависимости только вниз/внутрь.** Реактор + проверки запрещают обратные и
   циклические связи.
4. **Версии — централизованно** через `platform-bom`; модули не дублируют версии.

## Карта «сервис → модуль»

Сервисы из [services.md](services.md) ложатся в модули так:

| Модуль | Сервисы |
|---|---|
| `platform-regex` | PatternEngine (SPI + RE2J/JDK) |
| `platform-diagnostics` | Diagnostics (модель, каталог, порты, sinks/renderer); может зависеть на `platform-errors` для `DiagnosticException` |
| `platform-observability` | Observability/logging: MdcScope, LogEvent, ECS-конфиг |
| `platform-diagnostics-logging` | Bridge `DiagnosticSink` → LogEvent/SLF4J (`LoggingDiagnosticSink`); зависит на `platform-diagnostics` + `platform-observability` |
| `platform-errors` | базовые ошибки/common-типы и трансляция; нижний слой для `DiagnosticException` |
| `ioc-domain` | Refanger, IndicatorExtractor, SourceAttributor, MatchClassifier, Deduplicator, модели |
| `ioc-application` | Pipeline orchestrator (`ExtractIocsUseCase`), стадии, `Envelope`/`Result`, Aggregator/Retention (future) |
| `adapter-source-tika` | SourceReader (Tika) |
| `adapter-sink-csv` | IocSink + ArtifactFiller (provider/transform) |
| `adapter-lookup-csv` | LookupRepository |
| `adapter-psl` | HostClassifier (PSL/Guava) |
| `adapter-ingest` | Watch ingest: `IngestSourceUseCase`(in), `IngestionLedger`; SourceFeed adapter-local (Spring Integration) — future |
| `adapter-cli-picocli` | входной CLI |
| `ioc-app` (bootstrap) | composition root, исполняемый jar |

## Гранулярность

Решение: **сервисы — это уже изолированные единицы за портами** (готовы к выносу),
но физически нарезаем модули **поэтапно и укрупнённо**, а не «модуль на каждый
сервис» сразу:
- сначала границы по слоям (`platform-*`, `ioc-domain`, `ioc-application`, `adapters`),
- более тонкое дробление (отдельные `platform-*`/`adapter-*`) — по мере роста и
  потребности в параллельной разработке.

Параллельная разработка возможна **уже до** физического дробления: сервис изолирован
портом, и его границу стережёт ArchUnit ([boundaries.md](boundaries.md)). Это даёт
выгоды модульности без преждевременной сложности реактора (KISS).

## Поэтапный переход

1. **Этап 0 (сейчас):** один модуль, слои разнесены по пакетам, границы — на
   дисциплине.
2. **Этап 1:** выделить parent-pom (`packaging=pom`) и BOM; ввести ArchUnit для
   фиксации правил зависимостей пакетов (см. [boundaries.md](boundaries.md)).
3. **Этап 2:** вынести `ioc-domain` и `ioc-application` в отдельные модули
   (главный выигрыш — компиляционная гарантия чистоты домена).
4. **Этап 3:** вынести адаптеры и сквозные подсистемы (`platform/*`) в свои модули.
5. **Этап 4:** `bootstrap` как тонкий исполняемый модуль-сборщик.

Переход — инкрементальный: на каждом этапе сборка и тесты остаются зелёными.
