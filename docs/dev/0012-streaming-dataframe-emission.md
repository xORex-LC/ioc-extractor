# 0012 — Эмиссия и экспорт датафреймов поверх БД-truth (стрим)

## Статус

**Реализовано — C0–C11 закрыты.** Findings F1–F5 и ref-1…9 воплощены в коде:
strict WAL snapshot, callback-streaming JDBC→CSV, атомарный `artifact_revision`,
CAS-backed export saga/recovery, deterministic manifest + `_SUCCESS`, CLI/daemon cadence,
health и delivery-aware slice retention. Финальный контур проверяет concurrent ingest,
duplicate/`SKIPPED`, crash windows, fresh/upgrade migrations, golden slice и архитектурные
границы. Документ остаётся design log и implementation trace для следующего этапа
[0011](0011-remote-sync.md), который потребляет готовые immutable profile slices.

Cross-cutting поверх **реализованного** storage-слоя (ING-4): затрагивает canonical write
revision, JDBC snapshot, CSV/filesystem writer, service ledger и bootstrap lifecycle.
**Переписан** после приземления SQLite-хранилища и
β-коллапса (партиции и отдельный проход агрегации удалены) — прежняя редакция
исходила из «перезаписи canonical-файла», что более неверно. Грунт — текущий код
и [../ingestion.md](../ingestion.md) / [../worknote/storage-layer.md](../worknote/storage-layer.md).

## Контекст

Источник (SMB-шара/каталог) пополняется постоянно, приложение работает в stream.
Исходный вопрос документа — «как сформировать датафрейм над неограниченным потоком,
когда “полного набора” не существует» — **снят storage-слоем**. Сверено с кодом
(текущая реальность):

- **SQLite — система записи.** И oneshot, и daemon пишут прямо в canonical
  (`JdbcIocSink` per-source), дедуп — `INSERT … ON CONFLICT(row_key) DO NOTHING`
  (keep-first). Партиций и отдельной агрегации нет; `AggregationService` исчез,
  его роль втянул `IngestionService`.
- **CSV (`*_generated.csv`) — атомарная проекция из БД** (`CsvArtifactProjection`:
  `SELECT → temp → ATOMIC_MOVE`), не система записи.
- **Проекция идёт по-файлово, синхронно в ingest-саге**: `IngestionService` после
  commit БД в цикле по затронутым артефактам зовёт `projection.project(name)` →
  `markProjectionCompleted` (run-ledger `IngestRun`, фазы `DB_COMMITTED ↔
  PROJECTION_COMPLETED`).
- id — per-artifact `AUTOINCREMENT` + `row_key TEXT UNIQUE`; provenance/observation
  — `<artifact>_sources` (1:N: `first_seen_at`, `last_seen_at`, `occurrences`).
- Export-сага реализована поверх service migration `v5__export_state.sql`:
  `export_run` + `export_progress`, global single-flight, forward recovery и operational read model.

Реализованная задача была узкой и конкретной: **каденс эмиссии** и **контракт
экспорта для удалённых потребителей**. Опора на индустриальную модель якорится
на экспорт, а не на «растущий файл»:

- **Dataflow-модель** (Akidau и др., VLDB 2015; *«Streaming Systems»*; Apache Beam):
  *what / where (окно) / when (триггер+watermark) / how (accumulating vs discarding)*.
- **Output modes** Spark Structured Streaming: `complete` / `append` / `update`.
- Маркер готовности `_SUCCESS` (Hadoop/Spark `FileOutputCommitter`); публикация
  immutable-файлов в data lake.
- Kappa (Jay Kreps) — если когда-нибудь нужны и инкременты, и периодический полный
  список.

## Решения

**1. Формирование датафрейма над стримом — решено storage'ом, это больше не
открытый вопрос.** Непрерывный ограниченный **UPSERT** в БД-truth; БД всегда несёт
консистентный полный набор; консистентный снимок = одна SQLite read-транзакция под **WAL**
(один или несколько `SELECT` в одном snapshot; чтение не блокируется записью). Понятие «дождаться полного набора» снято: полнота
= **as-of текущего состояния БД**. (Прежний барьер «ledger опустел» и страх
«torn copy растущего файла» — сняты как несуществующие в новой модели.)

**2. Две разные эмиссии — не путать (ключевое разделение).**
- **Локальная проекция** `*_generated.csv` — **мутабельный always-fresh полный
  снимок** из БД (для локальных потребителей), атомарная (temp→rename), живёт в
  ingest-саге. Остаётся как есть.
- **Экспорт-для-доставки** — **immutable** срезы (снимок или инкремент) по
  таймстемпу + маркер готовности, для удалённых потребителей ([0011](0011-remote-sync.md)).
  Отдельный концерн = **export-сага** (свой export-run-ledger). Это и есть
  чистый мост к доставке: локальная проекция — для себя, экспорт — наружу.

**3. Контракт экспорта — output-mode первого класса** (словарь Spark):
- `complete` — периодический **полный снимок** (`SELECT … ORDER BY id`); потребитель
  тянет «весь актуальный список»;
- `append` — **инкремент** с прошлого watermark (`WHERE id > lastExportedId`).

**v1 реализует только `complete`** (F4): `append` — спроектированная точка расширения, но
в v1 **не строится**; значение `append` в конфиге **отклоняется fail-fast**
(`EXPORT.UNSUPPORTED_MODE`, FATAL), не молча падает в complete. Доставка ([0011]) к режиму
**агностична**. Полное обоснование `complete`-дефолта и отложенный скоуп `append` — §3a ниже.

**4. Экспортный срез самоописателен (формат — ниже).** Публикуемая единица — **каталог
immutable-файлов одного export-profile** `var/export/<profile>/<ts>__<run_id>/`: identity несёт
**каталог** (`<profile>/<UTC-ts>__<run_id>`,
ref-2; data-файлы внутри — стабильные имена, per-file-таймстемпы сняты); самоописание —
**`manifest.json`** (per-artifact coverage/rows/sha256/identity-epoch, ref-3); **маркер
`_SUCCESS`** последним, держит hash манифеста (ref-4) — потребитель забирает только при маркере.
Цепочка доверия `_SUCCESS` → manifest → файлы закрывает torn-read у потребителя на уровне
контракта. Раскладка и поток материализации — «Формат среза» ниже.

**5. Дедуп / id под инкрементом — id-состояние из БД; watermark-состояние отдельно.**
Per-artifact `id` + `row_key` в БД дают keep-first и монотонный id «из коробки» →
**id-assignment-состояние инкременту не нужно** (прежний вопрос «как назначать id под
append» закрыт storage-слоем). **Но это НЕ значит «append без состояния»:** ему нужен
**watermark/progress-state** — персистентный `lastExportedId` per export-profile; прогресс
доставки каждого потребителя отдельно живёт в 0011 `publish_ledger`. Это состояние и его recovery делают
`append` отдельной отложенной итерацией (F4), а не «бесплатной» опцией.

**6. Export-сага — formation-only; доставка отдельной сагой (F3).** По образцу ingest-саги,
не 2PC: форвард-recovery, идемпотентные шаги, чекпоинты export-run-ledger (`STARTED →
STAGED → AVAILABLE → COMPLETED`; + `FAILED`/`SKIPPED`, ref-7). **`export_run` —
только про формирование**;
терминал `COMPLETED` = срез лежит локально с `_SUCCESS`. **Никакого `PUBLISHED` в
`export_run`.** Граница с доставкой = **файловая система** (`var/export/<profile>/<slice>/` + `_SUCCESS`
+ manifest), не общая таблица. Доставка ([0011]) владеет **отдельным `publish_ledger` ключом
(slice_id, target_id)** (fan-out: один срез → N target'ов независимо); transport `export_run`
**не трогает**; корреляция — по `slice_id` из manifest. Каркас run-ledger-паттерна уже есть
(`IngestRun`/`JdbcRunLedger`) — экспорт зеркалит его (новая таблица + `JdbcExportRunLedger`).
(Recovery — **forward-from-files, не пересчёт из БД**; F1/F5/ref-5.) Детали — «Архитектура реализации».

### 3a. Дефолт output-mode — `complete` (закрывает Q1 и Q3)

**Решение.** Режим экспорта v1 — **`complete`** (полный снимок). `append` — спроектированная
точка расширения под узкий сценарий (высокий поток + дорогой/метрируемый канал + потребитель
ведёт собственное аккумулирующее состояние и не нуждается в отражении удалений), но в **v1
не строится** и **отклоняется fail-fast** в конфиге (F4) — его watermark/delivery-состояние
(реш. 5) делает его отдельной итерацией. **Q3 (поздние данные) снят как следствие**:
`complete` — accumulating, любой снимок отражает текущее состояние БД, понятия «строка уже
уехала и отстала» не возникает.

**Принцип, на который опираемся.** Дефолт — режим, корректный при **самых слабых
допущениях**; оптимизация — opt-in. Режим экспорта не определяет, мутабельны ли
данные, — это свойство самих данных: `complete` **устойчив** к мутации, `append`
**предполагает** иммутабельность строки. Мы не закладываем в контракт ставку на
допущение, которое не гарантируется на горизонте развития проекта. Будущие стадии
обработки (enrich, резолверы) впишутся в ETL **до** записи в truth, но их влияние
на жизненный цикл строки и характер будущих наборов данных заранее неизвестны.
Решение **обратимо**: `append` включается per export-profile, когда появится конкретный
дельта-потребитель (YAGNI — не предугадываем, дефолтимся в всегда-корректное).

**Как устроено.** `complete` = одна read-транзакция под **WAL**, последовательно читающая
настроенный набор артефактов (`SELECT … ORDER BY id`) из общего snapshot → streaming-
сериализация → публикация immutable-каталога (`profile/UTC-ts__run-id` + checksum +
`_SUCCESS`) через export-сагу. Per-target
watermark-состояния **не требуется** (в отличие от `append`, которому нужен
персистентный `lastExportedId` + его recovery). Срез самоописателен (штамп покрытия
as-of `max(id)` / `_created_at ≤ T`).

**Что это даёт.**
- **Корректность по удалениям (решающий довод).** Выбытие IOC (TTL, отзыв
  false-positive, reaper) отражается бесплатно — строки просто нет в следующем
  снимке. `append` удаление выразить **не может** (только добавляет) → удалённый
  IOC навсегда оставался бы в состоянии потребителя. Для blacklist, где выбытие —
  штатная операция, это **корректностный**, а не вкусовой аргумент.
- **Свежесть состояния.** accumulating: любые пост-фактум изменения строки (будущий
  enrich, агрегаты `_sources`) видны в каждом снимке; `append` (discarding) отгрузил
  бы «как на момент создания».
- **Простота.** Нет per-target watermark-состояния и его recovery; срезы
  самодостаточны.
- **Согласованность с кодом.** Локальная проекция уже работает в `complete`-модели
  (полная перепроекция артефакта) — export-сага лишь публикует immutable-снимок,
  без новой концептуальной машинерии.
- **Простой ретеншен.** Каждый `complete`-срез — полная истина → хранить можно
  минимум (вплоть до 1). `append` — это **цепочка**: потеря звена ломает
  реконструкцию → надо хранить всю цепь или вставлять периодические полные
  чекпоинты (скат в Lambda/Kappa-гибрид).

**Риски `complete` и их парирование.**
- **O(N) на триггер; цена растёт с полным размером таблицы**, не с дельтой (для
  только-растущего blacklist — монотонно). → Парируется **каденсом/триггером (Q4)**,
  а не сменой режима.
- **Transfer amplification на доставке ([0011]).** `publish` по SMB пересылает весь
  растущий список каждый цикл — **единственный** сценарий, где `append` объективно
  выигрывает (узкий/метрируемый канал). Отсюда и условие opt-in `append`.
- **WAL под долгим читателем.** Большой полный `SELECT` держит read-транзакцию; при
  крупной таблице + частом ingest WAL может пухнуть/мешать checkpoint. Операционный,
  не корректностный риск → `wal_autocheckpoint` + вменяемый каденс.
- **Размер среза при ретеншене.** Полная копия на снимок; компенсируется
  самодостаточностью (хранить мало).

**Опора (источники).** Output modes Spark Structured Streaming (`complete`/`append`/
`update`; `update` отброшен — плоский reputation-list потребитель не делает
keyed-merge); Dataflow-модель *what/where/when/how* (`complete` = accumulating,
`append` = discarding по оси *how*; ось *where*/окно тривиальна — «окно» = вся
таблица as-of-now); table/stream duality (Kreps) — БД = table-сторона, экспорт =
обратная граница `table → stream`; маркер `_SUCCESS` (Hadoop/Spark
`FileOutputCommitter`).

### 4a. Триггер экспорта — конфиг-стратегия, дефолт `interval` (закрывает Q4)

**Решение.** Триггер экспорта — **декларативная конфиг-выбираемая стратегия** (реестр,
как refang-правила / match-policy / value-providers), а не зашитый механизм:
`ioc.export.trigger.type: interval | quiet-period`.

- **`interval` (processing-time) — дефолт.** Полный снимок раз в N; дёшев,
  предсказуем, ограничивает цену `complete` **сверху независимо от паттерна входа**,
  естественно коалесцирует пачку изменений в один снимок. Всегда безопасен: снимок
  `complete` консистентен as-of, и если тик поймал середину пачки — следующий доберёт
  остаток.
- **`quiet-period` (debounce) — opt-in под бёрстовый вход.** Снимок после **T тишины
  canonical truth** (`artifact_revision.changed_at`) → один аккуратный снимок на пачку,
  максимально свежий, без пересъёмки посреди загрузки. Файловый arrival сам по себе таймер
  не сбрасывает: duplicate, не изменивший truth, не является активностью экспорта.
  **Обязателен параметр max-cap** («не реже чем раз в M, что бы ни происходило») —
  иначе при перекрывающихся источниках тишина не наступает и экспорт голодает
  (starvation).
- **`on-new-rows` (на каждый commit) — сейчас НЕ строим.** Под `complete` это
  анти-паттерн (O(N) на каждый commit → amplification). Добавим позже как
  OCP-расширение, если появится delta-потребитель (связка с opt-in `append`, §3a).

**Триггер реагирует на canonical change, не на вход (ref-6/7).** quiet-period дебаунсит по
*изменению truth*, а не по прилёту файла; interval тоже сверяется. Холостые выстрелы отсекаются
обнаружением изменений (`artifact_revision` pre-gate + content-hash skip) — см. «Обнаружение
изменений / skip». Требует, чтобы canonical write отдавал **фактические** вставки (сумма
`executeUpdate()`, не prepared-count с `ON CONFLICT DO NOTHING`).

**Разделение, которое держим.** Параметры (N, T, M) — **операционный конфиг оператора**,
крутится под ситуацию. Набор **стратегий** и **дефолт** — архитектурное решение
(оператор не настроит механизм, которого нет); дефолт важен как поведение из коробки
для не-тюнящего оператора.

**Параллель с Q1 (§3a).** Та же форма: безопасный дефолт + opt-in под узкий случай.
`complete`/`interval` — поведение v1 из коробки; `quiet-period` — независимая opt-in
стратегия триггера под бёрстовый вход. `append` остаётся будущим output-mode и с выбором
триггера не связывается.

**Контекст входа (на чём основан выбор).** Источники бёрстовые/дискретные: документы
пачками (сейчас) + парсинг сайтов со списками (планируется; апстримы сами обновляются
по расписанию) + файловый импорт остаётся навсегда. Истинно непрерывного потока не
ожидается → сверх-низкая задержка не нужна, `interval`/`quiet-period` уместнее
`on-new-rows`.

**Опора.** Spark `Trigger.ProcessingTime` (микробатч-интервал) / `Trigger.Continuous`;
session-окна Beam/Flink (quiet-period/debounce); комбинатор `linger.ms` + потолок
(Kafka) и early-firings (Beam) — образец под max-cap.

## Следствия

- **Каденс — главный остаточный рычаг.** Локальная проекция сейчас per-file
  синхронно перегенерит **весь** CSV каждого затронутого артефакта (O(строк) на
  файл, в ingest-пути). Под высоким потоком это и есть цена; развязка каденса
  (коалесцирующий/интервальный эмиттер) — лежит здесь, шов готов (`DB_COMMITTED ↔
  PROJECTION_COMPLETED` уже разнесены сагой).
- Новый самостоятельный конфиг-блок `ioc.export`: именованные profiles (набор артефактов),
  output-mode, триггер, корень хранения, формат и retention. `ioc.sync.publish.targets`
  ссылается на profile и не управляет формированием.
- Export-сага использует **свой** export-run-ledger/progress; таблицы создаются
  forward-миграцией `v5__export_state.sql` (v4 удалила прежнюю заготовку `export_run`).
- Доставка ([0011]) читает результат экспорта; output-mode определяется **здесь**.
- `ioc health`/диагностика показывают lag revision / возраст последнего успешного среза.
- Slice-level retention чистит локальные immutable-срезы; retention на приёмнике остаётся
  ответственностью доставки/получателя ([0011](0011-remote-sync.md)).

## Архитектура реализации

Export-сага — **структурный близнец реализованной ingest-саги**; проектируем зеркально,
переиспользуя доказанный паттерн (`IngestionService` / `IngestRun` / `RunLedger` /
`IngestRunRecoveryService`). Bounded context — **«Artifact Emission»**, отдельный от
«Ingestion», но делящий canonical-store (truth). Внутри — два под-капабилити (decision 2):
локальная проекция (`*_generated.csv`, есть, переиспускаем) и export-for-delivery (новое).

### Ubiquitous language
- **Export Run** — инстанс export-саги (близнец *Ingest Run*).
- **Export Slice** — публикуемая единица: каталог immutable-файлов + checksum + маркер
  `_SUCCESS` + манифест со штампом покрытия.
- **Coverage / Watermark** — per-artifact as-of марка (`revision`, `changed_at`, `max(id)`),
  захватывается тем же SQLite snapshot, из которого сериализуются строки. Это provenance,
  а не ключ повторного чтения при recovery.
- **Emitter / Cadence** — обобщённая «частота + спуск» триггера; здесь живёт guardrail
  Q2: абстракция универсальна, ING-7 переиспользует её для локальной проекции.

### Сервисы и порты (`[R]` reuse · `[N]` new)

| Роль | Ингест (есть) | Экспорт |
|---|---|---|
| In-port (use-case) | `IngestSourceUseCase` | `[N] ExportArtifactsUseCase`, `[N] RecoverExportUseCase` |
| Оркестратор | `IngestionService` | `[N] ExportService` (`application/export`) |
| Recovery | `IngestRunRecoveryService` | `[N] ExportRunRecoveryService` |
| Run-модель | `IngestRun`/`IngestRunStatus` | `[N] ExportRun`/`ExportRunStatus`/`ExportCoverage` |
| Run-ledger (out) | `RunLedger` | `[N] ExportRunLedger` (отдельный порт — развилка A; **CAS-переходы + DB-backed single-flight**, ref-5; ретрофит CAS в ingest `JdbcRunLedger`) |
| Запись результата (out) | `ArtifactProjection` | `[N] ArtifactSliceWriter` |
| Чтение truth (out) | `CanonicalArtifactRepository` | `[N] SnapshotSliceReader` — callback-streaming в `ArtifactSliceWriter` **внутри одной read-tx по всем артефактам profile** + per-artifact coverage (F5); `load(name)` остаётся для локальной проекции |
| Watermark `append` (out) | — | `[N] ExportWatermarkStore` — **отложен с `append` (F4)**: per-profile/artifact `lastExportedId`; delivery-progress остаётся в 0011 `publish_ledger`; v1 не строит |
| Change/revision | — | `[N] ArtifactRevisionReader` + `artifact_revision` в dataframe DB; revision меняется атомарно с canonical write |
| Export progress | — | `[N] ExportProgressStore` в service DB: per-profile/artifact `last_revision`/hash/slice/plan-hash |
| Ретеншен | `RetentionPolicy`/scheduler | `[R]` policy/scheduler + `[N] SliceRetentionStore` и `[N] SliceRetentionGuard` (Q5/F2) |
| Наблюдаемость | diagnostics/ECS | `[R]` инфраструктура + `[N] EXPORT-категория/события/health revision-lag |

Маленькие самобытные сервисы: `ExportService` (оркестрация), `ExportRunRecoveryService`
(forward-recovery из staging/final), `ExportChangeDetector` (pre-gate/post-hash) — каждый
узкий. Отдельного `ExportCoverageResolver` **нет**: независимый `max(id)` разорвал бы строгий
snapshot; coverage возвращает только `SnapshotSliceReader` из своей read-транзакции.

### Размещение по модулям (границы держатся)

| Слой / модуль | Добавляется |
|---|---|
| **core/ioc-application** | пакет `application/export` (сервисы, in/out-порты, модель `ExportProfile`/`ExportPlan`/manifest) — framework-free; домен **не трогаем** (`ExportRun` на application-уровне, как `IngestRun`) |
| **adapter-store-jdbc** | `[N] JdbcSnapshotSliceReader`, `JdbcArtifactRevisionReader`, `JdbcExportRunLedger`/`JdbcExportProgressStore`; dataframe-миграция `v3__artifact_revision.sql`; service-миграция `v5__export_state.sql`; переиспользует Hikari/JdbcClient/migrator |
| **adapter-sink-csv** | `[N] CsvArtifactSliceWriter` — immutable срез: `<profile>/<ts>__<run_id>`-каталог, callback-streaming (`ArtifactRow` → `CSVPrinter` → `DigestOutputStream`, single-pass SHA-256+rows, константная память) + staging/atomic rename |
| **adapter-manifest-json-jackson** | `[N] SliceManifestCodec` — единственный владелец Jackson JSON serialization/parsing; сохраняет правило «одна integration-library family на adapter» и переиспользуется 0011 |
| **driving lifecycle (bootstrap)** | `[N]` `DaemonExportScheduler` (`SmartLifecycle` по образцу `DaemonMaintenanceScheduler`, `@ConditionalOnProperty mode=daemon`) → дёргает `ExportArtifactsUseCase`; стратегия каденса — **framework-free `CadenceSource`** (`interval`/`quiet-period`+max-cap, развилка B) |
| **adapter-cli-picocli** | `[N]` команда `ioc export` (ручной on-demand, как `extract`) |
| **bootstrap/ioc-app** | `[N]` конфиг `ioc.export` (profiles + trigger/retention) + composition; service-storage/export graph — `@Lazy` и не ограничен `mode=daemon`: daemon инстанцирует его зависимостями, `ExportCommand` — только при фактическом вызове, обычные `extract`/`health` service DB не открывают (ref-8) |

Локальный slice-каталог (`var/export/<profile>/…`) — **граница с 0011**: target ссылается
на export-profile и byte-for-byte забирает **весь** срез с `_SUCCESS`; per-target фильтрации
файлов нет. Так manifest остаётся валиден, а 0012 — транспорт-агностичным.

### Сага, recovery, консистентность (F1/F5/ref-5)

**Срез определяется записанными файлами, а не повторным чтением БД.** БД читается **ровно
один раз** на run; после верифицируемого staging содержимое фиксировано файлами, и recovery
**никогда не перечитывает БД** — она мутабельна (обновления/удаления строк с теми же id дали
бы другой срез под тем же immutable-именем).

Чекпоинты `ExportRun` (formation-only, F3):
```
STARTED → STAGED → AVAILABLE → COMPLETED
    └──────────────→ SKIPPED                 (+ FAILED из любой незавершённой фазы)
```

- `STARTED` — run/slice-name/profile записаны, single-flight захвачен; staging может быть пуст
  или частично записан.
- `STAGED` — data + manifest + `_SUCCESS` закрыты, `force(true)` выполнен для файлов, staging-
  каталога и его parent; все hashes проверены. Это durable checkpoint внутренних файлов.
- `AVAILABLE` — staging **атомарно** переименован в final-каталог; это единственная точка
  локальной публикации. Staging расположен под тем же `var/export` filesystem; отсутствие
  поддержки `ATOMIC_MOVE` — fail-fast (`EXPORT.ATOMIC_PUBLISH_UNSUPPORTED`), без copy-fallback.
- `COMPLETED` — в одной service-DB транзакции применён `ExportProgress` и выполнен CAS
  `AVAILABLE→COMPLETED`. Для publish готов уже `AVAILABLE`; `COMPLETED` закрывает bookkeeping.
- `SKIPPED` — candidate byte-identical предыдущему срезу; staging удалён, но snapshot revisions
  атомарно продвинуты в `ExportProgress`, чтобы следующий tick не материализовал его повторно.

**Crash-матрица / forward-recovery (БД truth не перечитывается):**

| Наблюдаемое состояние | Recovery |
|---|---|
| `STARTED`, staging без валидного manifest | удалить staging, `FAILED`; следующий запуск создаёт новый run со свежим snapshot |
| `STARTED`, manifest+data валидны, `_SUCCESS` нет | проверить hashes, записать+force `_SUCCESS`, fsync каталога, CAS→`STAGED` |
| `STARTED`, полный валидный staging с `_SUCCESS` | CAS→`STAGED` |
| `STAGED`, staging существует | проверить root hash, atomic rename staging→final, fsync published-parent, CAS→`AVAILABLE` |
| `STAGED`, final существует, staging нет | проверить final по `_SUCCESS`, CAS→`AVAILABLE` (crash после rename) |
| `AVAILABLE` | из manifest идемпотентно применить progress и CAS→`COMPLETED` в одной service-DB транзакции |
| staging/final противоречат manifest или существуют одновременно | `FAILED`, диагностика corruption; автоматически не перезаписывать immutable final |

Durability baseline рассчитан на Linux/systemd deployment и включает `FileChannel.force(true)`
для файлов и fsync каталогов. Если filesystem/JDK не позволяет fsync directory, стартовая
preflight-проверка понижает гарантию до process-crash и выдаёт явный WARN; молчаливой имитации
durability нет.

**Консистентность многоартефактного среза — строгая (F5).** Материализация читает **все
артефакты среза в ОДНОЙ SQLite read-транзакции** (WAL snapshot isolation → все таблицы as-of
одной точки на одном файле БД) → единый консистентный as-of, без «masks@N + hashes@N-1».
Реализуется новым `SnapshotSliceReader` (одно соединение/tx, все артефакты + снимок per-artifact
`revision`/`changed_at`/`max(id)` как coverage), **отдельным от** `load(name)` (тот per-artifact, для локальной
проекции). Отклонён глобальный lock export↔ingest (publish не блокирует пайплайн, 0011 реш. 11).
Трейд-офф: одна read-tx дольше держит снапшот → WAL не чекпойнтится во время чтения (стык с
ref-1 streaming-vs-heap).

**Streaming-контракт между адаптерами (ref-1+F5).** Порт application-уровня
`SnapshotSliceReader.stream(SnapshotRequest, SnapshotRowConsumer)` синхронный и callback-based:

- `JdbcSnapshotSliceReader` владеет connection/read-tx/cursor, в этом же snapshot сначала читает
  coverage всех profile-artifacts, затем вызывает `begin(snapshotMeta)`, `beginArtifact(meta)`,
  `row(ArtifactRow)`, `endArtifact()` у consumer;
- `CsvArtifactSliceWriter` реализует consumer, владеет staging-файлами/CSV/digest, не видит JDBC
  типов и не удерживает `ArtifactRow` после возврата callback;
- callback не может быть async и не закрывает JDBC resources; любое исключение writer'а выходит
  из `stream`, закрывает cursor/transaction и оставляет run в `STARTED` для crash-матрицы;
- `ExportService` оркестрирует вызов, но не читает строки и не управляет IO. Так transaction
  lifecycle остаётся в JDBC adapter, сериализация — в CSV adapter, а heap остаётся O(1).

**Coverage = метаданные, не recompute-ключ.** Per-artifact `revision`/`changed_at`/`upperId`
(+ identity epoch/hash) — в manifest для provenance / lag / базы будущего append; на recovery
**не replay'ится** (срез восстанавливается из файлов).

**Ledger-корректность (ref-5):**
- **CAS на каждом переходе** — `UPDATE … SET status=NEXT WHERE run_id=? AND status=<prev>`,
  проверять `affected==1`. При `0` ledger перечитывает row: тот же/более поздний допустимый
  статус = идемпотентный replay; несовместимый/terminal status = diagnostic+exception, не silent
  no-op. **Заодно ретрофит существующего ingest `JdbcRunLedger`**.
- **Single-flight** — не более одного активного export-run глобально, **DB-backed** partial
  unique-index по `STARTED|STAGED|AVAILABLE`; crash блокирует новый run до recovery. В daemon
  recovery создаётся до `SmartLifecycle` scheduler, `ioc export` всегда сначала вызывает
  `RecoverExportUseCase`, затем пытается стартовать run.

`v5__export_state.sql` создаёт `export_run(run_id, profile, status, slice_name, plan_hash,
manifest_sha256, started_at, updated_at, reason)` + partial unique-index для активных статусов и
`export_progress` с PK `(profile, artifact)`. `manifest_sha256` заполняется на `STAGED`; пути не
хранятся абсолютными — staging/final детерминированно выводятся из настроенного root и slice-name.

**Механика:** staging = `var/export/.staging/<run_id>/`, final =
`var/export/<profile>/<UTC-ts>__<run_id>/`; оба имени фиксируются в `STARTED`. Incomplete не
виден в published-области. После early-crash новый run получает новый `run_id`; stale staging
удаляет только recovery соответствующей ledger-row, не общий opportunistic cleaner.

`append` (отложен, F4) потребует `ExportWatermarkStore` — шов оставлен.

### Формат среза (ref 1/2/3/4)

Раскладка на диске (пример profile `reputation-lists`):
```
var/export/reputation-lists/<UTC-ts>__<run_id>/
  ├── masks_list.csv
  ├── ip_list.csv
  ├── hashes_list.csv
  ├── manifest.json                # самоописание (ref-3)
  └── _SUCCESS                     # commit-маркер последним, держит hash(manifest) (ref-4)
```

- **Identity (ref-2):** `slice_id = run_id`, физический каталог `<profile>/<UTC-ts>__<run_id>` —
  profile задаёт неделимый набор, ts нужен для сортировки/глаз, `run_id` защищает от коллизий
  manual/scheduled export. Per-file-таймстемпы сняты.
- **`manifest.json` (ref-3):** `manifest_version`, `slice_id`/`run_id`, `profile`, `created_at`, `output_mode`,
  `format` (csv: charset/delimiter/quote/null), `artifacts[]` с `file` / `rows` /
  `coverage.revision`/`changed_at`/`upper_id` / `identity_epoch` / `identity_hash` /
  `schema_hash` / `sha256`. `schema_hash` детерминированно считается из ordered public columns
  + declared types; это per-artifact контракт, в отличие от глобального SQLite `user_version`.
  `identity_epoch`/hash и `schema_hash` явно показывают разрыв identity/схемы между срезами.
  JSON кодирует `SliceManifestCodec` из отдельного Jackson-адаптера; ручного JSON writer нет.
- **`_SUCCESS` (ref-4):** пишется **последним**, содержит **SHA-256 манифеста** → цепочка
  доверия `_SUCCESS` → manifest → per-file `sha256` → data. Потребитель и recovery верифицируют
  по одному корню.
- **Reader — streaming (ref-1):** JDBC cursor/callback → `CSVPrinter` → `DigestOutputStream`
  → staging-файл; **константная память** под растущие списки; SHA-256 и row-count — за тот же
  проход (бесплатно для ref-3/4). Трейд-офф WAL — как в F5 (одна read-tx; парируется
  `wal_autocheckpoint` + каденс; ингест не блокируется). Heap-материализация **локальной
  проекции** (`CsvArtifactProjection`) — не трогаем, это ING-7.

- **At-rest трансформы (ref-9):** будущие сжатие/шифрование — **опция формирования**, не v1.
  Когда seam активируется, срез становится `.gz`/`.enc`, manifest описывает алгоритм/key-id и
  покрывает финальные байты. Publish остаётся byte-preserving. Транспортные SMB3 encryption/
  protocol compression прозрачны приложению и end-to-end байты не меняют.

**Поток материализации:** одна read-tx → coverage+поартефактный callback→CSV→digest→staging →
`manifest.json` → SHA-256(manifest) → **`_SUCCESS` последним** → fsync → `STAGED` → atomic rename →
`AVAILABLE` → service-DB transaction(progress+`COMPLETED`).

### Обнаружение изменений / skip (ref-6/7)

Триггер выстреливает *кандидата*; публикуется ли срез — решает обнаружение изменений (нет
холостых идентичных срезов).

- **ref-6 — canonical write возвращает фактические изменения.** `CanonicalArtifactRepository.write`
  становится `CanonicalWriteResult(inserted, revision)`: `inserted` = сумма `executeUpdate()` для
  public artifact rows с `ON CONFLICT DO NOTHING`, не prepared-count и не connection-global
  `changes()`. `JdbcIocSink.write()` возвращает `inserted`; это заодно исправляет `ioc.rows`.
- **Атомарный источник change truth — dataframe `artifact_revision`.** Таблица
  `{artifact PK, revision, changed_at}` создаётся dataframe-миграцией v3. В **той же JDBC write-
  транзакции**, где `inserted>0`, repository делает `revision=revision+1, changed_at=now`; crash
  не может разорвать canonical row и revision. Oneshot и daemon проходят через один repository.
- **Cheap pre-gate.** Для profile сравниваются текущие revisions и `plan_hash` с
  `ExportProgress`; `plan_hash` покрывает ordered artifact-set, schema/identity hashes, CSV format
  и активные transforms. Не изменилось ничего → run не создаётся. Изменение config/schema поэтому
  не может быть ошибочно скрыто неизменным canonical revision.
- **Snapshot authority.** Начавшийся run перечитывает `revision`/`changed_at` **в той же read-tx**,
  где строки, и именно эти значения пишет в manifest. Commit после начала snapshot увеличит
  revision и будет пойман следующим tick; прогресс никогда не записывает revision, которого
  snapshot не видел.
- **Authoritative post-check — content-hash.** После materialization сравниваются per-artifact
  SHA-256 + `plan_hash`. Все совпали → `SKIPPED`: staging удаляется, а snapshot revisions
  продвигаются в `ExportProgress` **в одной service-DB транзакции со статусом SKIPPED**;
  `last_slice`/hash остаются прежними. Поэтому ложный revision не вызывает O(N) на каждом tick.
- **ExportProgress (service DB), ключ `(profile, artifact)`:** `last_revision`,
  `last_sha256`, `last_slice_id`, `plan_hash`, `updated_at`. Для `AVAILABLE→COMPLETED` progress
  восстанавливается из manifest и обновляется атомарно с terminal CAS. Будущий append-watermark —
  отдельное поле/таблица, активируемая только вместе с append.

**Точность v1:** insert-only keep-first → «изменение» = `inserted>0` на **artifact-таблицах**.
**`_sources`-only апдейты** (re-observation: last_seen/occurrences) экспортируемые колонки v1 не
меняют (meta=NULL до OUT-1) → revision на них **не бампаем**; content-hash — бэкстоп. Когда OUT-1
свяжет `time_last_seen` с `_sources`, та же write-транзакция обязана bump revision для затронутого
артефакта — это явный acceptance criterion OUT-1, не необязательный revisit.

### Slice retention и delivery guard (F2)

Retention работает с завершёнными final-каталогами как с единицами; leaf-файлы никогда не
ранжируются/удаляются отдельно. `SliceRetentionStore` перечисляет только валидные каталоги с
`_SUCCESS`, а staging чистит исключительно export-recovery.

Чтобы 0012 можно было реализовать до 0011 без прямой зависимости на sync, application-порт
`SliceRetentionGuard.canDelete(SliceDescriptor)` имеет две композиции:

- **standalone export (0011 ещё нет / sync disabled / нет target для profile):** guard разрешает
  удаление по max-age/max-count;
- **sync enabled:** `PublishLedgerSliceRetentionGuard` из 0011 получает статический набор target'ов
  текущей конфигурации для profile. Отсутствующая pair `(slice_id,target_id)` считается
  **недоставленной** и блокирует delete — discovery/reaper race невозможна. Удаление разрешено,
  только когда все ожидаемые пары `SUCCEEDED` или явно `ABANDONED`; `PENDING/IN_PROGRESS/FAILED`
  держат срез. Permanent failure требует operator `abandon` либо opt-in `force-after`, поэтому
  max-count является best-effort при pinned slices.

Publish scheduler при старте сначала reconciles `готовые slices × configured targets` в
`publish_ledger`, затем становится running; maintenance scheduler имеет более позднюю lifecycle-
phase. Для нового slice missing-pair всё равно блокирует retention до reconciliation. Конфиг
процесса immutable во время lifecycle: добавленный/удалённый target применяется после restart и
повторной reconciliation. Текущий `IN_PROGRESS` статус одновременно служит lease; publisher
делает CAS до открытия файлов, reaper проверяет guard непосредственно перед recursive delete.

### Export profiles и конфигурация

Profile — неделимая единица формирования и publish. Минимальная форма v1:

```yaml
ioc:
  export:
    enabled: true
    root: ./var/export
    trigger: { type: interval, interval: 5m }
    profiles:
      - name: reputation-lists
        output-mode: complete        # append валидатор отклоняет в v1
        artifacts: [ masks, ip_list, hashes ]
      - name: address-blacklist
        output-mode: complete
        artifacts: [ address_blacklist ]
    retention: { max-age: 7d, max-count: 3 }
```

Имена profile и artifact проходят cross-validation с `ioc.sink.artifacts`; порядок artifacts
и output-mode фиксированы profile и входят в `plan_hash`. Один run формирует один profile; global single-flight
сериализует profiles в v1, чтобы не держать несколько долгих SQLite readers. 0011 target содержит
`export-profile`, не `artifacts`, и копирует каталог целиком.

**CLI/service DB (ref-8).** Service datasource, migrations и export beans больше не conditional
только на daemon: они `@Lazy` и conditional на `ioc.storage.service.type=jdbc`. Daemon поднимает
их через ingest/export dependencies; `ExportCommand` получает lazy `ExportArtifactsUseCase`
только внутри `call()` и перед запуском вызывает recovery; `extract`, `health` и `--help` этот
graph не разрешают и `var/ioc-service.db` не открывают. CLI и daemon используют один DB-backed
single-flight и один export root.

### Решённые развилки
- **A — `ExportRunLedger` отдельным портом** (не генерик `RunLedger<R>`). KISS; две саги =
  две таблицы (`ingest_run` + новый `export_run`), симметрично. Генерик — если появится
  третья сага.
- **B — каденс: framework-free `CadenceSource`** (`interval`/`quiet-period`+max-cap),
  Spring-обвязка `SmartLifecycle` в **bootstrap** (по образцу `DaemonMaintenanceScheduler`,
  согласованно с планировщиками 0011 — единое размещение), НЕ отдельный `adapter-scheduler`.
  `CadenceSource` обобщён → исполняет guardrail Q2 (ING-7 и 0011-доставка переиспользуют его).
- **C — `ArtifactSliceWriter` расширяет `adapter-sink-csv`** (переиспускает `CSVPrinter` +
  atomic-move), JSON вынесен в `adapter-manifest-json-jackson` за `SliceManifestCodec`.
  Новый data-format adapter — только если формат разойдётся с CSV (напр. Parquet через
  DuckDB EXP-4 → `adapter-store-duckdb`).

### Наблюдаемость и проверочный контракт

Новая категория `DiagnosticCategory.EXPORT`: минимум `UNSUPPORTED_MODE`,
`SNAPSHOT_READ_FAILED`, `SLICE_WRITE_FAILED`, `MANIFEST_INVALID`, `ATOMIC_PUBLISH_UNSUPPORTED`,
`STATE_TRANSITION_CONFLICT`, `RECOVERY_FAILED`. ECS actions: `export_start`/
`export_complete`, `export_slice_write`, `export_recover`; поля `ioc.export.profile`,
`ioc.export.slice.id`, `ioc.export.revision`. Health показывает last completed/failed run,
возраст среза и revision lag per profile. Коды/поля добавляются вместе с producer'ами и
регистрируются в каталогах/whitelist по действующим конвенциям.

Обязательные тесты до готовности реализации:

- unit: все CAS-переходы и каждая строка crash-матрицы, включая rename-before-ledger;
- JDBC: `artifact_revision` атомарен с insert/rollback; concurrent commit после начала snapshot
  не попадает в срез и остаётся виден следующему pre-gate;
- contract: callback reader держит один snapshot для нескольких artifacts и закрывает resources
  при exception consumer;
- golden: deterministic CSV/manifest/root hash, escaping UTF-8 JSON, schema/plan-hash drift;
- integration: identical candidate → `SKIPPED` с продвижением revision; recovery не читает DB;
- retention: каталог удаляется целиком, missing/pending publish pair блокирует, standalone guard
  разрешает; publish-target всегда получает полный byte-identical profile-slice;
- bootstrap: `ioc export` лениво открывает service DB, `extract`/`health` — нет; CLI+daemon
  single-flight; migration upgrade v4→v5 и fresh DB.

## Открытые вопросы

1. ✅ **Решено — v1 = `complete`; `append` отложен (F4).** Единственный режим v1 — `complete`
   (канонический потребитель firewall/proxy/IDS перечитывает полный список; корректен по
   удалениям и мутабельности). `append` — спроектированная точка расширения, в v1 не строится,
   конфиг reject fail-fast (`EXPORT.UNSUPPORTED_MODE`). Обоснование — §3a.
2. ✅ **Решено — вне scope этой итерации, → ING-7.** Это перф **локальной проекции**
   (`*_generated.csv`), а не часть экспорт-контракта: экспорт читает **DB-truth**
   (`SELECT`), не локальный CSV, и идёт отдельной export-сагой (`export_run`), поэтому
   каденс локальной проекции на доставку не влияет, а будущая инкрементальная проекция
   (ING-7) экспорту прозрачна. Откладывание **не усложняет ING-7**: триггер-стратегия
   из §4a (`interval`/`quiet-period`, коалесцирование) — тот же паттерн, что нужен
   ING-7, → переиспользуется. **Guardrail:** держать абстракцию каденса/триггера
   обобщённой («эмиттер с каденсом», не «только экспорт»), чтобы ING-7 переиспользовал
   её для локальной проекции без переделки.
3. ✅ **Снят (следствие Q1).** При дефолте `complete` (accumulating) поздних данных
   не существует — каждый снимок as-of текущей БД. Актуально лишь для opt-in
   `append`: там обновления уже отгруженной строки осознанно discarding (не уезжают),
   потребитель принимает это как условие выбора режима.
4. ✅ **Решено — триггер = конфиг-стратегия, дефолт `interval`.** `quiet-period`
   (с max-cap) — opt-in под бёрстовый вход; `on-new-rows` отложен. Устройство и
   обоснование — «Решения» §4a.
5. ✅ **Решено — slice-level retention локально; приёмник — концерн доставки (уточнено F2).**
   Локальные срезы в `var/export/...` чистит **slice-granular** retention: единица = завершённый
   каталог (`_SUCCESS`), удаляется **целиком**, `max-age`/`max-count` по **срезам** (не leaf-файлам —
   `FileSystemRetentionStore` как unit-store не годится, нужен slice-store); staging/incomplete
   исключены (их чистит сага), delivery-aware guard трактует отсутствующую pair как pending и
   не реапит срез, пока 0011 не завершил/abandon все target'ы profile. Переиспускаем policy/
   scheduler ING-1; store и guard — slice-level.
   `complete`-срезы самодостаточны → хранить минимум. Ретеншен **на приёмнике** — за доставкой
   ([0011](0011-remote-sync.md) реш. 18).

## План реализации по commit-срезам

**Статус реализации:** C0–C3 закрыты; durable foundation (application-контракты,
canonical revisions/actual write count, CAS export-state и global single-flight) прошёл
полный `verify`. C4–C11 остаются планом следующих волн.

Оптимальная гранулярность — **11 реализационных коммитов плюс отдельный C0 с текущим
дизайном**. Меньшее число смешает schema/storage, filesystem protocol и orchestration в
непроверяемые большие изменения; заметно большее начнёт разрывать единые транзакционные
инварианты. Каждый коммит обязан оставлять reactor зелёным, включать Javadoc и обновлять
README каждого затронутого значимого каталога.

Зависимости срезов:

```text
C0 docs
 ↓
C1 contracts → C2 revision → C3 durable state
 ↓
C4 manifest → C5 snapshot reader → C6 slice filesystem
 ↓
C7 saga → C8 CLI/manual export
 ↓
C9 daemon cadence ─┐
C10 retention ─────┴→ C11 final gate
```

### C0 — зафиксировать ратифицированный дизайн

**Commit:** `DOCS: finalize streaming artifact emission design`

Включает текущие изменения 0012 и согласованные правки 0011. Это baseline реализации:
следующие коммиты не переоткрывают принятые решения без найденного противоречия. Локальный
рабочий файл `Findings` в commit не входит.

**Gate:** `git diff --check`; после commit — чистый tracked worktree.

### C1 — application-модель и порты

**Commit:** `ARCH: introduce artifact export contracts and model`

Добавить в `core/ioc-application`:

- пакет `application/export` и пакеты `port/in/export`, `port/out/export`;
- `ExportProfile`, `ExportPlan`, `ExportMode`, `ExportRun`, `ExportRunStatus`;
- `SliceManifest` и per-artifact metadata/coverage;
- `SnapshotRequest`, `SnapshotMetadata`, `SnapshotRowConsumer`;
- in-порты `ExportArtifactsUseCase`, `RecoverExportUseCase`;
- out-порты `SnapshotSliceReader`, `ArtifactSliceWriter`, `ExportRunLedger`,
  `ExportProgressStore`, `ArtifactRevisionReader`, `SliceManifestCodec`;
- config-neutral модель retention (`SliceDescriptor`, `SliceRetentionGuard`) как seam,
  реализация которого появится в C10.

**Граница ответственности:** только framework-free contracts/invariants. Здесь запрещены JDBC,
Spring, CSV, filesystem orchestration и абсолютные пути. `append` остаётся значением модели для
fail-fast/future seam, но use-case v1 его не выполняет.

**Тесты:** валидация моделей и state invariants; deterministic `plan_hash`; порядок artifacts;
невозможные status/coverage комбинации.

**Focused gate:**

```bash
./mvnw -pl core/ioc-application -am test
```

### C2 — canonical revision и фактический write count

**Commit:** `STORAGE: track canonical revisions and actual writes`

Изменения в core/JDBC write-path:

- dataframe migration `v3__artifact_revision.sql` и регистрация в
  `DataframeFormatMigrations`;
- `artifact_revision(artifact, revision, changed_at)`;
- `CanonicalArtifactRepository.write()` возвращает `CanonicalWriteResult(inserted, revision)`;
- `JdbcCanonicalArtifactRepository` суммирует `executeUpdate()` по public artifact rows;
- при `inserted > 0` bump revision выполняется в той же JDBC transaction;
- `JdbcIocSink.write()` возвращает фактически вставленные строки;
- JDBC-реализация `ArtifactRevisionReader`;
- адаптация legacy importer, test fakes и всех существующих callers нового контракта.

**Не смешивать** с export-сагой: это изменение действующего oneshot/daemon write-path и должно
ревьюиться отдельно.

**Acceptance/tests:** duplicate не меняет revision; insert и revision атомарны; rollback
откатывает оба; fresh DB и upgrade v2→v3; существующие ingest/projection/golden тесты зелёные.

**Focused gate:**

```bash
./mvnw -pl adapters/adapter-store-jdbc,bootstrap/ioc-app -am test
```

### C3 — durable export state, CAS и single-flight

**Commit:** `STORAGE: add CAS-backed export state and single-flight`

Реализовать:

- service migration `v5__export_state.sql`;
- `export_run`, `export_progress`, индексы и partial unique-index активного run;
- `JdbcExportRunLedger`, `JdbcExportProgressStore`;
- CAS каждого перехода с перечитыванием row при `affected=0`;
- атомарный `progress + COMPLETED/SKIPPED` в service DB;
- retrofit CAS в существующий `JdbcRunLedger`;
- reusable contract tests/TCK для export-ledger.

**Acceptance/tests:** допустимые и запрещённые переходы; идемпотентный replay; concurrent start;
crash оставляет active run для recovery; `SKIPPED` продвигает revision; fresh DB и upgrade
v4→v5; несовместимый terminal status выдаёт diagnostic, а не silent no-op.

**Wave gate после C3:**

```bash
./mvnw verify
```

### C4 — deterministic JSON manifest codec

**Commit:** `ADAPTER: add deterministic slice manifest JSON codec`

Создать модуль `adapters/adapter-manifest-json-jackson`:

- Jackson — единственная integration-library family модуля;
- реализация `SliceManifestCodec`;
- deterministic UTF-8 serialization с фиксированным порядком полей;
- round-trip parsing для будущего потребителя 0011;
- manifest hash считается по фактически записанным bytes;
- parent reactor/dependencyManagement/bootstrap dependency;
- module/directory README и ArchUnit boundary.

**Acceptance/tests:** Unicode/escaping/nulls; стабильные golden bytes; round-trip; неизвестная
несовместимая `manifest_version` отклоняется; перестановка runtime-map не меняет output.

**Focused gate:**

```bash
./mvnw -pl adapters/adapter-manifest-json-jackson -am test
```

### C5 — строгий callback-streaming snapshot

**Commit:** `STORAGE: stream consistent multi-artifact snapshots`

Реализовать `JdbcSnapshotSliceReader`:

- одно connection и одна explicit read transaction на profile;
- coverage всех profile-artifacts читается внутри того же snapshot;
- синхронные callbacks `begin/beginArtifact/row/endArtifact`;
- последовательный `ORDER BY id` по всем artifacts;
- connection/transaction/cursor полностью принадлежат JDBC adapter;
- consumer не получает JDBC-типы и не удерживает rows;
- schema/identity/revision metadata возвращаются вместе со snapshot.

**Acceptance/tests:** несколько artifact tables имеют одну as-of точку; commit после начала
snapshot не входит в текущий срез и виден следующему; consumer exception закрывает resources;
нет `CanonicalArtifact`/полной коллекции строк; неизвестный artifact fail-fast.

**Focused gate:**

```bash
./mvnw -pl adapters/adapter-store-jdbc -am test
```

### C6 — CSV staging и атомарная локальная публикация

**Commit:** `SINK: materialize and atomically publish immutable CSV slices`

Реализовать в `adapter-sink-csv`:

- `CsvArtifactSliceWriter` как синхронный `SnapshotRowConsumer`;
- `CSVPrinter → DigestOutputStream`, SHA-256 и row count за один проход;
- staging layout, data files, manifest через codec, `_SUCCESS` с hash manifest;
- `force(true)` для файлов и fsync каталогов;
- операции `stage`, `verify`, `makeAvailable`, filesystem inspection для recovery;
- atomic staging→final rename без copy-fallback;
- diagnostics `SLICE_WRITE_FAILED`, `MANIFEST_INVALID`,
  `ATOMIC_PUBLISH_UNSUPPORTED` вместе с producer'ами.

**Граница ответственности:** writer не знает ledger/status transitions и не читает service DB;
он отвечает только за deterministic bytes и filesystem protocol.

**Acceptance/tests:** deterministic tree/CSV/manifest; `_SUCCESS` последний; final не виден до
rename; corruption обнаруживается; повторная verification идемпотентна; unsupported atomic move
fail-fast; heap не растёт с количеством rows.

**Wave gate после C6:**

```bash
./mvnw -pl adapters/adapter-manifest-json-jackson,adapters/adapter-store-jdbc,adapters/adapter-sink-csv -am test
./mvnw verify
```

### C7 — export saga, change detection и recovery

**Commit:** `FEATURE: orchestrate export saga and forward recovery`

Добавить в `core/ioc-application`:

- `ExportService`, `ExportRunRecoveryService`, `ExportChangeDetector`;
- revision/`plan_hash` pre-gate;
- materialization и authoritative post-hash;
- `STARTED → STAGED → AVAILABLE → COMPLETED`, `SKIPPED`, `FAILED`;
- DB-backed single-flight;
- полную crash-матрицу;
- forward recovery только из staging/final+manifest, без повторного snapshot;
- diagnostics `STATE_TRANSITION_CONFLICT`, `RECOVERY_FAILED` и export ECS events рядом с
  фактическими producer'ами.

**Acceptance/tests:** каждая строка crash-матрицы; manifest без marker; marker до ledger update;
rename до `AVAILABLE`; `AVAILABLE` до progress; corruption; duplicate candidate→`SKIPPED` с
revision advance; recovery fake подтверждает, что `SnapshotSliceReader` не вызван.

**Focused gate:**

```bash
./mvnw -pl core/ioc-application -am test
```

### C8 — config, lazy wiring и ручной CLI export

**Commit:** `FEATURE: expose on-demand artifact export`

Добавить:

- `IocProperties.Export`, profiles/trigger/retention records и defaults;
- cross-validation profiles↔enabled sink artifacts, уникальности имён и порядка;
- fail-fast `EXPORT.UNSUPPORTED_MODE` для `append`;
- `application.yml` с безопасным default;
- lazy service datasource/migrations/export graph, не привязанный только к daemon;
- composition всех новых adapters/ports в `AppConfig`;
- `ExportCommand`, регистрация в root CLI, `ioc export --profile ...`;
- recovery перед ручным запуском;
- CLI/config/application-context integration tests.

**Acceptance:** ручной export создаёт валидный final slice; повтор без изменений получает
`SKIPPED`; unknown profile/artifact и `append` падают до IO; `extract`, `health`, `--help` не
открывают service DB; CLI и daemon корректно конфликтуют через single-flight.

**Milestone:** после C8 существует первый законченный пользовательский путь — on-demand
`complete` export без scheduler/retention.

**Wave gate после C8:**

```bash
./mvnw -pl adapters/adapter-cli-picocli,bootstrap/ioc-app -am test
./mvnw verify
```

### C9 — cadence и daemon scheduler

**Commit:** `FEATURE: schedule exports by canonical cadence`

Реализовать:

- framework-free `CadenceSource`/policy с injected `Clock`;
- `interval`, `quiet-period`, обязательный `max-cap`;
- `DaemonExportScheduler` в bootstrap;
- последовательный обход profiles под global single-flight;
- lifecycle ordering: recovery полностью заканчивается до scheduler start;
- revision/`changed_at` как источник quiet activity;
- overlap guard, controlled shutdown;
- export health: last completed/failed, slice age, revision lag per profile.

**Acceptance/tests:** interval; debounce; duplicate input не сбрасывает quiet-period; max-cap
предотвращает starvation; restart; медленный run не запускается второй раз; failure одного tick
не валит daemon и повторяется следующим tick.

### C10 — slice-level retention и standalone guard

**Commit:** `MAINT: reap completed export slices safely`

Реализовать:

- `SliceRetentionStore` в локальном slice/filesystem adapter;
- `SliceRetentionGuard` + standalone implementation;
- reuse чистой `RetentionPolicy`, но не leaf-file `FileSystemRetentionStore`;
- directory-as-unit delete и `max-count` отдельно по каждому profile;
- staging/incomplete/corrupt final не участвуют в автоматическом delete;
- seam для будущего `PublishLedgerSliceRetentionGuard` из 0011;
- интеграцию с maintenance lifecycle через именованный второй scheduler либо composite
  maintenance use-case — без смешения leaf и slice stores.

**Acceptance/tests:** каталог удаляется целиком; profile pools независимы; guard блокирует;
standalone разрешает; pinned slices делают max-count best-effort; reaper не трогает staging и
не маскирует corruption.

### C11 — полный gate, observability и опубликованные docs

**Commit:** `TEST: gate artifact emission end to end`

Финальный hardening:

- E2E `canonical write → revision → snapshot → CSV → manifest → _SUCCESS`;
- concurrent ingest во время export;
- duplicate/`SKIPPED`;
- все crash windows с fault injection;
- CLI export, daemon cadence, restart, retention;
- migration fresh/upgrade tests;
- ArchUnit: Jackson/JDBC/Spring не протекают внутрь;
- golden export slice;
- полный EXPORT diagnostic catalog, ECS whitelist и health assertions.

Обновить опубликованные документы: `architecture.md`, `modularization.md`, `ingestion.md`,
`diagnostics.md`/generated catalog, `techdebt.md`, roadmap/status 0012 и directory README.

**Final gate:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Реализация C11:** E2E объединяет canonical write, revision, real JDBC snapshot,
CSV/manifest/marker, duplicate skip и concurrent commit с catch-up run. Остальная
матрица распределена по ответственностям: recovery crash windows — application,
atomic publication/corruption — CSV adapter, snapshot isolation и fresh/upgrade
migrations — JDBC adapter, cadence/health/retention/ECS — bootstrap/platform.
Golden CSV хранится отдельной test resource; generated diagnostic catalog и
ArchUnit rules входят в общий `verify`.

## Группировка для реализации

Рекомендуются **четыре отдельные волны** с новым контекстом на границе каждой волны:

| Волна | Коммиты | Владелец ответственности | Результат / handoff |
|---|---|---|---|
| A — Durable foundation | C1–C3 | application contracts + JDBC state | Стабильные порты, revision, CAS-ledger/progress; полный `verify` |
| B — Slice formation | C4–C6 | JSON codec + JDBC snapshot + CSV/filesystem | Детерминированный staged/available slice без application orchestration |
| C — Vertical MVP | C7–C8 | application saga + bootstrap/CLI | Рабочий ручной `ioc export` с recovery и skip |
| D — Operations | C9–C11 | cadence + retention + health/gates | Полный daemon-ready контур и опубликованные docs |

Пакет handoff для следующего агента обязан содержать:

- диапазон уже готовых commit hash и чистый `git status`;
- список зафиксированных public interfaces и migrations;
- разрешённые для следующей волны модули;
- результаты focused tests и последнего `./mvnw verify`;
- известные seams следующей волны без предложения менять уже закрытые решения;
- явное указание сохранять чужие/пользовательские изменения и не включать unrelated files.

Правила работы внутри волны:

1. Один commit = один перечисленный выше инвариант; не squashing разных slices до review.
2. Сначала focused tests текущего модуля, на конце волны — полный `verify`.
3. Diagnostic code/event/field добавляется в том же commit, где появляется producer.
4. Migration создаётся один раз и после commit не переписывается; исправления — следующей версией.
5. Новая значимая директория сразу получает README, сложные public/module classes — Javadoc.
6. Bootstrap содержит только composition/lifecycle; SQL остаётся JDBC adapter, CSV/filesystem —
   sink adapter, saga/policy — application.
7. Перед handoff агент сверяет acceptance criteria своего диапазона с этим разделом построчно.

Безопасный параллелизм возможен только в отдельных worktree/ветках:

- после C3 C4 (manifest codec) и C5 (snapshot reader) почти независимы, но должны быть слиты до C6;
- после C8 C9 (cadence) и C10 (retention) концептуально независимы, однако оба меняют
  `AppConfig`/`IocProperties`, поэтому для одного worktree выполняются последовательно;
- C2/C3 и C6/C7 параллелить нельзя: они делят migrations/contracts и filesystem/saga protocol.
