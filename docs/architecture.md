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
    │           │             │              └────▶ platform/*
    │           │             └───────────────────▶ platform/*
    │           └─────────────────────────────────▶ platform/*
    └─────────────────────────────────────────────▶ platform/*
```

- `platform` содержит переиспользуемые подсистемы: errors, diagnostics,
  generic ETL kernel, observability and diagnostics-logging bridge.
- `domain` не зависит от application/adapters/bootstrap/platform-etl и не тянет
  фреймворки или IO-библиотеки.
- `application` зависит от `domain`, `platform-etl` and diagnostics contracts.
- `adapter` и `bootstrap` зависят внутрь; здесь — и только здесь — живут
  фреймворки и внешние библиотеки (Spring, Tika, RE2/J, commons-csv, picocli).
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
| Application | `application/port/in` | `ExtractIocsUseCase`, `ExtractionCommand`, `ExtractionResult` (driving) |
| Application | `application/port/out` | `SourceReader`, `IocSink`, `LookupRepository` (driven) |
| Application | `application/pipeline/payload` | IOC-specific payload records between stages |
| Application | `application/pipeline/stage` | IOC ETL stage implementations |
| Application | `application/service` | `IocExtractionService` — use-case orchestrator |
| Adapter (in) | `adapter/in/cli` | `IocRootCommand`, `ExtractCommand`, `CliRunner` (picocli) |
| Adapter (out) | `adapter/out/regex` | `Re2jPatternEngine` (default), `JdkRegexPatternEngine` |
| Adapter (out) | `adapter/out/source` | `TikaSourceReader` |
| Adapter (out) | `adapter/out/sink/csv` | `CsvIocSink`, `RowMapper` + мапперы, `IdGenerator` |
| Adapter (out) | `adapter/out/lookup` | `CsvMaskLookupRepository` |
| Bootstrap | `bootstrap` | `IocProperties` (конфиг), `AppConfig` (composition root) |
| Platform | `common` | `IocExtractorException` (`ioc-platform-errors`) |

## Конвейер обработки

Выражен через generic `platform-etl` contracts и IOC-specific application
stages:

```
read (SourceReader)
  → refang (Refanger)
  → extract (IndicatorExtractor / PatternEngine)
  → attribute source (SourceAttributor)
  → de-duplicate (LookupRepository)
  → write (IocSink на каждый артефакт, маршрутизация по IndicatorType)
```

Подход к конвейеру (Pipes-and-Filters + `Envelope`/diagnostics) — [pipeline.md](pipeline.md);
каталог и карта сервисов стадий — [services.md](services.md).

Конвейер — цепочка независимых стадий: новую стадию/реализацию добавляем, не
трогая остальные (OCP). Маршрутизация по типу индикатора позволяет одному
прогону наполнять несколько артефактов (сетевые маски, файловые хэши).

## Порты (контракты)

| Порт | Тип | Назначение |
|---|---|---|
| `ExtractIocsUseCase` | driving (in) | Единая точка входа прикладного ядра |
| `SourceReader` | driven (out) | Извлечение текста из документа любого формата |
| `IocSink` | driven (out) | Один выходной артефакт; фильтрует принимаемые типы |
| `LookupRepository` | driven (out) | Проверки существования (дедуп) против «хранилища» |
| `PatternEngine` | domain SPI | Движок regex (RE2/J по умолчанию, JDK — замена) |

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
открытые случаи — в [notes/0002](notes/0002-output-mapping-and-matching.md).

Политика — **rule-based и декларативная**: тонкий вычислитель + реестр
предикатов над признаками индикатора; сами правила и коды задаются конфигом
(не зашиты в код). Модель — в
[output-mapping.md](output-mapping.md#декларативная-классификация-масок-matchurl--matchhost).

> Ручной эталон использует только варианты 1 и 3; варианты 2 и 4 — по
> авторитетному правилу, поэтому вывод на поддоменах/параметрах закономерно
> расходится с ручной заливкой.

## Артефакты и заполнение

Колонки и правила заполнения артефактов **декларативны в конфиге**, не в коде
(provider/transform-модель). Детали — [output-mapping.md](output-mapping.md).

**Словарь колонок (masks):**

| Колонка | Назначение |
|---|---|
| `id` | идентификатор записи (ascending, продолжается от max в lookup) |
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

## Composition root

`bootstrap/AppConfig` — единственное место, где фреймворк связывает
агностичное ядро с конкретными адаптерами. Смена реализации (движок, reader,
sink) — изменение здесь, и больше нигде. `IocProperties` — типобезопасная
привязка дерева конфигурации `ioc.*`; вся внешняя специфика известна только тут.

## Куда движемся

- Многомодульность (выделение слоёв и подсистем в Maven-модули) —
  [modularization.md](modularization.md).
- Сквозные подсистемы (логирование/диагностика/ошибки) за портами —
  [cross-cutting.md](cross-cutting.md).
- Автоматическая защита границ — [boundaries.md](boundaries.md).
