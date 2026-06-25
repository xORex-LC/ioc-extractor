# Стриминговый инжест (демон)

Детальный дизайн основного режима работы — долгоживущего сервиса, который
постоянно `active`, обнаруживает новые источники и обрабатывает их без потери
данных и без двойной обработки. CLI-режим «один прогон» сохраняется как
вторичный. Обоснование выборов — в [dev/0001-streaming-ingestion.md](dev/0001-streaming-ingestion.md).

> Текущий контур: проект — Maven-реактор; generic pipeline находится в
> `platform/platform-etl`, IOC use cases и stages — в `core/ioc-application`,
> технические входы — отдельные `adapter-*` модули. Реализованы whole-file
> daemon ingestion, **прямая запись в canonical SQLite-хранилище**, CSV-проекция,
> artifact-aware lookup, retention/reaper и health-контур.
> Служебный durable ledger переключается file ↔ JDBC
> (`ioc.ingestion.ledger.type: file | jdbc`). **Бизнес-данные dataframe хранятся
> в SQLite как source of truth** (`ioc.storage.dataframe.type: jdbc`, default), а
> CSV-артефакты (`*_generated.csv`) — генерируемая проекция из БД. Это касается
> обоих режимов: oneshot и daemon переключены на DB-truth одновременно.
>
> **β-коллапс выполнен:** partition-staging, отдельный проход агрегации и
> stable-id sidecar удалены. Демон извлекает каждый файл прямо в canonical
> (`SourceSinkFactory → JdbcIocSink`) и тут же перепроецирует CSV. Запись→проекция
> идёт сагой с durable run-ledger (`ingest_run`): crash-window после commit БД и до
> CSV-проекции закрывается на старте повторной проекцией незавершённого
> `DB_COMMITTED` run (и/или полным идемпотентным репроцессом источника со статусом
> `CLAIMED`).

## 0. Реализованный scope 0.1.0

File-ingest реализован как driving-adapter поверх извлекающего pipeline. Spring
Integration остаётся в `adapter-ingest`, ядро application/domain не знает о SI, а
идемпотентность строится вокруг durable ledger и `INSERT … ON CONFLICT(row_key)
DO NOTHING` в canonical-таблицах (keep-first).

Ключевые runtime-решения:

- **Режим приложения** задаёт `ioc.runtime.mode: oneshot | daemon`; observability
  имеет свой `ioc.observability.mode` и используется для лог-профиля/полей.
- В `daemon` CLI runner выключен, а процесс живёт вместе со Spring context и
  Spring Integration flow.
- `IocSink` API не расширен контекстом источника. В daemon-режиме bootstrap
  подставляет per-source `JdbcIocSink`-и (`SourceSinkFactory.createFor(unit)`),
  которые пишут прямо в canonical с `_source_key` из ingestion-record; провенанс
  оседает в `<artifact>_sources`.
- **Авторитет id — единый** `IdGenerator(strategy, start=maxId+1)` (как в oneshot);
  отдельный stable-id sidecar упразднён, `id AUTOINCREMENT` — пассивная подстраховка.

## 1. Режимы запуска

| Режим | Профиль | Поведение |
|---|---|---|
| `oneshot` | `ioc.runtime.mode=oneshot` (default) | CLI активен: `ioc extract --source <file>` → один прогон → exit code |
| `daemon` | `ioc.runtime.mode=daemon`, опц. Spring profile `daemon` | CLI runner выключен; SI-поток + scheduler держат контекст живым |

Spring profile можно использовать для logback/config overrides, но источник
истины для поведения приложения — property `ioc.runtime.mode`. В `daemon`
**нет** `System.exit` после старта контекста: жизнь процесса поддерживают
компоненты Spring Integration (`SmartLifecycle`). CLI-runner активен только в
`oneshot`.

> **`oneshot` накопителен, а не «регенерирует с нуля».** При `dataframe.type=jdbc`
> (default) `ioc extract` пишет в персистентный `./dataframe/ioc-dataframe.db`
> через `INSERT … ON CONFLICT(row_key) DO NOTHING` (keep-first), затем
> перепроецирует CSV из БД. Поэтому несколько прогонов **накапливают** IOC в БД,
> а каждый `*_generated.csv` — это проекция всей накопленной таблицы, а не только
> текущего источника. Повтор того же источника идемпотентен (дедуп по `row_key`).
> Это намеренно: БД — система записи. Чтобы получить «чистый» набор только из
> одного источника, удалите `dataframe/ioc-dataframe.db` перед прогоном (так же
> поступают golden-тесты) либо запустите с отдельным `ioc.storage.dataframe.url`.
> В отличие от прежнего поведения, oneshot теперь поднимает Hikari-пул + SQLite
> на каждый вызов CLI.

## 2. Размещение в архитектуре

Демон — **driving-адаптер** (`adapters/adapter-ingest`) поверх application
use cases. Spring Integration живёт только в этом adapter-модуле и bootstrap
wiring.

```
[inbox dir] ─▶ adapter-ingest (Spring Integration file)
                   │  detect → stabilize → claim
                   │  (SourceFeed — adapter-local над SI: whole-file / tail)
                   ▼
              IngestSourceUseCase (application)
                   │  ledger/status/recovery + run-ledger saga
                   ├──────────────▶ SourceLifecycle (claim/archive/fail)
                   └──────────────▶ extraction pipeline (refang → extract → attribute)
                   │
                   ▼
              JdbcIocSink (per-source) → canonical SQLite ──▶ ArtifactProjection → *_generated.csv
   driving:  IngestSourceUseCase, ExtractIocsUseCase
   driven:   IngestionLedger | SourceLifecycle | SourceSinkFactory | CanonicalArtifactRepository
             | ArtifactProjection | RunLedger | LookupRepository
```

**Новые порты**

| Порт | Тип | Назначение |
|---|---|---|
| `IngestSourceUseCase` | in (driving) | Приём одной единицы-источника на обработку (вызывается watch-адаптером) |
| `IngestionLedger` | out (driven) | Durable-журнал **статусов** обработки (идемпотентность, восстановление) |
| `SourceLifecycle` | out (driven) | Claim/archive/fail источника через атомарные операции ФС; application не знает SI |
| `SourceSinkFactory` | out (driven) | Создание per-source `IocSink`-ов (`JdbcIocSink`), пишущих прямо в canonical с `_source_key`; реализация в bootstrap поверх `adapter-store-jdbc` |
| `CanonicalArtifactRepository` | out (driven) | Чтение / транзакционная запись canonical-таблиц (rows + `<artifact>_sources`), keep-first `ON CONFLICT` |
| `ArtifactProjection` | out (driven) | Перепроекция `*_generated.csv` из canonical-истины (`CsvArtifactProjection`) |
| `ArtifactIdentityResolver` | out (driven) | Единая формула `row_key` из output-значений (application) |
| `RunLedger` | out (driven) | Durable чекпоинты саги per-file `write→project` (`ingest_run`); recovery + observability |
| `RunRetentionUseCase` / `RetentionStore` | in / out | Reaper растущих каталогов (возраст/количество → delete/archive); IO — `FileSystemRetentionStore` |

`SourceFeed` — **не порт ядра**, а adapter-local абстракция внутри
`adapter-ingest` над Spring Integration (whole-file/tail). Наружу единицы
отдаются через driving-порт `IngestSourceUseCase`; ядро остаётся framework-free,
SI заменяем без влияния на ядро.

Размещение по модулям:

| Модуль | Что добавляется |
|---|---|
| `core/ioc-application` | `IngestSourceUseCase`, ingestion command/result, `IngestionLedger`, `SourceLifecycle`, `SourceSinkFactory`, run-ledger saga + `IngestRunRecoveryService`, orchestration service |
| `adapters/adapter-ingest` | Spring Integration file flow, stability filters, `SourceLifecycle` FS implementation, file ledger implementation |
| `adapters/adapter-store-jdbc` | `JdbcIocSink`, `JdbcCanonicalArtifactRepository` (rows + `_sources`), `JdbcRunLedger`, schema migrations/health |
| `adapters/adapter-sink-csv` | `CsvArtifactProjection` (CSV-проекция из canonical) |
| `bootstrap/ioc-app` | conditional wiring: `oneshot` CLI vs `daemon` ingest, `SourceSinkFactory`, config binding, app lifecycle |

## 3. Детект появления (гибрид)

`FileReadingMessageSource` Spring Integration остаётся входной точкой. Гибрид
делаем явно:

- WatchService (`setUseWatchService(true)`) — low-latency путь для событий ФС.
- Poller + периодическая reconciliation scan — safety net: полный/контрольный
  проход по `inbox`, чтобы не зависеть от надёжности inotify/overlay/NFS.

Интервал реконсиляции и `maxMessagesPerPoll` — конфигурируемы. На сетевых или
overlay-ФС WatchService может молчать, поэтому reconciliation — обязательный
инвариант daemon-режима, а не оптимизация.

## 4. Два типа источников

| Тип | Механизм SI | Случай |
|---|---|---|
| **Whole-file** | `FileReadingMessageSource` (+ фильтры) | Дискретный документ целиком (как `ioc-source.htm`) |
| **Tail** *(later)* | `FileTailingMessageProducer` (Apache Commons `Tailer`) | Дозапись новых строк/записей в растущий фид; обработка ротации, трекинг смещения |

В этапе 10 реализуем только **whole-file**. Tail оставляем как совместимый seam:
adapter-local abstraction и модель ключей не должны закрывать путь к tail, но
tail не входит в baseline и не должен усложнять первый daemon flow.

> **Идемпотентность у режимов разная** (см. §7): whole-file — по **content-hash**;
> tail — по **checkpoint** (идентичность файла + смещение + маркер ротации +
> id/hash записи). Единая «переобработка того же content-hash» для растущего
> файла **не работает** — у tail свой ключ.

## 5. Автопоиск источников

Жёсткие имена остаются как частный случай; основной режим — **поиск по
паттернам**:

- SI-фильтры `SimplePatternFileListFilter` (glob, Ant-style) /
  `RegexPatternFileListFilter` поверх `ChainFileListFilter`.
- Конфиг: `ioc.ingestion.patterns.include`/`exclude` (напр. `["*.htm","*.docx","ioc-*.*"]`).
- «Новый источник» = файл, проходящий include-паттерны и **не** отсеянный
  фильтром дедупликации (см. §7).

## 6. Конечный автомат каталогов

Защита от двойной обработки и потери при падении — атомарные перемещения между
подкаталогами (всё на одной ФС, `Files.move(ATOMIC_MOVE)`):

```
inbox/  ──claim──▶  processing/  ──success──▶  done/ (archive)
   ▲                    │
   │                    └──fail(после ретраев)──▶  failed/ (dead-letter + .error sidecar)
   └── реконсиляция при старте: всё из processing/ → обратно в inbox/
```

- Перемещение в `processing/` = эксклюзивный «клейм» источника (статус `CLAIMED`).
- При старте — **status-driven реконсиляция** (см. §7): каждый незавершённый юнит
  из `processing/` доводится/откатывается по записанному статусу, а не слепо
  возвращается в `inbox/`.
- Каталоги — конфигурируемы; по умолчанию под общим рабочим корнем.

## 7. Идемпотентность, статусы и восстановление

**Модель идемпотентности — по типу источника:**
- **Whole-file:** ключ = **content-hash (sha256 файла)** — ловит переименования и
  повторные дропы того же содержимого.
- **Tail:** ключ = **checkpoint** = идентичность файла (path + inode/маркер
  создания) + байтовое смещение + маркер ротации + id/hash записи. У растущего
  файла нет единого content-hash — прогресс трекается смещением/чекпоинтом.

**Двухслойный дедуп (whole-file):** SI `FileSystemPersistentAcceptOnceFileListFilter`
(дёшево, имя+mtime) + `IngestionLedger` по content-hash/source-key (надёжно).
Spring filter — оптимизация входа, ledger — источник истины.

**Явные статусы юнита в `IngestionLedger`** (не булево «обработано»):
```
CLAIMED ─▶ SOURCE_ARCHIVED          (отказ на любом шаге → FAILED)
```
После `markClaimed` единственный промежуточный статус — `CLAIMED`; запись→проекция
выполняется как сага под durable run-ledger, и лишь затем источник доводится до
`SOURCE_ARCHIVED`.

**Восстановление (компенсации) при старте:**

| Слой | Состояние | Компенсация |
|---|---|---|
| ingestion-ledger `CLAIMED` | в `processing/`, не доведён до архива | **полный идемпотентный репроцесс** источника (extract → canonical write → project → archive) |
| ingestion-ledger `SOURCE_ARCHIVED` / `FAILED` | завершено / отклонено | ничего |
| run-ledger `STARTED` | крэш до commit БД | пометить `FAILED` (без авто-replay: пересчёт unsafe) |
| run-ledger `DB_COMMITTED` | данные есть, проекция нет | **повторить проекцию** из canonical → `COMPLETED` |
| orphan в `processing/` без записи ledger | осиротевший источник | `fail` + `FAILED` |

Все шаги **идемпотентны**: canonical-запись — `ON CONFLICT(row_key) DO NOTHING`
(keep-first), проекция — полная регенерация из БД, перемещение — `ATOMIC_MOVE`
(уже перемещён → no-op). Итог — at-least-once доставка + идемпотентные шаги =
effectively-once на выходе. Run-ledger гарантирует, что окно «БД записана, CSV нет»
закрывается даже без полного репроцесса; см. §8 о роли run-ledger.

## 8. Выход: прямая запись в canonical + CSV-проекция

`dataframe.type=jdbc` (default) делает **SQLite source of truth**, а `*_generated.csv`
— производную проекцию. Промежуточного partition-staging нет.

```
dataframe/
├── ioc-dataframe.db                 ← canonical SQLite (source of truth)
├── masks_list_generated.csv         ← проекция из БД
├── ip_list_generated.csv
├── address_blacklist_generated.csv
└── hashes_list_generated.csv
```

- **Запись:** извлечённые из источника индикаторы идут в per-source `JdbcIocSink`-и
  (`SourceSinkFactory.createFor(unit)`), которые пишут прямо в canonical-таблицы
  одной транзакцией на артефакт (rows + `<artifact>_sources` с `_source_key` из
  ingestion-record). Дедуп — `ON CONFLICT(row_key) DO NOTHING` (keep-first), причём
  источник отброшенного дубля всё равно учитывается в `<artifact>_sources`.
- **Проекция:** после успешного commit БД `IngestionService` вызывает
  `ArtifactProjection.project(...)` — `CsvArtifactProjection` перечитывает артефакт
  из БД и атомарно (`*.tmp → ATOMIC_MOVE`) переписывает `*_generated.csv`.
- **Сага и run-ledger:** per-file шаг — `STARTED → DB_COMMITTED → PROJECTION_COMPLETED
  → COMPLETED` (`ingest_run` в service-БД). При падении после commit БД и до проекции
  startup-recovery (`IngestRunRecoveryService`) доспроецирует незавершённый
  `DB_COMMITTED` run. Run-ledger держим как **durable-observability**: восстановление
  и так несёт ingestion-ledger (`CLAIMED → полный репроцесс`), а run-ledger даёт
  наблюдаемость фаз write→project и фундамент под будущую export-сагу.
- **Авторитет id:** единый `IdGenerator(strategy, start=canonical maxId+1)` (как в
  oneshot), общий счётчик через сессию демона; контракт id — stable/unique/ascending
  (не gapless). `id AUTOINCREMENT` — пассивная подстраховка.

### 8.1 Retention reaper (housekeeping)

Архивные каталоги демона (`done`, `failed`) чистит **единый декларативный
reaper** — общая обслуживающая абстракция, а не свой механизм на каждый каталог.
После β-коллапса partition-staging нет, поэтому target'ы — плоские `done`/`failed`
(canonical-данные живут в БД и retention'у не подлежат). Раскладка по слоям:

- **Триггер по времени** — `DaemonMaintenanceScheduler` (`bootstrap`): «часы»,
  которые дёргают use-case.
- **Политика** — чистая `RetentionPolicy` (`application.maintenance`): по списку
  записей и `now` решает, что просрочено. Запись реапается, если **старше
  `max-age` ИЛИ за пределами `max-count` самых свежих** (критерии объединяются).
  Без часов и ФС — покрыта таблицей тест-кейсов.
- **IO** — порт `RetentionStore` (`application.port.out.maintenance`) +
  `FileSystemRetentionStore` (в `adapter-ingest`). Реапит **листовые файлы
  рекурсивно**; `done`/`failed` — плоские каталоги архивов источников.

Свойства:

- **Безопасный дефолт:** `ioc.maintenance.retention.enabled: false` — без явного
  включения ничего не удаляется.
- **Действия:** `delete` | `archive` (перенос в `archive-dir`).
- Источник истины бизнес-данных — canonical SQLite; reaper трогает только
  файловые архивы обработанных/упавших источников.

Конфиг — см. §12 (`ioc.maintenance.retention.*`).

## 9. Ошибки, ретраи, dead-letter

- SI `error-channel` + `RequestHandlerRetryAdvice` (spring-retry): экспоненциальный
  backoff, N попыток.
- После исчерпания — источник в `failed/` + sidecar `.<имя>.error` с диагностикой.
- Один «ядовитый» файл не блокирует поток (политика `collect-and-continue`).
- Категоризация и трансляция ошибок идут через общий exception/diagnostics
  контур; typed `Diagnostic` producer-ы в production path остаются открытым
  техническим долгом D1 из [roadmap.md](roadmap.md).

## 10. Параллелизм и backpressure

**Решение:** проектируем **multithread-ready**, реализуем сначала **однопоточно**.
Параллелизм добавляется позже сменой конфигурации (`concurrency > 1` + executor)
**без переделки ядра** — это обеспечено инвариантами ниже.

Инварианты многопоточной готовности (закладываем сразу):
- **Per-file claim** атомарным move в `processing/` — два воркера не возьмут один
  источник.
- Обработка **stateless и идемпотентна** (ключ — content-hash); переобработка
  безопасна.
- Партиция выхода — отдельный файл по content-hash + atomic temp→rename → нет
  конкуренции на запись между источниками.
- Порты потокобезопасны/по-задачно: `IngestionLedger` — атомарная фиксация,
  `LookupRepository` — неизменяемый снапшот, reader/refanger/extractor — без
  разделяемого состояния.
- **Нет разделяемого счётчика id** в стриминге: id назначается на агрегации
  единым писателем → главный конкурентный риск исключён.
- `Aggregator` — **single-writer** (сериализован) независимо от параллелизма
  инжеста.

Backpressure: bounded `QueueChannel` + `maxMessagesPerPoll`; идемпотентность
делает at-least-once безопасным. Включение параллелизма: `TaskExecutor` на
service-activator + `concurrency > N` — без изменения доменного ядра и формата
выхода.

## 11. Жизненный цикл, остановка, health, деплой

- **Graceful shutdown:** SI-компоненты — `SmartLifecycle`; остановка контекста
  останавливает поллеры, даёт дообработать in-flight, фиксирует ledger. SIGTERM
  от контейнера/systemd → shutdown hook Spring.
- **Health:** health contributors для ledger и JDBC-хранилищ (service + dataframe:
  connect / `user_version` / PRAGMA / `quick_check`) экспонируются по HTTP
  (`/actuator/health`, `/actuator/info`).
  Web-сервер поднимается **только в daemon-режиме**: `DaemonWebEnvironmentPostProcessor`
  флипает `spring.main.web-application-type` в `servlet` по `ioc.runtime.mode=daemon`
  (oneshot/CLI остаётся non-web — one-shot не должен поднимать сервер). По умолчанию
  bind на `127.0.0.1:8081` (`server.address`/`server.port`), expose `health,info`,
  без `shutdown`. Это же — первая точка входа будущего web driving-adapter (ING-8).
- **Деплой:** контейнер (long-running, restart policy) или `systemd`
  (`Restart=always`). Рабочие каталоги монтируются как том.

## 12. Конфигурация (`ioc.ingestion.*`)

```yaml
ioc:
  runtime:
    mode: daemon                  # daemon | oneshot

  observability:
    mode: daemon                  # обычно совпадает с runtime.mode; пишет ioc.mode в логах

  storage:
    service:
      type: jdbc
      url: jdbc:sqlite:./var/ioc-service.db
      sqlite:
        tuning: low-memory
      pool:
        write-max: 1
        read-max: 2

  ingestion:
    dirs:
      inbox: ./var/inbox
      processing: ./var/processing
      done: ./var/done
      failed: ./var/failed
    patterns:
      include: ["*.htm", "*.html", "*.docx"]
      exclude: ["*.tmp", "*.part"]
    detect:
      use-watch-service: true     # гибрид: watch + реконсиляция
      reconcile-interval: 30s
      max-messages-per-poll: 50
    stability:
      quiet-period: 10s           # «тишина» size/mtime перед обработкой
    retry:
      max-attempts: 3
      backoff: 5s
    ledger:
      type: file                  # file | jdbc; СУБД задаётся storage.service.url
      path: ./var/ledger          # file-ledger dir; legacy import source при type=jdbc
    concurrency: 1

  # Бизнес-вывод — в storage.dataframe (canonical SQLite) + sink-проекция
  # (*_generated.csv); формула row_key — в ioc.artifact-identity.artifacts
  # (key-columns / key-mode / epoch). Отдельного блока агрегации/партиций больше нет.

  maintenance:
    retention:
      enabled: false              # безопасный дефолт: ничего не удаляется
      interval: 1h
      initial-delay: 5m
      targets:
        - { name: done,   dir: ./var/done,   max-age: 30d, max-count: 0, action: delete }
        - { name: failed, dir: ./var/failed, max-age: 90d, max-count: 0, action: delete }
        # action: archive требует archive-dir, напр.:
        # - { name: failed, dir: ./var/failed, max-age: 90d, action: archive, archive-dir: ./var/archive }
```

`max-age` — Spring/ISO-длительность (`30d`, `720h`); `max-count: 0` — критерий
по количеству выключен. См. §8.1.

## 13. Библиотеки и module placement

| Назначение | Артефакт | Примечание |
|---|---|---|
| Инжест-каркас | `spring-boot-starter-integration` + `spring-integration-file` | только `adapter-ingest`/`ioc-app`; poller/watch, фильтры, error-channel |
| Ретраи/backoff | `spring-retry` (+ `spring-aspects`, если нужен AOP advice) | только `adapter-ingest` |
| Health/метрики | `spring-boot-starter-actuator` + `spring-boot-starter-web` | health contributors + HTTP `/actuator/health`; web включается **только в daemon** (`DaemonWebEnvironmentPostProcessor`), loopback-bind. См. [dev/0010](dev/0010-health-actuator.md) |
| Хэш содержимого | JDK `MessageDigest` (`SHA-256`) | новой зависимости не требуется |
| Durable ledger + run-ledger + canonical | `spring-jdbc`/`JdbcClient` + `org.xerial:sqlite-jdbc` + Hikari | `ioc.ingestion.ledger.type: file \| jdbc`; service-datasource создаётся в любом daemon (нужен для run-ledger), dataframe-datasource — при `storage.dataframe.type=jdbc` (default); schema через `user_version`, DB health через `quick_check`/PRAGMA |
| Tail (later) | Apache Commons `Tailer` / SI tail producer | descoped для источников (вне домена document-ingest), см. техдолг ING-2 |

## 14. Реализованный контур и расширения

1. Runtime split: `ioc.runtime.mode`, conditional `CliRunner`, daemon-safe
   `main` без unconditional `System.exit`.
2. Порты `IngestSourceUseCase`, `IngestionLedger`, `SourceLifecycle`; command/result
   для whole-file source unit.
3. Модуль `adapters/adapter-ingest`: Spring Integration file flow,
   include/exclude/stability filters, atomic claim, file ledger implementation.
4. Direct-to-canonical artifact writing: per-source `JdbcIocSink` пишет прямо в
   canonical SQLite (rows + `<artifact>_sources`), без partition-staging; CSV —
   проекция (`CsvArtifactProjection`).
5. Recovery/compensation: ingestion-ledger (`CLAIMED → репроцесс`) + run-ledger
   (`DB_COMMITTED → репроекция`), retry/dead-letter, `.error` sidecar упавшего файла.
6. Test contour: unit-тесты статус-переходов, adapter-тесты с `@TempDir`,
   `DataframeRecoveryIntegrationTest` (crash-window/no-data-loss snapshot),
   `DaemonIngestE2ETest` (полный daemon ingest → canonical+`_sources`+проекция),
   golden e2e на проекцию.
7. JDBC service + dataframe storage: `adapter-store-jdbc` — SQLite datasource
   policy, service/dataframe миграции (`user_version`), `JdbcIngestionLedger`,
   `JdbcRunLedger`, `JdbcCanonicalArtifactRepository`, legacy import и DB health.

Также реализовано:

- прямая запись daemon-ингеста в canonical + CSV-проекция, сага write→project
  под `ingest_run` (см. §8);
- единый авторитет id (`IdGenerator(maxId+1)`); stable-id sidecar упразднён;
- artifact-aware `LookupRepository` (JDBC) для masks, `ip_list` и hashes;
- health contributors + HTTP `/actuator/health` (daemon-only, см. [dev/0010](dev/0010-health-actuator.md));
- **retention reaper** (`RetentionService`/`RetentionStore`/`DaemonMaintenanceScheduler`):
  возраст/количество → delete/archive для `done`/`failed` (§8.1).

Позже:

- export-сага (STIX/OpenIOC) — под неё в service-схеме задуман `ingest_run`-каркас;
- web driving-adapter (REST-ингест/запросы, TAXII/STIX) — ING-8;
- tail-источники — **descoped** (вне домена document-ingest, ING-2).

На каждом этапе сборка и тесты остаются зелёными; ядро не меняется.

## 15. Связи

- Daemon- и oneshot-id назначает единый `IdGenerator(start=lookup.maxId()+1)`;
  отдельного stable-id sidecar больше нет (β-коллапс).
- Использует diagnostics/observability как разные подсистемы
  ([cross-cutting.md](cross-cutting.md), [logging.md](logging.md)).
- Модуль `adapters/adapter-ingest` включён в reactor и проверки границ
  ([modularization.md](modularization.md), [boundaries.md](boundaries.md)).

## 16. Паттерны и референсы

- **Enterprise Integration Patterns:** [File Transfer](https://www.enterpriseintegrationpatterns.com/patterns/messaging/FileTransferIntegration.html),
  [Pipes and Filters](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PipesAndFilters.html),
  [Idempotent Receiver](https://www.enterpriseintegrationpatterns.com/patterns/messaging/IdempotentReceiver.html),
  Dead Letter Channel, Process Manager, Aggregator.
- **Spring Integration file support** — основной implementation reference:
  [file inbound adapter / reading files](https://docs.spring.io/spring-integration/reference/file/reading.html),
  persistent accept-once filters, last-modified stability filter, poller/watch,
  error channel/retry.
- **Apache Camel file component** — полезный концептуальный reference для
  аналогичных file-consumer идей (idempotent consumer, move/failed directories),
  но в текущем проекте не нужен как зависимость: Spring Boot + SI уже покрывают
  нужный file-ingest без второго integration framework.
