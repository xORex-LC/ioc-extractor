# Стриминговый инжест (демон)

Детальный дизайн основного режима работы — долгоживущего сервиса, который
постоянно `active`, обнаруживает новые источники и обрабатывает их без потери
данных и без двойной обработки. CLI-режим «один прогон» сохраняется как
вторичный. Обоснование выборов — в [notes/0001-streaming-ingestion.md](notes/0001-streaming-ingestion.md).

## 1. Режимы запуска

| Режим | Профиль | Поведение |
|---|---|---|
| `oneshot` | `oneshot` (по умолчанию для CLI) | `ioc extract --source <file>` → один прогон → выход |
| `daemon` | `daemon` | Сервис не завершается; SI-поток + планировщик держат контекст живым |

Выбор — через Spring-профиль (`--spring.profiles.active=daemon`). В `daemon`
**нет** `System.exit`: жизнь процесса поддерживают компоненты Spring Integration
(`SmartLifecycle`). CLI-runner активен только в `oneshot`.

## 2. Размещение в архитектуре

Демон — **driving-адаптер** поверх неизменного `ExtractIocsUseCase`.

```
[inbox dir] ─▶ adapter/in/watch (Spring Integration)
                   │  detect → stabilize → claim → read(SourceFeed)
                   ▼
              ExtractIocsUseCase  (ядро, без изменений)
                   │  refang → extract → attribute → dedup
                   ▼
              IocSink → partitions/  ──▶ Aggregator ──▶ dataframe/ (канон. артефакты)
   out-порты:  SourceFeed | IngestionLedger | IocSink | LookupRepository
```

**Новые порты**

| Порт | Тип | Назначение |
|---|---|---|
| `SourceFeed` | in (driving) | Поток единиц-источников: whole-file и tail-записи |
| `IngestionLedger` | out (driven) | Durable-журнал обработанного (идемпотентность, восстановление) |

Spring Integration живёт **только** внутри `adapter/in/watch`, за портом
`SourceFeed` — ядро остаётся framework-free, SI заменяем.

## 3. Детект появления (гибрид)

`FileReadingMessageSource` Spring Integration с `setUseWatchService(true)` даёт
**ровно гибрид**: события WatchService (inotify) для низкой латентности +
встроенный poller как периодическая **реконсиляция** (полный скан — страховка от
потерянных событий и ненадёжности на смонтированных/сетевых ФС).

- Интервал реконсиляции и `maxMessagesPerPoll` — конфигурируемы.
- На сетевых/overlay-ФС WatchService может молчать — реконсиляция гарантирует
  обнаружение.

## 4. Два типа источников за `SourceFeed`

| Тип | Механизм SI | Случай |
|---|---|---|
| **Whole-file** | `FileReadingMessageSource` (+ фильтры) | Дискретный документ целиком (как `ioc-source.htm`) |
| **Tail** | `FileTailingMessageProducer` (Apache Commons `Tailer`) | Дозапись новых строк/записей в растущий фид; обработка ротации, трекинг смещения |

Обе реализации отдают наружу единицы `SourceFeed` единообразно; обработчик
(service-activator) вызывает use case. `commons-io` (Tailer) уже в проекте.

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

- Перемещение в `processing/` = эксклюзивный «клейм» источника.
- При старте сервиса всё, зависшее в `processing/` (признак падения), возвращается
  на переобработку.
- Каталоги — конфигурируемы; по умолчанию под общим рабочим корнем.

## 7. Идемпотентность и защита от потери данных

**Двухслойный дедуп:**
1. SI `FileSystemPersistentAcceptOnceFileListFilter` + persistent `MetadataStore`
   — дёшево, по `имя+mtime`.
2. Наш `IngestionLedger` по **content-hash (sha256 файла)** — ловит
   переименования и повторные дропы того же содержимого.

**Порядок коммита (критично):**
```
read → process → write partition (temp → ATOMIC_MOVE) → record ledger → move source → done/
```
Падение на любом шаге безопасно: источник остаётся в `inbox/`/`processing/` →
переобработка → тот же content-hash → та же партиция (перезапись) + дедуп ledger
→ **дублей на выходе нет**. Это «at-least-once доставка + идемпотентная
обработка = effectively-once на выходе».

**Атомарность записи:** любой файл (партиция, ledger) пишется в `*.tmp` и
переименовывается `ATOMIC_MOVE` — частичных файлов при падении не возникает.

## 8. Выход: партиции + агрегация

**Партиции держим отдельно от канональных артефактов** — в выделенной
поддиректории (по умолчанию `dataframe/partitions/`, конфигурируемо, в
`.gitignore`), чтобы не смешивать с выходными артефактами в `dataframe/`.

```
dataframe/
├── partitions/                      ← промежуточные, идемпотентные (gitignored)
│   ├── masks/<date>/<content-hash>.csv
│   └── hashes/<date>/<content-hash>.csv
├── repListMasks.csv                 ← канонический артефакт (результат агрегации)
└── hashes.csv
```

- **Запись:** обработанный источник → партиция с ключом `content-hash`.
  Переобработка перезаписывает ту же партицию → идемпотентно, без гонок на
  дозапись.
- **Агрегация:** отдельный процесс (`Aggregator`, по расписанию или по событию)
  сводит партиции в канонический артефакт с **глобальной дедупликацией и
  назначением id единым писателем** — это закрывает TODO про `id auto` (нет
  отрицательных/конкурентных id).
- Канонические артефакты в `dataframe/` остаются совместимыми с текущим форматом
  и используются как lookup.

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
- **Источник статуса:** `Aggregator` помечает партицию как `AGGREGATED` (отметка
  в ledger/sidecar); `PartitionReaper` читает только эту отметку, сам ничего не
  агрегирует.
- **Варианты политики:** `delete` | `archive` (перенос в холодное хранилище) |
  `compress`; критерий — по возрасту, по флагу `aggregated`, по суммарному объёму.
- После очистки источник истины — канонический артефакт (+ ledger по content-hash
  для дедупа повторных дропов).

## 9. Ошибки, ретраи, dead-letter

- SI `error-channel` + `RequestHandlerRetryAdvice` (spring-retry): экспоненциальный
  backoff, N попыток.
- После исчерпания — источник в `failed/` + sidecar `.<имя>.error` с диагностикой.
- Один «ядовитый» файл не блокирует поток (политика `collect-and-continue`).
- Категоризация и трансляция ошибок — сквозная подсистема, см.
  [cross-cutting.md](cross-cutting.md).

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
- **Health:** Spring Boot Actuator (`/actuator/health`, liveness/readiness) +
  Micrometer-метрики (обработано/в очереди/ошибки). Для systemd — опц.
  heartbeat-файл/`Type=notify` (см. открытый вопрос в notes).
- **Деплой:** контейнер (long-running, restart policy) или `systemd`
  (`Restart=always`). Рабочие каталоги монтируются как том.

## 12. Конфигурация (`ioc.ingestion.*`)

```yaml
ioc:
  ingestion:
    mode: daemon                 # daemon | oneshot
    dirs:
      inbox: ./var/inbox
      processing: ./var/processing
      done: ./var/done
      failed: ./var/failed
    patterns:
      include: ["*.htm", "*.docx", "ioc-*.*"]
      exclude: ["*.tmp", "*.part"]
    detect:
      use-watch-service: true     # гибрид: watch + реконсиляция
      reconcile-interval: 30s
      max-messages-per-poll: 50
    stability:
      quiet-period: 10s           # «тишина» size/mtime перед обработкой
    tail:
      enabled: false              # включить для растущих фидов
      files: []
    output:
      partitions-dir: ./dataframe/partitions
      partition-by: [date, source-hash]
    aggregation:
      schedule: 1m                # сведение партиций в канон. артефакт
    retention:                    # очистка партиций — опциональна, по умолчанию off
      enabled: false
      mode: delete                # delete | archive | compress
      after: aggregated           # удалять только AGGREGATED-партиции
      grace: 24h                  # минимальный возраст до очистки
      archive-dir: ./dataframe/partitions-archive
    retry:
      max-attempts: 3
      backoff: 5s
    ledger:
      type: file                  # file | sqlite
      path: ./var/ledger
    concurrency: 1
```

## 13. Библиотеки (финальный набор к добавлению)

| Назначение | Артефакт | Примечание |
|---|---|---|
| Инжест-каркас | `spring-boot-starter-integration` + `spring-integration-file` | poller/watch, фильтры, tail, error-channel |
| Ретраи/backoff | `spring-retry` (+ `spring-aspects`) | advice для SI |
| Health/метрики | `spring-boot-starter-actuator` | liveness/readiness; для HTTP-эндпойнта нужен management-веб-сервер |
| Хэш содержимого | `commons-codec` (`DigestUtils`) | content-hash для ledger/партиций |
| Durable ledger (опц.) | `org.xerial:sqlite-jdbc` + `spring-integration-jdbc` | если файлового metadata-стора станет мало |
| Tail | Apache Commons `Tailer` | уже есть через `commons-io` |

> Actuator поверх non-web приложения для HTTP-health требует management
> веб-сервера — отдельное решение по деплою (см. notes, открытые вопросы).

## 14. Поэтапное внедрение

1. Порты `SourceFeed` + `IngestionLedger`; адаптер `adapter/in/watch` (whole-file,
   гибрид-детект, автомат каталогов, content-hash ledger). Профиль `daemon`.
2. Партиционная запись `IocSink` в `dataframe/partitions/` + `Aggregator` →
   канонический артефакт с глобальными id.
3. Ретраи/dead-letter + Actuator health + graceful shutdown.
4. Tail-источники (`FileTailingMessageProducer`).
5. (Опц.) SQLite-ledger; параллелизм пулом.
6. (Опц., в стороне) `PartitionReaper` за портом `RetentionPolicy` — очистка/архив
   подтверждённо сагрегированных партиций. Seam готов с этапа 2.

На каждом этапе сборка и тесты остаются зелёными; ядро не меняется.

## 15. Связи

- Закрывает TODO `id auto` ([architecture.md](architecture.md)) через агрегацию.
- Использует сквозную подсистему ошибок ([cross-cutting.md](cross-cutting.md)).
- `adapter/in/watch`, `platform/*`-кандидаты — учесть при нарезке модулей
  ([modularization.md](modularization.md)) и в проверках границ
  ([boundaries.md](boundaries.md)).
