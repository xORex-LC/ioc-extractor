# Стриминговый инжест (демон)

Детальный дизайн основного режима работы — долгоживущего сервиса, который
постоянно `active`, обнаруживает новые источники и обрабатывает их без потери
данных и без двойной обработки. CLI-режим «один прогон» сохраняется как
вторичный. Обоснование выборов — в [dev/0001-streaming-ingestion.md](dev/0001-streaming-ingestion.md).

> Текущий контур: проект — Maven-реактор; generic pipeline находится в
> `platform/platform-etl`, IOC use cases и stages — в `core/ioc-application`,
> технические входы — отдельные `adapter-*` модули. Реализованы whole-file
> daemon ingestion, partition output, scheduled aggregation в canonical CSV,
> stable id sidecar, artifact-aware lookup и минимальный health-контур. Tail,
> retention/reaper и SQLite/JDBC ledger/index остаются следующими расширениями.

## 0. Реализованный scope 0.1.0

File-ingest реализован как driving-adapter поверх существующего
`ExtractIocsUseCase`. Spring Integration остаётся в `adapter-ingest`, ядро
application/domain не знает о SI, а идемпотентность строится вокруг durable
ledger и source-scoped партиций.

Ключевые runtime-решения:

- **Режим приложения** задаёт `ioc.runtime.mode: oneshot | daemon`; observability
  имеет свой `ioc.observability.mode` и используется для лог-профиля/полей.
- В `daemon` CLI runner выключен, а процесс живёт вместе со Spring context и
  Spring Integration flow.
- `IocSink` API не расширен контекстом источника. В daemon-режиме bootstrap
  подставляет partition wrapper/factory в adapter layer: путь партиции строится
  по source-key/content-hash и делегирует запись существующему CSV mapper/sink.
- Глобальные stable id назначает single-writer aggregation через sidecar
  `artifact;key;id;created_at;updated_at`.

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
                   │  ledger/status/recovery
                   ├──────────────▶ SourceLifecycle (claim/archive/fail)
                   └──────────────▶ ExtractIocsUseCase (pipeline без доменных изменений)
                   │  refang → extract → attribute → dedup
                   ▼
              IocSink via partition wrapper → partitions/
                                        └── AggregationService → dataframe/
   driving:  IngestSourceUseCase, ExtractIocsUseCase, AggregatePartitionsUseCase
   driven:   IngestionLedger | SourceLifecycle | IocSink | LookupRepository | aggregation storage ports
```

**Новые порты**

| Порт | Тип | Назначение |
|---|---|---|
| `IngestSourceUseCase` | in (driving) | Приём одной единицы-источника на обработку (вызывается watch-адаптером) |
| `IngestionLedger` | out (driven) | Durable-журнал **статусов** обработки (идемпотентность, восстановление) |
| `SourceLifecycle` | out (driven) | Claim/archive/fail источника через атомарные операции ФС; application не знает SI |
| `PartitionSinkFactory` | out (driven) | Создание daemon-scoped `IocSink`-ов для source-key/content-hash; реализация в `adapter-sink-csv` |
| `AggregatePartitionsUseCase` | in (driving) | Запуск single-writer aggregation из готовых партиций в канонические артефакты |
| `PartitionArtifactRepository` | out (driven) | Чтение source-scoped partition CSV как storage-neutral rows |
| `CanonicalArtifactRepository` | out (driven) | Чтение/атомарная запись канонических артефактов |
| `StableIdIndex` | out (driven) | Stable id per artifact-key; текущая реализация — sidecar CSV |
| `ArtifactIdentityResolver` | out (driven) | Artifact-specific key extraction; живёт в adapter layer, потому что зависит от схемы артефакта |
| Partition sink wrapper | adapter wrapper | Daemon-only обёртка над CSV sink/path resolver; пишет партиции без расширения core `IocSink` API |

`SourceFeed` — **не порт ядра**, а adapter-local абстракция внутри
`adapter-ingest` над Spring Integration (whole-file/tail). Наружу единицы
отдаются через driving-порт `IngestSourceUseCase`; ядро остаётся framework-free,
SI заменяем без влияния на ядро.

Размещение по модулям:

| Модуль | Что добавляется |
|---|---|
| `core/ioc-application` | `IngestSourceUseCase`, ingestion command/result, `IngestionLedger`, `SourceLifecycle`, `PartitionSinkFactory`, orchestration service |
| `adapters/adapter-ingest` | Spring Integration file flow, stability filters, `SourceLifecycle` FS implementation, file ledger implementation |
| `adapters/adapter-sink-csv` | partition wrapper/path resolver поверх существующей CSV-записи; core `IocSink` API не расширяется на этапе 10 |
| `bootstrap/ioc-app` | conditional wiring: `oneshot` CLI vs `daemon` ingest, config binding, app lifecycle |

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
CLAIMED ─▶ PARTITION_WRITTEN ─▶ LEDGER_RECORDED ─▶ SOURCE_ARCHIVED ─▶ (AGGREGATED)
```
Каждый переход фиксируется durable **до** выполнения следующего шага.

**Восстановление (компенсации) при старте — по последнему статусу:**

| Последний статус | Состояние | Компенсация |
|---|---|---|
| `CLAIMED` | в `processing/`, партиция не записана | переобработать (write → …) |
| `PARTITION_WRITTEN` | партиция есть, в ledger не зафиксировано | дозаписать запись ledger (идемпотентно по ключу) |
| `LEDGER_RECORDED` | зафиксировано, источник не перемещён | **довести до `SOURCE_ARCHIVED`** (move в `done/`) |
| `SOURCE_ARCHIVED` | завершено | ничего |

Все шаги **идемпотентны**: партиция перезаписывается по ключу, запись ledger —
upsert по ключу, перемещение — `ATOMIC_MOVE` (уже перемещён → no-op). Поэтому
случай «ledger сказал обработано, а источник ещё в `processing/`» не теряется:
компенсация доводит до `SOURCE_ARCHIVED`. Итог — at-least-once доставка +
идемпотентные шаги = effectively-once на выходе.

**Атомарность записи:** партиция и записи ledger пишутся `*.tmp` → `ATOMIC_MOVE` —
частичных файлов при падении не возникает. После обработки источник доводится до
`SOURCE_ARCHIVED`; после успешной агрегации запись получает `AGGREGATED`.

## 8. Выход: партиции + агрегация

**Партиции держим отдельно от канональных артефактов** — в выделенной
поддиректории (по умолчанию `dataframe/partitions/`, конфигурируемо, в
`.gitignore`), чтобы не смешивать с выходными артефактами в `dataframe/`.

```
dataframe/
├── partitions/                      ← промежуточные, идемпотентные (gitignored)
│   ├── masks/<date>/<content-hash>.csv
│   ├── ip_list/<date>/<content-hash>.csv
│   ├── address_blacklist/<date>/<content-hash>.csv
│   └── hashes/<date>/<content-hash>.csv
├── masks_list_generated.csv         ← канонические артефакты (результат агрегации)
├── ip_list_generated.csv
├── address_blacklist_generated.csv
└── hashes_list_generated.csv
```

- **Запись:** обработанный источник → партиция. Ключ партиции зависит от режима:
  whole-file — `content-hash`; tail — checkpoint-id (см. §7). Переобработка
  перезаписывает ту же партицию → идемпотентно, без гонок на дозапись.
- Daemon пишет source-scoped партиции, а `AggregationService` по расписанию
  вызывает application use case `AggregatePartitionsUseCase`, читает готовые
  `SOURCE_ARCHIVED` записи из ledger и сводит партиции в канонические артефакты
  **единым писателем**.
- **Граница ответственности:** агрегатор — process manager, а не дедупликатор.
  Identity/upsert вынесены за отдельные контракты:
  `ArtifactIdentityResolver`, `StableIdIndex`, `CanonicalArtifactRepository`,
  `PartitionArtifactRepository` и `ArtifactMergePolicy`.
- **Стабильные id:** решаются через sidecar CSV
  `ioc.aggregation.id-index.path` (`artifact;key;id;created_at;updated_at`).
  Известный artifact-key сохраняет id при повторной агрегации; новый ключ
  получает следующий id. Миграция sidecar CSV на SQLite/JDBC — технический долг.
- **Conflict policy:** реализован безопасный `keep-first`; более богатая модель
  merge/update — отдельный долг.

### 8.1 Жизненный цикл и очистка партиций (retention)

Партиция проходит состояния:

```
WRITTEN ──aggregate──▶ AGGREGATED ──(grace)──▶ PURGED | ARCHIVED
```

Очистку выносим в **отдельный опциональный планировщик** `PartitionReaper` за
портом `RetentionPolicy`. Он не связан с основным потоком инжеста/агрегации и
добавляется «в стороне» — **seam зарезервирован сразу, реализация может прийти
позже**.

- **Безопасный дефолт:** retention **выключен** (`enabled: false`) — без явного
  включения ничего не удаляется.
- **Безопасность очистки:** удаляются только партиции со статусом `AGGREGATED`
  (их содержимое подтверждённо влито в канонический артефакт и зафиксировано в
  `IngestionLedger`) **и** старше grace-периода. Не-сагрегированные партиции не
  трогаются никогда — это исключает потерю данных.
- **Источник статуса:** `AggregationService` помечает source record как
  `AGGREGATED` в `IngestionLedger`; `PartitionReaper` в будущем должен читать
  только эту отметку, сам ничего не агрегируя.
- **Варианты политики:** `delete` | `archive` (перенос в холодное хранилище) |
  `compress`; критерий — по возрасту, по флагу `aggregated`, по суммарному объёму.
- После очистки источник истины — канонический артефакт (+ ledger по content-hash
  для дедупа повторных дропов).

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
- **Health:** этап 11 добавляет `spring-boot-starter-actuator` и health
  contributors для ledger, artifact storage и последней aggregation state.
  HTTP-exposure остаётся deployment/config решением: приложение по умолчанию
  остаётся non-web.
- **Деплой:** контейнер (long-running, restart policy) или `systemd`
  (`Restart=always`). Рабочие каталоги монтируются как том.

## 12. Конфигурация (`ioc.ingestion.*`)

```yaml
ioc:
  runtime:
    mode: daemon                  # daemon | oneshot

  observability:
    mode: daemon                  # обычно совпадает с runtime.mode; пишет ioc.mode в логах

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
    output:
      partitions-dir: ./dataframe/partitions
    retry:
      max-attempts: 3
      backoff: 5s
    ledger:
      type: file                  # file | sqlite
      path: ./var/ledger
    concurrency: 1

  aggregation:
    enabled: true
    interval: 1m
    initial-delay: 10s
    id-index:
      path: ./dataframe/.ioc-id-index.csv
    artifacts:
      - name: masks
        key-columns: [ mask ]
        conflict-policy: keep-first
      - name: ip_list
        key-columns: [ ip ]
        conflict-policy: keep-first
      - name: address_blacklist
        key-columns: [ forbidden_url, forbidden_ip ]
        key-mode: first-non-empty
        conflict-policy: keep-first
      - name: hashes
        key-columns: [ hash_md5, hash_sha1, hash_sha256 ]
        key-mode: first-non-empty
        conflict-policy: keep-first
    retention:
      enabled: false              # seam only; implementation postponed
```

## 13. Библиотеки и module placement

| Назначение | Артефакт | Примечание |
|---|---|---|
| Инжест-каркас | `spring-boot-starter-integration` + `spring-integration-file` | только `adapter-ingest`/`ioc-app`; poller/watch, фильтры, error-channel |
| Ретраи/backoff | `spring-retry` (+ `spring-aspects`, если нужен AOP advice) | только `adapter-ingest` |
| Health/метрики | `spring-boot-starter-actuator` | health contributors есть с этапа 11; для HTTP endpoint нужен management web server/deployment config |
| Хэш содержимого | JDK `MessageDigest` (`SHA-256`) | новой зависимости не требуется |
| Durable ledger (later) | `org.xerial:sqlite-jdbc` + `spring-integration-jdbc` | если файлового metadata-store станет мало |
| Tail (later) | Apache Commons `Tailer` / SI tail producer | не входит в baseline 0.1.0 |

> Actuator поверх non-web приложения для HTTP-health требует management
> веб-сервера — отдельное решение по деплою (см. dev-документы, открытые вопросы).

## 14. Реализованный контур и расширения

1. Runtime split: `ioc.runtime.mode`, conditional `CliRunner`, daemon-safe
   `main` без unconditional `System.exit`.
2. Порты `IngestSourceUseCase`, `IngestionLedger`, `SourceLifecycle`; command/result
   для whole-file source unit.
3. Модуль `adapters/adapter-ingest`: Spring Integration file flow,
   include/exclude/stability filters, atomic claim, file ledger implementation.
4. Partition-aware artifact writing: отдельный partition wrapper в adapter layer,
   чтобы daemon писал в `dataframe/partitions/...` без изменения domain logic и
   без расширения core `IocSink` API на этапе 10.
5. Recovery/compensation по статусам, retry/dead-letter, sidecar error file.
6. Test contour: unit tests for ledger/status transitions, adapter tests with
   `@TempDir`, daemon e2e for duplicate drop/restart compensation, golden check
   against partition content.

Также реализовано:

- `AggregationService` / `AggregatePartitionsUseCase` → канонические артефакты
  с stable id sidecar;
- artifact-aware `LookupRepository` для masks, `ip_list` и hashes;
- health contributors для daemon runtime.

Позже:

- tail-источники (`FileTailingMessageProducer`);
- SQLite/JDBC ledger и stable id index; параллелизм пулом;
- `PartitionReaper` за портом `RetentionPolicy` — очистка/архив подтверждённо
  сагрегированных партиций.

На каждом этапе сборка и тесты остаются зелёными; ядро не меняется.

## 15. Связи

- Daemon-side stable ids закрыты через sidecar index; legacy
  `id.start:auto` для oneshot остаётся совместимым с lookup `maxId()`.
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
