# Архитектура

Стиль — **Clean Hexagonal (порты и адаптеры) + Onion**. Приложение — мини-ETL
по извлечению индикаторов компрометации (IOC): читает документ, деобфусцирует
(refang), извлекает/нормализует индикаторы, классифицирует сетевые маски,
проставляет `source` из заголовков секций, дедуплицирует и пишет в выходные
артефакты.

## Правило зависимостей

Зависимости направлены **внутрь**:

```
adapter ─▶ application ─▶ domain
   └────────▶ bootstrap (composition root) собирает всё вместе
```

- `domain` не зависит ни от чего (чистый Java + JDK).
- `application` зависит только от `domain`.
- `adapter` и `bootstrap` зависят внутрь; здесь — и только здесь — живут
  фреймворки и внешние библиотеки (Spring, Tika, RE2/J, commons-csv, picocli).
- Внутренние слои **никогда** не импортируют из внешних.

## Слои и пакеты

| Слой | Пакет | Содержимое |
|---|---|---|
| Domain | `domain/model` | `Indicator`, `IndicatorType`, `IndicatorCategory`, `SourceContext`, `MaskMatch` |
| Domain | `domain/refang` | `Refanger` (порт) + `ReplacementRefanger`, `RefangRule` |
| Domain | `domain/extract` | `PatternEngine` (порт), `IndicatorExtractor` (порт) + `RegexIndicatorExtractor`, `Span`, `RawIndicator` |
| Domain | `domain/classify` | `MatchPolicy` (порт) + `DefaultMatchPolicy` |
| Domain | `domain/attribute` | `SourceAttributor` (порт) + `MarkerSourceAttributor` |
| Application | `application/port/in` | `ExtractIocsUseCase`, `ExtractionCommand`, `ExtractionResult` (driving) |
| Application | `application/port/out` | `SourceReader`, `IocSink`, `LookupRepository` (driven) |
| Application | `application/service` | `IocExtractionService` — оркестратор конвейера |
| Adapter (in) | `adapter/in/cli` | `IocRootCommand`, `ExtractCommand`, `CliRunner` (picocli) |
| Adapter (out) | `adapter/out/regex` | `Re2jPatternEngine` (default), `JdkRegexPatternEngine` |
| Adapter (out) | `adapter/out/source` | `TikaSourceReader` |
| Adapter (out) | `adapter/out/sink/csv` | `CsvIocSink`, `RowMapper` + мапперы, `IdGenerator` |
| Adapter (out) | `adapter/out/lookup` | `CsvMaskLookupRepository` |
| Bootstrap | `bootstrap` | `IocProperties` (конфиг), `AppConfig` (composition root) |
| Shared | `common` | `IocExtractorException` |

## Конвейер обработки

Выражен в `IocExtractionService` исключительно через порты:

```
read (SourceReader)
  → refang (Refanger)
  → extract (IndicatorExtractor / PatternEngine)
  → attribute source (SourceAttributor)
  → de-duplicate (LookupRepository)
  → write (IocSink на каждый артефакт, маршрутизация по IndicatorType)
```

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
