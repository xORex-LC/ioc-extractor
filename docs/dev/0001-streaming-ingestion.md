# 0001 — Стриминговый инжест (демон)

- **Статус:** принято; актуализировано после этапа 9; **partition/aggregation-часть
  заменена storage-коллапсом** (см. ниже)
- **Дата:** 2026-06-21
- **Связано:** [../ingestion.md](../ingestion.md), [../architecture.md](../architecture.md),
  [../cross-cutting.md](../cross-cutting.md), [../modularization.md](../modularization.md)

> **Superseded (storage Step 1–3 / β-коллапс):** этот ADR — историческая запись
> исходного дизайна с partition-staging, отдельным проходом агрегации и stable-id
> sidecar. Они **удалены**: бизнес-данные теперь в canonical SQLite (truth), демон
> пишет каждый файл прямо в canonical + CSV-проекция, id даёт единый `IdGenerator`.
> Актуальный контур — [../ingestion.md](../ingestion.md) и
> [../worknote/storage-layer.md](../worknote/storage-layer.md). Ниже — как было.

> **Поправки после ревью ([0006](0006-design-review-refinements.md)) и
> модуляризации:** `SourceFeed` — не порт ядра, а adapter-local abstraction над
> Spring Integration; driving-порт — `IngestSourceUseCase`. Stage 10 реализует
> whole-file daemon ingestion + partition output. Tail, Aggregator, retention и
> SQLite-ledger оставлены как последующие шаги/расширения.

## Контекст

CLI-запуск «один прогон → выход» остаётся поддерживаемым, но **не основным**
режимом. Основной режим — долгоживущий сервис (контейнер или systemd), который
постоянно `active`, сканирует каталог источников и обрабатывает новые
файлы/записи по мере появления. Требование: **без потери данных, без двойной
обработки, переживать падения**. Нужно проработать детект, идемпотентность,
запись выхода и жизненный цикл.

Архитектурно демон — это **driving-адаптер** поверх существующего
`ExtractIocsUseCase`; доменное ядро не меняется.

## Решения

| # | Развилка | Выбор | Обоснование | Отклонено |
|---|---|---|---|---|
| 1 | Природа «новых записей» | **Whole-file сейчас, tail как seam later** через adapter-local `SourceFeed` | Stage 10 должен закрыть основной дискретный документный поток без усложнения tail-checkpoint логикой; растущие фиды требуют отдельной модели offset/rotation | Только tail (упустили бы дискретные дропы); tail сразу (раздувает stage 10) |
| 2 | Механизм детекта | **Гибрид:** WatchService + периодическая реконсиляция | Низкая латентность от inotify + страховка опросом (события теряются при overflow и на сетевых/смонтированных ФС) | Чистый inotify (ненадёжен на mount/NFS); чистый polling (выше латентность) |
| 3 | На чём строим инжест | **Spring Integration (file)** | Боевые фильтры (persistent accept-once, стабильность), atomic-rename, poller, tail-producer, error-channel, retry — «меньше велосипедов» | Свой watcher за портом (больше кода для тех же гарантий) |
| 4 | Запись выхода в стриминге | **Партиции на stage 10, агрегация на stage 11** | Партиция на источник идемпотентна и без гонок; отдельный aggregator позже собирает канонический артефакт и решает глобальные id | Дозапись в единый артефакт (атомарность/блокировки/durable-id — хрупко при конкуренции) |
| 5 | Расположение партиций | **Отдельная поддиректория** `dataframe/partitions/` (конфиг., gitignored) | Не смешивать промежуточные партиции с канональными артефактами в `dataframe/` | Партиции рядом с артефактами (мешают, путают lookup) |
| 6 | Очистка партиций (retention) | **Отдельный опциональный `PartitionReaper` за портом `RetentionPolicy`**, по умолчанию off; seam резервируем сразу | Чистку выносим «в сторону», не связываем с основным потоком; добавляется позже без его изменения | Удаление прямо в агрегаторе (связывает ответственности, риск потери при сбое) |
| 7 | Параллелизм | **Multithread-ready дизайн, реализация сначала однопоточная** | KISS: не строим конкуренцию до нужды, но закладываем инварианты (atomic-claim, идемпотентность, id на агрегации, single-writer aggregator) → параллелизм потом без переделки | Многопоток сразу (преждевременная сложность/риск); однопоток без оглядки (рискует переделкой) |

## Следствия

- **Новый inbound-адаптер** `adapters/adapter-ingest` (Spring Integration); SI не
  выходит за пределы этого адаптера. `SourceFeed` остаётся adapter-local.
- **Новые application ports:** `IngestSourceUseCase`, `IngestionLedger`,
  `SourceLifecycle`, `PartitionSinkFactory`. `SourceLifecycle` скрывает
  claim/archive/fail операции ФС за driven-port; `PartitionSinkFactory` скрывает
  daemon partition path strategy за adapter implementation.
- **Выход stage 10:** daemon пишет партиции (атомарно, ключ — content-hash/
  source-key) через отдельный partition wrapper в adapter layer. Core
  `IocSink` API на этапе 10 не расширяем.
- **Выход stage 11:** отдельный `Aggregator` сводит партиции в канонический
  артефакт и назначает глобальные id. Именно он закрывает TODO про устойчивый
  `id auto`.
- **Конечный автомат каталогов** `inbox → processing → done | failed` с атомарными
  перемещениями как защита от двойной обработки и потери при падении.
- **Двухслойная идемпотентность:** SI accept-once (имя+mtime) + наш content-hash
  ledger (ловит переименования/дубли).
- **Жизненный цикл:** graceful shutdown по SIGTERM; два режима запуска через
  `ioc.runtime.mode` (`oneshot` | `daemon`), Spring-профиль используется для
  runtime/config overrides. `System.exit` допустим только для `oneshot`.
- **Новые библиотеки:** `spring-boot-starter-integration` + `spring-integration-file`,
  `spring-retry`; Actuator переносится на период после этапа 11, SQLite/tail
  libraries — later/optional. SHA-256 можно считать через JDK `MessageDigest`
  без `commons-codec`. Детали — в [../ingestion.md](../ingestion.md).
- Смыкается со сквозной **подсистемой ошибок** (политика fail-fast vs
  collect-and-continue, dead-letter) — [../cross-cutting.md](../cross-cutting.md).

## Открытые вопросы

- **Health-транспорт:** Actuator/heartbeat выбираем после этапа 11, когда
  daemon + aggregator сформируют полный runtime-контур. Не блокирует stage 10.
- **Стор ledger/metadata:** файловый (`PropertiesPersistingMetadataStore`) на
  старте, SQLite (`spring-integration-jdbc`) — при росте требований к durability.
- **Политика агрегации stage 11:** по расписанию vs по событию «партиция
  готова»; стратегия глобальных id (продолжение от max в каноническом артефакте).
- **Partition wrapper contract:** спроектировать wrapper так, чтобы он жил в
  adapter layer и не протаскивал source-key в domain/application API сверх
  уже существующих envelope metadata.
- **Retention:** критерий (возраст/объём) и режим (delete/archive/compress) по
  умолчанию при включении `PartitionReaper`.
