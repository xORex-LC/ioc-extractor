# 0006 — Уточнения по итогам дизайн-ревью

- **Статус:** принято (дизайн)
- **Дата:** 2026-06-21
- **Связано:** [../ingestion.md](../ingestion.md), [../output-mapping.md](../output-mapping.md),
  [../extraction.md](../extraction.md), [../services.md](../services.md),
  [0002-output-mapping-and-matching.md](0002-output-mapping-and-matching.md)

## Контекст

Ревью со стороны заказчика выявило ряд противоречий/недоопределённостей в
спроектированных доках. Ниже — принятые разрешения (доки обновлены).

## Решения

| # | Замечание | Разрешение |
|---|---|---|
| 1 | `SourceFeed` описан и как driven, и как driving | `adapter/in/watch` — inbound-адаптер, вызывает **driving-порт `IngestSourceUseCase`**; `SourceFeed` — **adapter-local** абстракция над SI, **не** порт ядра |
| 2 | PSL/Guava затягивался в domain | Домен содержит **порт `HostClassifier`**; адаптер `PslHostClassifier` (Guava) — в `adapter-psl`. В домене **нет** `InternetDomainName` |
| 3 | Идемпотентность инжеста требовала строгого автомата | Явные статусы юнита в `IngestionLedger`: `CLAIMED → PARTITION_WRITTEN → LEDGER_RECORDED → SOURCE_ARCHIVED → (AGGREGATED)` + **компенсации** при старте по последнему статусу |
| 4 | Глобальные id в агрегаторе недоопределены | Устойчивый индекс **`dedupKey → id`** (в каноне или отдельном индексе агрегатора): известный ключ сохраняет id при повторной агрегации/смене порядка/удалении партиций/дедупе; новый → `max+1` |
| 5 | «Новый формат без кода» переобещано | Уточнено: без кода — формат из **существующих** `ValueProvider`/`Transform`; новая семантика колонки = новый тонкий provider/transform (это код) |
| 6 | Whole-file и tail имели одну модель идемпотентности | Разведено: whole-file — **content-hash**; tail — **checkpoint** (идентичность файла + offset + маркер ротации + id/hash записи) |
| 7 | Риск, что `ConfigurableRowMapper` станет интерпретатором | DSL **ограничен**: `from`/`const`, `when-type`, упорядоченные `transform`; без вложенных выражений, условий сложнее `when-type` и вычислений в YAML |
| 8 | `MatchPolicy` поверх строкового URL-парсинга в маппере | Введены доменные `IndicatorNormalizer` + `IndicatorFeatureExtractor`; `MatchPolicy`/предикаты работают над `IndicatorFeatures`; PSL-признак — через порт `HostClassifier` |

## Следствия

- Новые/уточнённые порты: `IngestSourceUseCase` (in), `HostClassifier` (domain
  port → `adapter-psl`); новые домен-сервисы `IndicatorNormalizer`,
  `IndicatorFeatureExtractor` (см. [services.md](../services.md)).
- `IngestionLedger` хранит **статусы**, а не булево; восстановление —
  компенсациями.
- Агрегатор владеет устойчивым `dedupKey → id`.
- DSL заполнения зафиксирован как ограниченный (не язык).

## Открытые вопросы

- Точная форма `dedupKey` (нормализованное значение + тип) и место индекса
  id (внутри каноны vs отдельный файл) — при реализации агрегатора.
- Формат checkpoint для tail (inode vs file-id на конкретной ФС) — при реализации
  tail-режима.
