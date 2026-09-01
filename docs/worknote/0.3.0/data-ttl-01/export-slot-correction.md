---
title: "DATA-TTL-01 — stable reusable export slots"
version: "0.3.0"
status: "P7 verified"
document_type: "Worknote design correction"
source_of_truth: false
language: "ru"
---

# DATA-TTL-01 — stable reusable export slots

Этот документ фиксирует переоткрытое после P6 требование к полю `id` в
export-slice. Долгоживущее архитектурное решение вынесено в
[ADR-0021](../../../ADR/0021-stable-reusable-export-slots.md). Здесь находится
детальный проект и текущее implementation evidence P7.

## 1. Исправленный бизнес-контракт

Внешняя колонка `id` — не идентичность canonical record и не идентичность его
lifecycle. На стороне приложения это **`export_slot`**: номер места, который
нужен потребителю конкретного export artifact.

Правила одного `(profile, artifact)`:

1. Пока запись активна, её `export_slot` стабилен во всех новых slices.
2. Истечение другой записи не сдвигает и не перенумеровывает surviving rows.
3. После исчезновения expired lifecycle из active set его слот становится
   свободным при следующем допустимом export reconciliation.
4. Новая lifecycle получает минимальный свободный положительный слот.
5. Если свободных слотов нет, используется следующий high-water slot.
6. Пробелы допустимы: projection не обязана быть плотной `1..N`.
7. Старые immutable slices не изменяются.

Пример:

| Момент | Active mapping в новом slice | Пояснение |
|---|---|---|
| T1 | `A=1, B=2, C=3` | Начальное состояние |
| T2 | `C=3` | `A` и `B` expired; `C` не перенумерован |
| T3 | `D=1, C=3` | `D` занял первый свободный слот |
| T4 | `D=1, E=2, C=3` | `E` занял следующий свободный; `C` всё ещё `3` |

Запись `A` в старом slice и запись `D` в более новом slice могут обе иметь
внешний `id=1`. Поэтому диагностический ключ должен включать как минимум
`slice_id + profile + artifact + export_slot`; один `export_slot` не является
ссылкой на запись во времени.

## 2. Что остаётся неизменным

- `_lifecycle_id` и технический primary key остаются уникальными и не
  переиспользуются.
- Reappearance после expiry создаёт новую canonical lifecycle.
- `row_key` продолжает задавать keep-first identity только внутри active
  canonical lifecycle contract.
- Source-owned ID хранится как namespaced business/provenance field и не
  смешивается с `export_slot`.
- Expiry не инициирует immutable export. Новый automatic slice разрешён только
  после обычного new-data trigger.
- `valid_until`, active predicate, history, retention, receipt fallback и clock
  policy из ADR-0020 не меняются.
- Артефакты без внешней колонки `id` не участвуют в allocation.

## 3. Обнаруженное расхождение текущей реализации

На момент переоткрытия P7:

- canonical artifact table использует `id INTEGER PRIMARY KEY AUTOINCREMENT`;
- snapshot reader выбирает configured `id` прямо из canonical table и сортирует
  результат по нему;
- durable artifact ID allocator выдаёт монотонные непереиспользуемые ranges.

Это корректно характеризует выполненный P6, но не выполняет исправленный
consumer contract. Исправление должно убрать только зависимость внешнего
export `id` от canonical primary key. Переписывать TTL/history/provenance path
из-за этого не требуется.

## 4. Владение и границы модулей

| Boundary | Ответственность P7 |
|---|---|
| `core/ioc-application`, `application.export` | Existing `SnapshotSliceReader` contract, slot-policy fingerprint и failure contract; отдельный registry port не нужен |
| `adapter-store-jdbc` | SQLite migration, registry, set-based allocator, consistent active snapshot и collision checks |
| `adapter-sink-csv` | Сериализация уже разрешённого `export_slot` во внешнюю колонку `id` |
| `bootstrap/ioc-app` | Composition root и existing configuration wiring |

Новый Maven-модуль или внутрипроектная библиотека не нужны: capability не
образует новую integration family и используется только export path. Domain и
TTL application services не должны импортировать export-slot abstraction.

Новый control event также не нужен. Существующие события остаются latency
hints; slot reconciliation является частью уже начавшегося export use case.

## 5. Durable data model

Registry хранится в dataframe DB рядом с canonical truth, но логически
принадлежит export capability:

```sql
export_slot_assignment(
    profile,
    artifact,
    lifecycle_id,
    slot,
    assigned_at_epoch_ms,
    PRIMARY KEY (profile, artifact, lifecycle_id),
    UNIQUE (profile, artifact, slot)
)

export_slot_free(
    profile,
    artifact,
    slot,
    released_at_epoch_ms,
    PRIMARY KEY (profile, artifact, slot)
)

export_slot_state(
    profile,
    artifact,
    next_slot,
    source_generation,
    updated_at_epoch_ms,
    PRIMARY KEY (profile, artifact)
)
```

Физические имена могут быть уточнены при реализации, однако обязательны:

- unique assignment lifecycle→slot;
- unique active ownership slot→lifecycle;
- индексированный поиск минимального свободного слота;
- high-water state, не вычисляемый через `MAX(active id)`;
- generation/fingerprint для обнаружения смешанного snapshot.

Service DB отвергнута: atomic commit между двумя SQLite-файлами отсутствует, а
падение между обновлением registry и чтением dataframe создало бы
необъяснимое состояние. Исторические snapshots не требуют отдельной копии
registry: завершённый immutable slice уже материализует соответствие на момент
публикации.

## 6. Export-time reconciliation

Операция запускается только когда существующая export policy уже разрешила
создание slice:

```text
eligible export trigger
        |
        v
stage active lifecycle IDs at generation G
        |
        +--> release assignments absent from active G
        +--> preserve surviving assignments byte-for-byte
        +--> assign smallest free slots to new lifecycle IDs
        +--> assign remainder from next_slot
        |
        v
verify/open consistent active snapshot at G
        |
        v
project export_slot AS external id -> immutable slice -> _SUCCESS
```

Если active generation изменилась до snapshot boundary, попытка не публикует
mixed slice и повторяется через существующий export recovery path. Unique
constraints остаются последним safety net при ошибке координации.

Для нескольких новых lifecycle порядок allocation детерминирован: ascending
internal lifecycle ID получает ascending available slots. Это не придаёт
internal ID внешней семантики; он используется только как стабильный tie-break.

Assignment может быть committed до успешной записи файлов slice. Это безопасно:
неопубликованное соответствие не видно потребителю, а retry сохранит тот же
слот. Publication authority остаётся у существующего complete marker/saga.

## 7. Производительность

Запрещён алгоритм вида `для каждой строки → SELECT MIN(free) → INSERT`.
Целевой adapter должен:

- stage active lifecycle keys bounded batch-ами;
- release vanished assignments set-based;
- сопоставить ordered free slots и ordered new lifecycles set-based либо через
  bounded staging table;
- выделить остаток одним high-water range;
- читать projection streaming-ом, не материализуя 100k rows в heap;
- иметь query-plan evidence для active membership, released slots и
  `ORDER BY slot`.

Минимальный индексный контракт:

- assignment PK по `(profile, artifact, lifecycle_id)`;
- assignment unique index по `(profile, artifact, slot)`;
- free-slot PK/index по `(profile, artifact, slot)`;
- active canonical predicate использует уже существующий validity index.

## 8. Migration и rollback

При первом включении P7 текущие active lifecycle seed-ятся текущими внешними
`id`, если соответствие однозначно. Это сохраняет mapping для потребителей:
survivors не меняют номера только из-за upgrade.

- `next_slot = max(seed slot) + 1`;
- положительные holes ниже high-water, не занятые active lifecycle, становятся
  free;
- duplicate slot, duplicate lifecycle mapping, неположительное значение или
  ambiguous artifact/profile namespace останавливают activation;
- silent renumbering при migration запрещён;
- policy version входит в export plan/schema fingerprint, но не требует
  canonical artifact identity epoch bump.

Rollback выполняется вместе с matching dataframe/service DB backup и release
binary/config. Down migration, пытающаяся слить reusable slots обратно с
canonical IDs, не поддерживается.

## 9. Failure и race matrix

| Сценарий | Требуемый outcome |
|---|---|
| Expiry без новых данных | Active record исчезает по TTL; новый immutable slice и slot mutation не обязательны |
| New data после накопившегося expiry | Один reconciliation освобождает old slots и назначает минимальные новым lifecycle |
| Только survivors | Все их slots остаются прежними; compaction отсутствует |
| Crash после assignment commit, до `_SUCCESS` | Retry использует те же assignments; incomplete slice не публикуется |
| Ingest меняет generation во время export | Mixed slice не публикуется; bounded retry/recovery |
| Два export trigger | Existing single-flight/saga сериализует namespace; unique constraints блокируют двойное владение |
| Reappearance того же IOC | Новая lifecycle может получить любой минимальный свободный slot, включая slot прежней lifecycle |
| Artifact без `id` | Registry и serialized schema не меняются |

## 10. P7 acceptance evidence

- tests: survivor stability, smallest-hole reuse, multiple holes, no compaction,
  deterministic batch allocation, restart и failure recovery;
- migration tests: sparse seed, high-water, collision/non-positive fail-closed;
- race tests: ingest×export generation change и concurrent trigger;
- immutable slice test: одинаковый slot в двух slices у разных lifecycle без
  mutation первого slice;
- compatibility: current external column/order сохранены, source IDs не
  смешиваются, artifacts without `id` не меняются;
- performance: новый 100k профиль и SQLite query plans без N+1/full JVM
  materialization;
- packaged stand: fresh install, v0.2.0 upgrade seed, activation rollback и
  release rollback;
- после реализации обновлены published capability/storage/export/operator docs.

Automated implementation evidence получен 2026-08-19: migration/rollback,
survivor/hole/high-water/restart/generation/concurrency cases, два 100k
reconciliation snapshot и три immutable CSV slices прошли. Повторные packaged
fresh/upgrade/rollback проверки и fresh full-reactor gate завершены
2026-09-01. P6 evidence сохраняется как доказательство TTL lifecycle и
характеристика прежней ID-модели.
