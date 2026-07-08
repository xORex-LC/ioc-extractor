# 0014 — Event-driven координация всей цепочки: ingest → export → delivery

## Статус

**Принято, не реализовано.** Design-решение по итогам анализа поверх
реализованного [0013](0013-event-driven-coordination.md) (event-координация,
S0–S8 + CHANGE_NOTIFY). Документ **расширяет** 0013 на цепочку
`ingest → export → delivery`, **не заменяя** его: та же доктрина (события —
подсказка, ledger+reconcile — истина, per-consumer durable ledger, keyed
single-flight, anti-broker), новые точки применения. Фиксирует **базу для
рефакторинга и миграции**; кода ещё нет. Обоснование модели «когда/как» — в
[../dev/event-coordination.md](../dev/event-coordination.md).

**Обновление 2026-07-05.** Р1/Р2 реализованы: export recovery теперь эмитит
`SliceCompleted` (`49bd68b`), ingest эмитит `CanonicalArtifactsChanged`
(`d28c74e`), `DaemonExportScheduler.nudge()` и Spring listener соединяют ingest
с export fast-path (`e265b0d`, `294b67c`). Follow-up loop дополнительно защищён
от idle-состояний без pending-работы (`aefb593`). Р3/Р4 остаются design-state.

**Обновление 2026-07-06.** Оба fast-path подтверждены эмпирически на тестовом
стенде (daemon, ECS-лог): Р2 — событие ingest → nudge-чек ровно через QP →
export → publish за сотни мс; Р1 — crash-симуляция (SIGKILL, run возвращён в
`AVAILABLE`) дала при рестарте `export_recover → export_complete →
event_publish → доставка через 140 мс`, причём до строки
`Started IocExtractorApplication` — эмиссия на phase 100 доходит до executor'а
ещё в процессе старта. От краша до передоставленного среза — <12 с (против
reconcile-тика до Р1).

## Контекст

0013 задал доктрину и реализовал её для sync (fetch — detection⊥execution +
CHANGE_NOTIFY; publish — `SliceCompleted` fast-path + reconcile backstop). Анализ
проекта показал: цепочка `ingest → export → delivery` **событийна только
наполовину** — publish уже событийный, но:

- **ingest → export — это poll-over-revision.** `DaemonExportScheduler` каждые
  `pollInterval` опрашивает `artifact_revision.changedAt` и сравнивает с
  checkpoint через `CadenceSource.isDue(...)`. Bump ревизии — это **факт**,
  происходящий в canonical write-транзакции ingest, но export узнаёт о нём
  поллингом, а не событием. Латентность export'а привязана к частоте поллинга.
- **export recovery теряет fast-path.** `ExportRunRecoveryService.completeAvailable`
  доводит recovered run до `COMPLETED` (`ledger.finish`), но `SliceCompleted`
  **не эмитит** (у сервиса нет `ControlEventPublisher`). После краша публикация
  ждёт следующий reconcile-тик — latency-cliff именно там, где он нежелателен.
- **delivery — single-consumer.** `SliceCompletedPublishListener` — единственный
  потребитель факта; EXP-3 (firewall EDL, proxy ACL, DNS RPZ, STIX/OpenIOC,
  appliance push) требует fan-out на независимые каналы.
- **retention — чисто периодичный** (`DaemonSliceRetentionScheduler` +
  publish-ledger guard).

**Цель:** сделать всю цепочку событийной под **той же** доктриной 0013,
**аддитивно** (backstop'ы остаются), ради латентности, точности и единообразия
модели — не нарушая anti-broker инвариант.

## Решения

### Р1. Инвариант «эмитить факт везде, где он становится истиной»

Любой путь, доводящий срез до `COMPLETED` — нормальный export **или** recovery —
эмитит `SliceCompleted`. Recovery получает `ControlEventPublisher` и публикует
`SliceCompleted.from(terminal)` **только** на переходе `AVAILABLE → COMPLETED`
(не на `SKIPPED` — байты не изменились, доставлять нечего — и не на `FAILED`).
Идемпотентность держит CAS publish-ledger по `(sliceId, targetId)`: двойной
триггер (recovery-событие + позже reconcile) безвреден.

### Р2. ingest → export fast-path (`CanonicalArtifactsChanged`)

После завершения ingest-run (`COMPLETED`) `IngestionService` эмитит control-
событие `CanonicalArtifactsChanged` (затронутые артефакты + revision + runId для
корреляции). Export реагирует через coordinator (по образцу
`RemoteFetchDetectionCoordinator`), **сохраняя существующую cadence
(`quiet-period`/`max-cap`) как debounce**: событие кормит cadence фактом
«активность была», а не командой «экспортируй немедленно». Periodic poll остаётся
**обязательным backstop** и по-прежнему покрывает не-событийные случаи
(`coversCurrentPlan`: initial-export после апгрейда БД, plan-drift).

Выигрыш: **развязывает латентность export'а от частоты поллинга** — `pollInterval`
можно сделать редким (backstop), а export всё равно сработает через ~`quiet-period`
(та же форма, что CHANGE_NOTIFY дал fetch'у). Плюс точность: событие несёт, какие
артефакты изменились → export таргетит только затронутые профили. Эмиссия из
application чиста: `application` уже зависит от framework-free `platform-events`.

> **Уточнено детальным дизайном (2026-07-05), см. «Детальный дизайн» → Р2:**
> payload — `runId + artifactNames`, **без значения revision** (claim-check:
> консьюмер читает durable-ревизию сам в момент чека); «coordinator»
> материализуется не отдельным классом по образцу
> `RemoteFetchDetectionCoordinator`, а методом `nudge()` на самом
> `DaemonExportScheduler` + тонким listener'ом; таргетинг затронутых профилей —
> отложенная точность (v1-консьюмер — doorbell, `artifactNames` зарезервированы
> в payload'е).

### Р3. Delivery fan-out: `SliceCompleted` — факт, доставка — N независимых consumers

`SliceCompleted` **остаётся фактом**, а не командой «отправь в SMB». Каждый
delivery-канал (SMB publish, EDL, RPZ, STIX/OpenIOC, appliance push) — это
**отдельный `@EventListener` + собственный durable ledger + собственный periodic
reconcile**, а не «ещё один if в export». Событие in-process и lossy, поэтому
consumer без своего ledger'а потерял бы доставку при потере события — durable
ledger + reconcile обязательны для каждого канала (это EXP-3, эффорт L на
коннектор; событие — тривиальная часть).

**Обобщённый retention guard (ключевой пункт).** Сейчас
`PublishLedgerSliceRetentionGuard` вето на удаление среза, пока не доставлено на
все **publish-targets**. Новые каналы со своими ledger'ами этот guard не увидит →
retention удалит срез **до** их доставки. Поэтому guard обобщается с «publish
targets» до **«все delivery sinks»**: вводится единая абстракция
`DeliverySink`/`DeliveryLedger`, каждый канал репортит terminal-состояние в общий
предикат, retention вето по **любому** незавершённому каналу. Consumers независимы
по доставке, но имеют общую зависимость — корректность retention; она решается на
уровне абстракции, а не дублированием.

> **Уточнено детальным дизайном (2026-07-05), см. «Детальный дизайн» → Р3:**
> «единая абстракция» ограничена retention-view — consumer-owned порт
> `DeliveryLedger.isTerminal(sliceId, targetId)` + `DeliveryTarget`; «общий
> предикат» материализуется как AND-композит инстансов одного generic-guard'а.
> `DeliverySink` — роль/шаблон канала (listener + ledger + reconcile + health),
> **не** общий Java-интерфейс: у исполнения доставки нет полиморфного
> потребителя. Saga-API каждого канала остаётся channel-private.

### Р4. Retention: periodic остаётся авторитетным; event-nudge отложен

`age/count`-политика **time-driven** и событием не выражается — только sweep. Поэтому
periodic retention остаётся обязательным. Опциональный nudge
(`SliceDeliveredToTarget`/`SlicePublishStateChanged` → перепроверка retention по
`profile/slice`) вводится **только при реальном disk pressure**: выигрыш узкий —
помогает лишь срезам, уже прошедшим `age/count` и запинённым **исключительно**
доставкой. До симптома — YAGNI.

Все решения сохраняют инварианты 0013: события — non-durable hints; у каждого
consumer'а свой durable ledger + reconcile; идемпотентность по бизнес-ключу
(`slice×target` / identity), не по `eventId`; keyed single-flight исполнение;
anti-broker (никаких queue/DLQ/wire-format в ядре). `platform-events` не меняется,
ArchUnit-правила anti-broker продолжают держать.

## Детальный дизайн

Пре-имплементационная детализация срезов; добавляется по мере проработки
(Р1–Р3 — 2026-07-05). Ссылки на классы — по состоянию ветки
`module/platform-event/eip-base`.

### Р1 — recovery эмитит `SliceCompleted`

**Инвентарь путей.** `ExportRunRecoveryService` — единственный компонент,
доводящий recovered run до терминала. Вызывается из двух мест, оба сходятся в
одном singleton'е, поэтому эмиссия добавляется в **одну** точку —
`completeAvailable()`:

| Путь | Вызов | Режим |
|---|---|---|
| daemon-старт | `DaemonExportScheduler.start()` (phase 100) → бин `RecoverExportUseCase` (лямбда в `AppConfig` под `ExportOperationGuard`-lease) | daemon |
| перед каждым export'ом | `ExportService.export()` → `recovery.recoverIncomplete()` (внутри собственного guard'а) | daemon-poll и oneshot CLI `export` |

**Изменения модели.** Единственный класс с новой логикой —
`ExportRunRecoveryService` (`core/ioc-application`):

- новое поле `ControlEventPublisher eventPublisher`: **оба существующих конструктора
  сохраняются как есть** и делегируют в новый расширенный overload с
  `NoopControlEventPublisher.INSTANCE`; publisher принимает только новый overload —
  ровно та же лестница, что у `ExportService` (его 12-arg делегирует в 13-arg с
  Noop). Это держит «существующие тесты не трогаем» буквально: фикстура
  `ExportRunRecoveryServiceTest` строит сервис текущим полным конструктором.
  Осознанная цена — телескоп углубляется на ступень; это учтённый долг **CODE-1**,
  консолидация в builder — его зона, не Р1;
- `completeAvailable()`: после `ledger.finish(AVAILABLE→COMPLETED)` и
  `observer.completed(terminal)` добавляется эмиссия
  `eventPublisher.publish(SliceCompleted.from(terminal))` в **локальном
  try/catch** (см. «Модель корректности») — порядок тот же
  (durable finish → observer → event), что в happy-path `ExportService.export()`.

В bootstrap меняется только сигнатура бина `AppConfig.exportRunRecoveryService(...)`
(+`ControlEventPublisher`; бин publisher'а безусловный, `EventCoordinationConfig`).
Новых событий нет — переиспользуется `SliceCompleted` из `application/export` с его
корреляцией (`correlationId = runId` → сквозной MDC в listener'е). **Не меняются:**
`platform-events`, `SliceCompleted`, `SliceCompletedPublishListener`, keyed executor,
publish-ledger, ArchUnit-правила. Контракт «только COMPLETED» уже защищён самим
`SliceCompleted.from(run)` (бросает на любом другом статусе).

**Семантика ветвей recovery:**

| Терминал recovery | Ветка | Эмиссия | Почему |
|---|---|---|---|
| `AVAILABLE → COMPLETED` | `completeAvailable()` | **да** | байты среза доступны, нужна доставка |
| `… → SKIPPED` | `recoverStartedCandidate()` (same-content) | нет | байты не изменились, доставлять нечего |
| `… → FAILED` | `fail(...)` | нет | среза нет |

**Модель корректности:**

- *Durable-first:* эмиссия строго после `ledger.finish` — правило
  [../dev/event-coordination.md](../dev/event-coordination.md) §6 («эмитить там,
  где факт становится истиной, после durable-записи, не до»).
- *Fire-and-observe — контракт адаптера, а не типа порта:* сигнатура
  `ControlEventPublisher.publish` технически может бросить; «не бросает наружу»
  задокументировано в dev-доке (§3) и соблюдается штатным
  `SpringControlEventPublisher` (ловит RuntimeException → `publishFailed`), но
  compile-time гарантии нет — Javadoc порта об исключениях молчит. Для recovery
  это существенно: эмиссия стоит внутри try→`recoveryException` в
  `recoverIncomplete()`, и нарушивший контракт adapter превратил бы завершённый
  run в ложный `RECOVERY_FAILED` и завалил daemon-start (phase 100). Поэтому
  эмиссия в recovery оборачивается локальным try/catch — свойство «эмиссия не
  ломает recovery» становится локальным, а не допущением об адаптере. Catch —
  сознательно тихий с инвариант-комментарием: у core-класса нет логгера
  (framework-free), а первичный канал наблюдения сбоя публикации — это
  `ControlEventObserver.publishFailed` в адаптере, который штатно и не даёт
  исключению выйти; guard — defence-in-depth, не канал ошибок. `ExportService`
  остаётся без guard'а: его throw изолируется `DaemonExportScheduler.attempt` и
  startup-путь не задевает.
- *Идемпотентность:* CAS `publish_ledger` по `(sliceId, targetId)` — двойной
  триггер (recovery-эмит + позже reconcile `DaemonPublishScheduler`) безвреден.
  Двойная эмиссия на один run невозможна: после `finish` run терминален и в
  `findIncomplete()` больше не попадает.
- *Oneshot:* событие публикуется и наблюдается как обычно (publisher/observer —
  безусловные бины), но delivery-listener не поднят (только
  daemon+`sync.publish`), поэтому публикация **не приводит к доставке** —
  прецедент уже есть (`ExportService` эмитит в oneshot сегодня); доставку в
  oneshot покрывает команда `sync publish`.
- *Границы выигрыша:* Р1 закрывает post-crash latency-cliff — и только его.
  Событие не перепроверяет удалённое состояние: `SUCCEEDED` в `publish_ledger`
  авторитетен, ручная очистка remote-target'а не обнаруживается ни
  recovery-событием, ни reconcile (тот сверяет каталог×ledger, не
  remote-listing) — это модель 0011/0013, Р1 её не меняет и «самовосстановления»
  target'а не обещает.

**Lifecycle (закрывает открытый вопрос Р1).** Эмиссия на phase 100 **доходит** до
исполнителя:

1. `SliceCompletedPublishListener` и `syncKeyedExecutor` — eager-бины (не `@Lazy`),
   создаются при context refresh, до старта любых `SmartLifecycle`.
2. `BoundedKeyedSerialExecutor` строится поверх уже запущенного fixed-пула
   (`ioc-sync-worker`) и принимает работу с момента конструирования — lifecycle-gate
   у него нет.
3. Multicaster инициализируется до lifecycle-processor'а; dispatch синхронный:
   recovery (main thread, phase 100) → listener → `executor.submit` → работа уходит
   на воркер, старт не блокируется.
4. Порядок фаз усиливает выигрыш: export recovery (phase 100) раньше первого
   publish-reconcile (`DaemonPublishScheduler`, phase 150) — post-crash срез уходит
   в доставку в первые секунды старта, не дожидаясь `publish.interval`.

Остаточный риск — только bounded admission (shed при полной очереди,
64/endpoint — недостижимо на старте); и даже тогда reconcile добирает. При
имплементации подтвердить наблюдением ECS-лога старта (event dispatch → publish start).

**Тестовая модель.** `ExportRunRecoveryServiceTest` + `RecordingControlEventPublisher`
(`platform-events`), по образцу `ExportServiceTest`:

- **ровно одно** `SliceCompleted` на каждом forward-пути, достигающем `COMPLETED`,
  — параметризованно по матрице (ledger-status × fs-state), по которой фикстура
  теста уже устроена (`fixture(status, filesystemState)`):
  `STARTED/RECOVERABLE`, `STARTED/STAGED`, `STARTED/AVAILABLE`, `STAGED/STAGED`,
  `STAGED/AVAILABLE`, `AVAILABLE/AVAILABLE`. Событие добавляется в
  `completeAvailable()`, поэтому матрица защищает главный тезис «эмиссия в одной
  точке» от регрессии при рефакторинге веток `recover()`;
- поля события соответствуют терминальному run'у (`sliceId = runId`,
  `manifestSha256` дотягивается из ledger — заодно проверка пропагации sha на
  recovery-пути);
- **ноль** эмиссий на SKIPPED- (same-content) и всех FAILED-путях; ноль при пустом
  `findIncomplete()`;
- бросающий publisher не валит recovery и не меняет терминальный статус run'а
  (локальный guard);
- существующие кейсы/ассерты не меняются (делегирующие конструкторы); матрица —
  новые параметризованные кейсы поверх той же фикстуры, расширенной
  `RecordingControlEventPublisher`.

Эффорт подтверждён: **S** (~3 файла: сервис, `AppConfig`, тест).

### Р2 — ingest → export fast-path (`CanonicalArtifactsChanged`)

**Факты кода, определяющие дизайн:**

- `QuietPeriodCadenceSource.isDue` считает quiet-дедлайн от **durable**
  `changed_at` ревизии (`observedActivity + quietPeriod`), а не от момента
  наблюдения; событию не нужно нести ни время, ни ревизию. Nudge при этом
  планирует чек в `eventTime + QP`, где `eventTime ≥ changed_at` на хвост
  `DB-commit → projection → archive → COMPLETED`: **fast-path сознательно
  позднит на хвост завершения run** — v1-trade-off, зато к моменту чека quiet
  относительно `changed_at` заведомо истёк и «not due» при срабатывании почти
  исключён. `maxCap` анкерится от первого наблюдения pending-работы
  (`pendingSince`) — отсюда follow-up-цикл ниже.
- `IntervalCadenceSource.isDue` игнорирует activity полностью (фиксированный
  processing-time интервал) → **при `trigger.type: interval` (текущий default)
  fast-path не даёт ничего по определению**; выигрыш Р2 материализуется при
  `quiet-period`. Смена default-типа — операторское решение вне Р2.
- `bumpRevision` вызывается только при `inserted > 0` → ingest из одних
  дубликатов не двигает ревизию. Безусловная эмиссия на `COMPLETED` может дать
  ложный hint — cadence отбрасывает его по durable-фактам
  (`activity ≤ checkpoint → clearPending`); цена ложного события — два чтения БД.
- Crash-recovery ingest'а — слепая зона эмиссии: `IngestRunRecoveryService`
  (daemon-only, работает при context refresh) доводит `DB_COMMITTED`-runs до
  `COMPLETED` отдельным путём, а события, эмитнутые до крэша, но не успевшие
  сработать, никто не переиздаёт. Закрывается **startup-nudge** (ниже), а не
  протаскиванием publisher'а в recovery.

**Событие (`application/ingest`) и резолюция гранулярности:**

```java
record CanonicalArtifactsChanged(ControlEventMetadata metadata,
                                 String runId,
                                 List<String> artifactNames) implements ControlEvent
```

`EVENT_TYPE = "ingest.canonical-artifacts.changed"`, `EVENT_VERSION = 1`;
`from(...)` строит metadata `withoutCausation`, `correlationId = runId` —
зеркало `SliceCompleted.from`. Compact-конструктор валидирует контракт (зеркало
`SliceCompleted`): `runId` — requireText, `artifactNames` — `List.copyOf`,
непустой, элементы non-blank — даже пока v1-консьюмер их не использует
(дешёвая защита контракта события). Резолюции открытого вопроса:

- **per-run, не per-artifact**: одно событие на завершённый run (N событий на
  run — шум без выигрыша, коалесценция всё равно на консьюмере);
- **без значения revision**: claim-check — консьюмер читает durable-ревизию сам
  (`ArtifactRevisionReader`) в момент чека; снапшот ревизии в событии — это
  stale-риск и лишний контракт;
- **`artifactNames` событие несёт, но v1-консьюмер их не использует** (doorbell):
  гейт по профилю — два дешёвых чтения в `attempt()`; таргетинг профилей
  включать при реальном росте их числа. Это уточнение обещания Р2 «export
  таргетит только затронутые профили»: точность остаётся возможной (payload
  есть), сейчас — YAGNI.

**Эмиссия.** `IngestionService` — та же лестница, что Р1: существующие
конструкторы сохраняются и делегируют с `NoopControlEventPublisher.INSTANCE`,
новый overload принимает publisher; эмиссия в конце `processClaimed()` **после**
`runLedger.markCompleted` (durable-first) под тем же локальным guard'ом
(тихий catch, наблюдение — `publishFailed` адаптера). Одна точка покрывает и
нормальный ingest, и CLAIMED-recovery (`recover()` → `processClaimed`). На
duplicate/`FAILED`/`reject()` эмиссии нет. Каноническая истина возникает раньше
(на `markDbCommitted`), но эмиссия на `COMPLETED` выбрана как «одно событие на
успешный run»; задержка commit→completed пренебрежима против QP.

**Консьюмер: `nudge()` на `DaemonExportScheduler` + тонкий listener (резолюция
«какой координатор»).** Ни reuse `RemoteFetchDetectionCoordinator` (связан с
fetch-sources/transports/health; его per-source SourceState-машинерия не нужна
единственному консьюмеру), ни отдельный `ExportTriggerCoordinator`: вся
координация сводится к «попроси схедулер проверить cadence чуть позже» и
принадлежит самому схедулеру.

- `DaemonExportScheduler.nudge()`: no-op при `!active` и при выключенной
  nudge-политике; CAS-флаг «чек запланирован» →
  `executor.schedule(check, policy.delay())`; повторный nudge при уже
  запланированном чеке — coalesce (no-op), таймер не переносится. Чек
  исполняется на **том же** single-thread executor'е схедулера: poll-тик и
  nudge-чек сериализованы по построению, cadence-объекты трогает один поток —
  ни одного нового примитива синхронизации, кроме флага. **Nudge-политику
  схедулер получает явно** — маленький `ExportNudgePolicy(enabled, delay)` из
  `AppConfig` (`enabled = type == quiet-period`, `delay = quiet-period`):
  `CadenceSource` о своём типе не сообщает, и никакой
  `instanceof`-инспекции реализаций не допускается.
- Чек = обычный `runOnce()` (гейты `coversCurrentPlan` + `cadence.isDue`
  остаются авторитетными — двойная защита от ложных/устаревших событий), но
  `attempt()` начинает возвращать внутренний исход
  (`ATTEMPTED / PENDING_NOT_DUE / IDLE / FAILED`; именно `ATTEMPTED`, не
  `EXPORTED` — export use case может вернуть unchanged/SKIPPED по своим
  durable-гейтам, для схедулера это одинаково «due обработан, follow-up не
  нужен»): если после чека остался
  `PENDING_NOT_DUE` (активность освежилась после планирования чека или quiet ещё
  не истёк), схедулер планирует **follow-up-чек через QP**. Follow-up-цикл живёт
  только пока есть pending-работа; cadence остаётся **единственным** носителем
  quiet/max-cap-политики (nudge-механика не дублирует `maxCap`), а серия чеков
  каждые QP гарантирует срабатывание cap не позже «первый чек + maxCap».
- Латентность: export через ≤ 2×QP от последней активности (coalesce может
  «проехать» один чек — та же форма, что у fetch-debounce); при непрерывной
  активности cap-дедлайн срабатывает на ближайшем чеке после `QP + maxCap`
  (чеки идут с шагом QP → строго меньше `2×QP + maxCap` от первого события;
  точное равенство `QP + maxCap` — только когда `maxCap` кратен QP, как у
  дефолтов 1h/5m). Всё — независимо от `pollInterval`, который после Р2 можно
  делать редким (цель решения).
- Listener `CanonicalArtifactsChangedExportListener` (bootstrap):
  `@EventListener` → MDC-scope (runId-корреляция, зеркало
  `SliceCompletedPublishListener.mdc`) → `trigger.nudge()`. Зависит от
  `ExportNudgeTrigger` (`@FunctionalInterface` в bootstrap, реализуется
  схедулером — идиома `RemoteFetchDetectionTrigger`), а не от конкретного
  класса. Тяжёлой работы в потоке публикации нет — nudge только планирует.
  Keyed-single-flight-эквивалент здесь — сам схедулер: один воркер, один ключ
  «export».
- Условия бина — зеркало `daemonExportScheduler`
  (daemon && `export.enabled` && jdbc-storage×2), **без** sync-условий:
  контур ingest→export локальный.

**Lifecycle/startup.** `start()` после recovery дополнительно делает `nudge()` —
первый чек через QP, а не через `pollInterval`. **Порядок внутри `start()`
фиксируется явно** — иначе startup-nudge тихо no-op'ается об собственный
`!active`-guard (в текущем коде `active = true` выставляется последним):
recovery → создать executor → `active = true` → запланировать poll →
`nudge()`. Одним механизмом закрываются обе крэш-дыры: события, потерянные при
рестарте, и `DB_COMMITTED`-runs, доведённые `IngestRunRecoveryService` (который
сам ничего не эмитит). `stop()` и поздний nudge — no-op без исключений (флаг +
guard на `RejectedExecutionException`).

**Конфигурация: новых ключей нет.** Nudge-делэй = `trigger.quiet-period`,
включённость выводится из `trigger.type` — политика остаётся в существующем
конфиге и доезжает до схедулера явным `ExportNudgePolicy` из `AppConfig`
(см. выше). Отдельный debounce-ключ — только при реальной потребности (YAGNI).

**Модель корректности** — инварианты Р1 без изменений: durable-first;
guarded-эмиссия с разделением ролей — **адаптер наблюдает свои сбои**
(`ControlEventObserver.publishFailed` в `SpringControlEventPublisher`, штатно
не бросает), **сервисный guard — последний барьер**, чтобы ingest не зависел от
событийного контура даже при non-conforming адаптере (guard — не канал ошибок,
у него нет своего observer'а); идемпотентность на durable-гейтах консьюмера
(`coversCurrentPlan` + `isDue` по ревизии/чекпойнту), не на `eventId`; poll —
обязательный backstop (правило 0013: у каждого event-пути есть не-event
backstop); потерянное событие ⇒ export на следующем poll-тике.

**Тестовая модель.**

- `IngestionServiceTest` (+`RecordingControlEventPublisher`): ровно одно событие
  на успешный `processClaimed` (runId + artifactNames корректны); эмиссия на
  CLAIMED-recovery; ноль на duplicate/`FAILED`-record/claim-fail/
  extraction-throw/`reject()`; бросающий publisher не меняет ledger-статусы и
  результат (guard).
- `DaemonExportSchedulerTest` (детерминированные executor/clock): nudge
  планирует чек через QP; coalesce второго nudge; follow-up при
  `PENDING_NOT_DUE` и его отсутствие при idle; cap-срабатывание серии чеков;
  startup-nudge (в т.ч. порядок `active`); no-op при выключенной политике и
  после `stop()`. **Seam для детерминизма закладывается сразу**:
  package-private конструктор с инжектируемым `ScheduledExecutorService` —
  идиома уже есть у `RemoteFetchDetectionCoordinator`; иначе тесты живут на
  реальном времени.
- Listener-тест: dispatch → `nudge()` вызван; MDC-поля проверяются **внутри**
  вызова `trigger.nudge()` (фейковый trigger захватывает MDC в момент вызова —
  после возврата `MdcScope` уже закрыт).

Эффорт подтверждён: **M** (событие + `IngestionService` + `AppConfig` +
nudge-механика схедулера + `ExportNudgeTrigger` + listener + тесты в двух
модулях). Tracking — **OPS-7** в [../KNOWN-ISSUES.md](../KNOWN-ISSUES.md).

### Р3 — delivery fan-out: контракт `DeliverySink`/`DeliveryLedger` и обобщённый retention guard

Р3 остаётся **отложенным по триггеру** (первый реальный не-SMB target, EXP-3);
здесь фиксируется контракт, чтобы первый коннектор не проектировал его под
давлением сроков и не ломал retention.

**Факты кода, определяющие дизайн:**

- Guard-слот сегодня **одиночный и взаимоисключающий**: `SliceRetentionService`
  принимает один `SliceRetentionGuard`; в wiring живут два XOR-бина —
  `StandaloneSliceRetentionGuard.INSTANCE` (publish-контур выключен, всегда
  `true`) против `PublishLedgerSliceRetentionGuard` (включён). Второй канал в
  эту схему не встаёт вообще — подтверждение ключевого пункта Р3.
- `SliceRetentionGuard` уже ISP-минимален (`boolean canDelete(SliceDescriptor)`),
  а `SliceRetentionService` при вето просто оставляет срез в пуле (`blocked++`,
  `maxCount` — best-effort) — семантика пиннинга готова к композиции.
- `PublishLedger` — **богатый saga-API** (~10 методов: `ensurePending`,
  `transition` CAS, `findRetryable`, health-агрегаты). Retention'у из него нужен
  один факт: terminal ли `(sliceId, targetId)`
  (`SUCCEEDED | ABANDONED`; отсутствие записи = pin). Обобщать весь saga-API —
  ISP-нарушение и преждевременная унификация разнородных каналов.
- В проекте есть готовая TCK-идиома: `ioc-application-tck` с абстрактными
  контракт-тестами (`IngestionLedgerContractTest`), которые адаптеры
  подключают test-scoped зависимостью.

**Ключевое решение: общий знаменатель каналов — только retention-view, и он
принадлежит потребителю.** Единственный общий потребитель у разнородных каналов
— retention (export-контекст). Поэтому:

```java
// application/port/out/export — consumer-owned port (DIP: порт у retention)
interface DeliveryLedger {                 // retention-view, НЕ saga-API
    boolean isTerminal(String sliceId, String targetId);
}
interface DeliveryTarget {                 // общий минимум конфигурации цели
    String targetId();
    String exportProfile();
}
```

- **`CompositeSliceRetentionGuard`** (application/export): AND по
  `List<SliceRetentionGuard>` с short-circuit на первом вето; пустой список →
  allow. XOR-wiring схлопывается: `StandaloneSliceRetentionGuard` и условные
  выражения уходят — композит над «сколько есть guard-вкладов» покрывает и
  standalone-случай (ноль вкладов), и N каналов.
- **`DeliveryLedgerSliceRetentionGuard`** — generic-обобщение сегодняшнего
  `PublishLedgerSliceRetentionGuard` (та же логика: по targets профиля →
  `isTerminal`, иначе вето): один класс, **N инстансов** (по одному на канал),
  вместо N рукописных guard'ов. `PublishTarget` реализует `DeliveryTarget`;
  `PublishLedger` реализует `isTerminal` через существующий `find` — **без
  переименований схемы/таблиц** (`publish_ledger` остаётся).
- Контракт guard-вклада (LSP, держится TCK): `false` = «мой канал ещё не
  довёл этот срез до terminal»; неизвестный срез/цель → `false` (консервативный
  pin — новый канал обязан догнать историю через свой reconcile, а не
  пропустить её).

**Что сознательно НЕ обобщается.** «`DeliverySink`» — **роль/шаблон канала, а
не общий Java-интерфейс**: у исполнения доставки нет полиморфного потребителя
(каждый канал — свой listener + свой scheduler), общий интерфейс был бы
спекулятивным (YAGNI/ISP). Saga-API ledger'а остаётся channel-private:
жизненные циклы file-push (SMB), API-push (EDL/appliance) и zone-transfer (RPZ)
не унифицируются заранее; рекомендованная (не обязательная) модель статусов —
как у publish (`PENDING/IN_PROGRESS/SUCCEEDED/FAILED/ABANDONED`).

**Шаблон канала** (OCP-рецепт; расширяет §6
[../dev/event-coordination.md](../dev/event-coordination.md)) — новый канал
добавляет **только свои** компоненты, ноль правок в retention/export/существующих
каналах:

1. adapter-модуль интеграции (правило «один адаптер = одна интеграция»),
   версия — в parent POM;
2. свой durable ledger (+ реализация `DeliveryLedger.isTerminal`) —
   идемпотентность по `slice×target`;
3. `@EventListener` на `SliceCompleted` в bootstrap → keyed-работа (fast-path;
   тяжёлой работы в потоке публикации нет — multicaster синхронный, N
   listener'ов делают только submit);
4. свой periodic reconcile-scheduler (correctness backstop; каталог срезов ×
   свой ledger — образец `DaemonPublishScheduler`);
5. вклад в композит: инстанс `DeliveryLedgerSliceRetentionGuard(ledger, targets)`;
6. свой health read-model (счётчики ledger'а, degradation, queue-keys) — образец
   `syncHealthIndicator`.

**Keyed-исполнение.** Каналы разделяют существующий
`BoundedKeyedSerialExecutor`; ключ — **`channelId:endpoint`** (внутри
канала+endpoint — сериализация, между каналами — параллельность; bounded
admission + shed-to-reconcile сохраняются). Sizing воркеров пересматривается с
`max(fetch, publish)` на учёт каналов при первом коннекторе. Отдельный executor
на канал — только при несовместимом латентном профиле (контраргумент-заметка,
не default).

**Конфигурация (резолюция «единообразна ли»).** Не единый polymorphic-список:
каждый канал — своя typed-секция (`ioc.delivery.<channel>.targets` либо
существующая `ioc.sync.publish.targets` для первого канала — non-breaking) с
**обязательным общим минимумом полей** (`name`, `export-profile`) +
канал-специфика. Общий минимум и есть `DeliveryTarget`; binding остаётся
строго типизированным per-канал.

**Границы/onboarding.** Включение нового канала пиннит retention для всех уже
завершённых срезов, пока его reconcile не догонит историю — это **ожидаемое**
следствие консервативного pin'а (bounded: скорость reconcile), не баг.
Доставка «всей истории» новым каналом — семантика как у publish; канал,
которому нужна только «последняя версия», выражает это своей saga-логикой
(например, немедленный `ABANDONED` для устаревших срезов), а не ослаблением
guard-контракта. Ручную очистку удалённой стороны retention по-прежнему не
видит (модель 0011/0013, как в Р1).

**Тестовая модель.**

- `CompositeSliceRetentionGuard`: пустой список → allow; любой `false` → вето
  (short-circuit подтверждается порядком вызовов); все `true` → allow.
- `DeliveryLedgerSliceRetentionGuard` — generic-перенос сегодняшних кейсов
  publish-guard'а: unknown → вето; non-terminal → вето; все terminal → allow.
- **`DeliveryLedger` retention-view TCK** в `ioc-application-tck` (образец —
  `IngestionLedgerContractTest`): семантика `isTerminal`, консервативность на
  unknown; каждый канал прогоняет TCK на своём ledger'е.
- Шаблонные тесты канала (listener → keyed submit; reconcile-идемпотентность) —
  при первом коннекторе.

**Порядок активации** (уточняет пункт 3 миграции):

1. *Сейчас* — только этот контракт на бумаге; кода нет.
2. *При первом не-SMB target, шаг 1 (S)* — guard-рефакторинг: `DeliveryTarget`/
   `DeliveryLedger`/generic guard/композит, wiring-схлопывание XOR; publish
   становится первым вкладом композита. Самодостаточен и non-breaking.
3. *Шаг 2 (L на коннектор)* — сам канал по шаблону выше.

Эффорт: **S** на guard-рефакторинг + **L** на коннектор (подтверждает оценку
EXP-3); событийная часть — тривиальна (listener по шаблону).

### Р4 — сознательно не детализируется

Решение Р4 уже полное: политика time-driven, sweep авторитетен, nudge — только
при disk pressure (латентность *удаления* — не correctness-концерн, retention
by design best-effort). События-источники появятся лишь с Р3-каналами —
детализация сейчас была бы дизайном против несуществующего кода. Механизм при
активации — ещё один doorbell-nudge на retention-scheduler по образцу Р2;
проверено: ничто в детальных дизайнах Р1–Р3 будущий Р4 не блокирует (точка
эмиссии — saga-логика канала, шов `ControlEventPublisher` доступен).

## Отклонённые альтернативы

- **Эвентифицировать CSV-проекцию.** Проекция correctness-coupled внутри
  write→project run-ledger саги (crash recovery). Событие променяло бы чистый
  recovery-инвариант на ненужную латентность. Coalescing-выигрыш принадлежит
  **ING-7** (дельта-проекция), не событиям.
- **Эвентифицировать стадии extraction-конвейера.** In-process трансформация
  данных, не кросс-контекстная координация: нулевой выигрыш, лишняя indirection.
- **Control-событие «файл появился».** Spring Integration inbound adapter уже даёт
  edge-события; дублировать фреймворк не нужно.
- **Delivery как if-ветки в export / `SliceCompleted` как команда «в SMB».**
  Нарушает event-notification (факт ≠ команда) и OCP; связывает export с каждым sink.
- **Полностью событийный retention.** `age/count` time-based; только sweep энфорсит.
- **Durable outbox / внешний брокер сейчас.** Отложенный seam (**OPS-4**);
  in-process доставка + per-consumer ledger reconcile достаточны до появления
  межпроцессной доставки.

## Следствия (включая migration/rollout)

- **Аддитивно и non-breaking.** Каждое изменение добавляет fast-path поверх
  существующего backstop'а; ничего не удаляется. Разворачивается инкрементально,
  каждый срез самодостаточен.
- **Порядок миграции:**
  1. **Р1 — recovery эмитит `SliceCompleted`** (S). Наименьшее, закрывает
     post-crash latency-cliff. Эмиссия только на `AVAILABLE→COMPLETED`.
     Пре-имплементационная детализация — раздел «Детальный дизайн» → Р1.
  2. **Р2 — `CanonicalArtifactsChanged`** (M). Эмиссия + nudge-механика на
     `DaemonExportScheduler` (уточнено детальным дизайном: отдельный coordinator
     не нужен); backstop не трогаем. Развязывает export-латентность от поллинга.
     Пре-имплементационная детализация — раздел «Детальный дизайн» → Р2;
     tracking — `OPS-7`.
  3. **Р3 — delivery fan-out** (L, на коннектор). Браться, когда появится реальный
     не-SMB target; проектировать сразу `DeliverySink` + обобщённый retention guard.
     Контракт уже зафиксирован — раздел «Детальный дизайн» → Р3 (guard-рефакторинг
     S + коннектор L); tracking — `EXP-3`.
  4. **Р4 — retention nudge** — отложить до disk pressure.
- **Размещение.** Новые события живут в application-контексте своего факта
  (`CanonicalArtifactsChanged` — в `application/artifact`/`ingest`; delivery/retention
  события — в своих контекстах). `platform-events` без изменений.
- **Health/observability.** Каждый новый consumer добавляет свой ledger read-model
  + reconcile, как publish.
- **Документы.** Расширяет 0013; при реализации обновляются
  [../dev/event-coordination.md](../dev/event-coordination.md) (как) и
  [../dev/sync.md](../dev/sync.md); в [../KNOWN-ISSUES.md](../KNOWN-ISSUES.md) Р2
  получает tracking-ID, Р3 = **EXP-3**, Р4 привязан к disk pressure.

## Открытые вопросы

- **Р1 lifecycle-порядок:** эмиссия при `DaemonExportScheduler.start()` (фаза 100)
  реально доходит до keyed executor'а, способного принять publish-работу, или
  no-op'ается до reconcile? — **Разрешено анализом кода (2026-07-05): доходит.**
  Listener и executor — eager-бины, созданные до SmartLifecycle-стартов; пул
  executor'а жив с конструирования; publish-reconcile стартует позже (phase 150).
  Детали — «Детальный дизайн» → Р1; при имплементации осталось эмпирическое
  подтверждение по ECS-логу старта. — **Подтверждено эмпирически (2026-07-06,
  стенд):** recovery-эмиссия на phase 100 доставила восстановленный срез за
  140 мс, до завершения старта приложения (см. «Обновление 2026-07-06» в
  Статусе).
- **Р2 гранулярность:** `CanonicalArtifactsChanged` per-artifact или per-run; нести
  ли значение revision для точного таргетинга; переиспользовать
  `RemoteFetchDetectionCoordinator` или отдельный маленький `ExportTriggerCoordinator`.
  — **Разрешено анализом кода (2026-07-05):** per-run; без значения revision
  (claim-check — консьюмер читает durable-ревизию сам); ни reuse, ни отдельный
  координатор — `nudge()` на `DaemonExportScheduler` (его же single-thread
  executor) + тонкий listener за `ExportNudgeTrigger`. Детали — «Детальный
  дизайн» → Р2.
- **Р3 контракт:** форма `DeliverySink`/`DeliveryLedger` и как обобщённый retention
  guard агрегирует terminal-состояние по разнородным каналам; единообразна ли
  конфигурация delivery-целей. — **Разрешено анализом кода (2026-07-05):** общий
  знаменатель каналов — только consumer-owned retention-view
  (`DeliveryLedger.isTerminal` + `DeliveryTarget`); агрегация — AND-композит
  инстансов одного generic-guard'а (unknown → консервативный pin);
  `DeliverySink` — роль/шаблон, не общий интерфейс; конфигурация —
  per-channel typed-секции с общим минимумом полей (`name`, `export-profile`).
  Детали — «Детальный дизайн» → Р3.
- Заводить ли tracking-ID в KNOWN-ISSUES сразу (напр. `OPS-7` под ingest→export).
  — **Разрешено (2026-07-05):** да, `OPS-7` заведён в
  [../KNOWN-ISSUES.md](../KNOWN-ISSUES.md) со ссылкой на детальный дизайн Р2.

## План реализации Р1/Р2 по срезам

План ниже — execution checklist для имплементации. Коммитить лучше логическими группами:
Р1 отдельно, Р2 — сначала событие/эмиссия, затем consumer/nudge+wiring. Все срезы сохраняют
инварианты ADR-0013: события остаются non-durable hints, Spring — только
in-process adapter/fan-out, тяжёлая работа уходит в consumer-owned executor или
scheduler, periodic/reconcile backstop не удаляется.

### Срез 1 — Р1: recovery эмитит `SliceCompleted` (выполнен: `49bd68b`)

**Цель.** Закрыть post-crash latency-cliff для export-срезов, которые recovery
доводит до `COMPLETED`: доставка получает тот же fast-path, что и обычный
happy-path `ExportService.export()`.

**Изменения кода:**

- `core/ioc-application/.../export/ExportRunRecoveryService.java`
  - добавить поле `ControlEventPublisher eventPublisher`;
  - оба существующих конструктора сохранить и делегировать в новый overload с
    `NoopControlEventPublisher.INSTANCE`;
  - новый полный overload принимает `ControlEventPublisher`;
  - в `completeAvailable()` после `ledger.finish(... COMPLETED ...)` и
    `observer.completed(terminal)` публиковать `SliceCompleted.from(terminal)`;
  - публикацию обернуть локальным `try/catch` с коротким инвариант-комментарием:
    штатный `SpringControlEventPublisher` наблюдает failure сам, local guard —
    defence-in-depth, чтобы non-conforming adapter не ломал recovery.
- `bootstrap/ioc-app/.../AppConfig.java`
  - добавить `ControlEventPublisher` в сигнатуру бина
    `exportRunRecoveryService(...)`;
  - передать publisher в новый overload `ExportRunRecoveryService`.

**Тесты:**

- `core/ioc-application/.../export/ExportRunRecoveryServiceTest.java`
  - расширить фикстуру `RecordingControlEventPublisher`;
  - проверить ровно одно `SliceCompleted` на forward-путях, достигающих
    `COMPLETED`: `STARTED/RECOVERABLE`, `STARTED/STAGED`, `STARTED/AVAILABLE`,
    `STAGED/STAGED`, `STAGED/AVAILABLE`, `AVAILABLE/AVAILABLE`;
  - проверить поля события: `profile`, `sliceId = runId`, `sliceName`,
    `manifestSha256`, `metadata.correlationId = runId`;
  - проверить ноль событий на `SKIPPED`, `FAILED`, empty `findIncomplete()`;
  - проверить throwing publisher: recovery не падает и terminal status уже
    завершённого run'а не откатывается.

**Локальная проверка:**

```bash
./mvnw -pl core/ioc-application -am test \
  -Dtest=ExportRunRecoveryServiceTest,ExportServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** Самодостаточный коммит `P1 recovery emits SliceCompleted`.
Не смешивать с Р2: blast radius маленький, value появляется сразу.

### Срез 2 — Р2a: `CanonicalArtifactsChanged` и эмиссия из ingest (выполнен: `d28c74e`)

**Цель.** Зафиксировать application-level факт: завершённый ingest-run мог
изменить canonical artifacts. Consumer пока не добавляется.

**Изменения кода:**

- `core/ioc-application/.../ingest/CanonicalArtifactsChanged.java`
  - новый `record CanonicalArtifactsChanged(ControlEventMetadata metadata,
    String runId, List<String> artifactNames) implements ControlEvent`;
  - `EVENT_TYPE = "ingest.canonical-artifacts.changed"`,
    `EVENT_VERSION = 1`;
  - compact-конструктор: `metadata` non-null, `runId` non-blank,
    `artifactNames = List.copyOf(...)`, список непустой, элементы non-blank;
  - factory `from(String runId, List<String> artifactNames, Instant occurredAt)`;
    event id детерминированный: `canonical-artifacts-changed:<runId>`,
    `correlationId = runId`, `causationId = null`;
  - без скрытого `Instant.now()`: источник времени передаётся явно. Если при
    имплементации появится terminal `IngestRun` snapshot с durable `updatedAt`,
    допустима дополнительная factory от него; при текущем `RunLedger`
    `markCompleted(...)` возвращает `void`, поэтому базовый путь — инжектированный
    `Clock` в `IngestionService`.
- `core/ioc-application/.../ingest/IngestionService.java`
  - добавить поле `ControlEventPublisher eventPublisher`;
  - добавить поле `Clock clock`;
  - существующие конструкторы сохранить и делегировать с
    `NoopControlEventPublisher.INSTANCE` и production-safe default clock
    (`Clock.systemUTC()`);
  - новый overload принимает publisher и `Clock`;
  - после `runLedger.markCompleted(run.runId())` публиковать
    `CanonicalArtifactsChanged` под локальным guard'ом;
  - для payload использовать artifact list из durable run (`run.artifacts()`)
    либо `sourceSinks.artifactNames()`; предпочтительно durable snapshot, если
    он уже содержит нормализованный список.
- `bootstrap/ioc-app/.../AppConfig.java`
  - прокинуть `ControlEventPublisher` и уже существующий бин `Clock` в
    `IngestionService`.

**Тесты:**

- `core/ioc-application/.../ingest/IngestionServiceTest.java`
  - успешный ingest публикует ровно одно событие с `runId` и `artifactNames`;
  - `recover()` для `CLAIMED`-record публикует событие тем же путём
    `processClaimed()`;
  - duplicate source, existing `FAILED`, claim-fail, extraction failure,
    projection failure и `reject()` не публикуют событие;
  - throwing publisher не меняет итоговые ledger/source statuses и результат
    успешного ingest.

**Локальная проверка:**

```bash
./mvnw -pl core/ioc-application -am test -Dtest=IngestionServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** Самодостаточный коммит `P2a emit CanonicalArtifactsChanged`.
После него событие уже есть, но fast-path ещё не подключён: это допустимый
промежуточный state, потому что без listener'а событие является harmless hint.

### Срез 3 — Р2b: `DaemonExportScheduler.nudge()` (выполнен: `e265b0d`, fix `aefb593`)

**Цель.** Добавить consumer-owned async boundary: ingest-событие сможет просить
export scheduler проверить durable cadence раньше `pollInterval`, но сам Spring
listener не будет выполнять export.

**Изменения кода:**

- `bootstrap/ioc-app/.../ExportNudgeTrigger.java`
  - package-private `@FunctionalInterface` с методом `void nudge()`.
- `bootstrap/ioc-app/.../ExportNudgePolicy.java`
  - small value object/record `enabled + delay`;
  - validate: если enabled, `delay` должен быть positive; disabled policy может
    хранить `Duration.ZERO` или тот же configured delay.
- `bootstrap/ioc-app/.../DaemonExportScheduler.java`
  - реализовать `ExportNudgeTrigger`;
  - добавить `ExportNudgePolicy nudgePolicy`;
  - добавить `AtomicBoolean nudgeScheduled`;
  - добавить package-private constructor с инжектируемой фабрикой
    `Supplier<ScheduledExecutorService>`, а не готовым executor'ом: `start()`
    должен создавать новый пул на каждый lifecycle-start, иначе `stop()` убьёт
    injected executor навсегда и повторный `start()` нарушит SmartLifecycle
    restart-семантику;
  - production default фабрики — текущий single-thread executor
    `ioc-export-scheduler`;
  - порядок `start()` зафиксировать как:
    recovery → `nudgeScheduled.set(false)` → создать executor → `active = true` →
    запланировать periodic poll → `nudge()`;
  - `nudge()`:
    - no-op при `!active`, disabled policy, `executor == null`;
    - CAS `nudgeScheduled false→true`;
    - `executor.schedule(this::runNudgedCheck, nudgePolicy.delay())`;
    - `RejectedExecutionException` гасить, флаг возвращать в false;
  - `runNudgedCheck()`:
    - сбрасывает `nudgeScheduled`;
    - вызывает общий non-overlapping run path;
    - если итог содержит `PENDING_NOT_DUE` или run был `BUSY`, планирует
      follow-up через тот же `nudge()`;
    - `FAILED` follow-up не планирует: retry сознательно остаётся за periodic
      poll/backstop, иначе nudge превращается в retry-механизм и может штормить
      каждые QP;
  - `runOnce()` остаётся обычным poll entrypoint и не плодит follow-up;
  - `attempt(plan)` возвращает внутренний outcome:
    `ATTEMPTED`, `PENDING_NOT_DUE`, `IDLE`, `FAILED`;
  - `ATTEMPTED` ставится после вызова export use case независимо от того,
    материализовал ли он новый slice или вернул unchanged/skipped;
  - после `ATTEMPTED` вызывать `cadence.completed()` как сейчас.
- `bootstrap/ioc-app/.../AppConfig.java`
  - собрать `ExportNudgePolicy` из `props.export().trigger()`:
    `enabled = normalizedTriggerType.equals("quiet-period")`,
    `delay = trigger.quietPeriod()`;
  - нормализация типа должна совпадать с `CadenceSources.create`
    (`trim().toLowerCase(Locale.ROOT)`), чтобы `Quiet-Period` не включал cadence
    и одновременно молча выключал nudge;
  - передать policy в `DaemonExportScheduler`.

**Тесты:**

- `bootstrap/ioc-app/.../DaemonExportSchedulerTest.java`
  - тестовая `Supplier<ScheduledExecutorService>` или controllable fake executor
    factory;
  - `nudge()` планирует check через policy delay;
  - второй `nudge()` до исполнения coalesce'ится;
  - disabled policy делает `nudge()` no-op;
  - `stop()` + поздний `nudge()` no-op без исключения;
  - `stop() → start() → nudge()` снова работает: флаг `nudgeScheduled` не
    переживает restart как вечный latch;
  - startup-nudge реально планируется после `active = true`;
  - `PENDING_NOT_DUE` даёт follow-up, `IDLE` не даёт;
  - busy/overlap nudged-check не теряет wake-up, а планирует follow-up;
  - `FAILED` не даёт follow-up, retry остаётся за poll;
  - cap-сценарий покрывается серией checks с шагом QP.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test -Dtest=DaemonExportSchedulerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** Можно держать отдельным коммитом `P2b add export nudge
scheduler`. Если реализация идёт ровно, допустимо объединить со срезом 4, но
при первых concurrency-шероховатостях лучше оставить отдельно.

### Срез 4 — Р2c: listener и Spring wiring (выполнен: `294b67c`)

**Цель.** Соединить `CanonicalArtifactsChanged` с `DaemonExportScheduler.nudge()`
через тонкий Spring listener.

**Изменения кода:**

- `bootstrap/ioc-app/.../CanonicalArtifactsChangedExportListener.java`
  - `@EventListener`-метод принимает `CanonicalArtifactsChanged`;
  - открывает `MdcScope` с полями события и `runId`;
  - вызывает `ControlEventObserver.dispatching(event, HANDLER)` перед nudge —
    это единый канал наблюдаемости dispatch'а, как у
    `SliceCompletedPublishListener` и `RemoteChangeFetchListener`;
  - вызывает только `ExportNudgeTrigger.nudge()`;
  - `dispatchFailed` обычно не нужен: `nudge()` проектируется no-throw и сам
    гасит late-stop/rejected-executor races;
  - тяжёлой работы, чтения БД и export-вызовов в listener'е нет.
- `bootstrap/ioc-app/.../AppConfig.java`
  - добавить бин listener с теми же условиями, что `daemonExportScheduler`
    (`daemon && export.enabled && service/dataframe jdbc`);
  - sync-условия не добавлять: ingest→export — локальный контур.
- `bootstrap/ioc-app/.../README.md`
  - добавить `CanonicalArtifactsChangedExportListener`,
    `ExportNudgeTrigger`, `ExportNudgePolicy` в bootstrap reference table.

**Тесты:**

- новый `CanonicalArtifactsChangedExportListenerTest`
  - dispatch вызывает `nudge()`;
  - MDC-поля проверять внутри fake trigger, потому что после возврата listener'а
    `MdcScope` уже закрыт.
- при необходимости `AppConfig`/condition test не добавлять, если существующая
  coverage уже проверяет bean conditions косвенно; не раздувать Spring context
  tests без нужды.

**Локальная проверка:**

```bash
./mvnw -pl bootstrap/ioc-app -am test \
  -Dtest=CanonicalArtifactsChangedExportListenerTest,DaemonExportSchedulerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Граница коммита.** `P2c wire ingest changed listener`. Допустимо объединить с
Р2b, если итоговый diff остаётся легко ревьюить.

### Срез 5 — документы и закрытие tracking

**Цель.** Перевести документацию из design-state в shipped-state после кода.

**Изменения документации:**

- `docs/dev/event-coordination.md`
  - добавить Р1/Р2 как реализованные fast-paths;
  - явно зафиксировать: Spring dispatch синхронный, async boundary —
    consumer-owned executor/scheduler.
- `docs/dev/sync.md`
  - обновить только если текст про delivery/reconcile требует упоминания Р1;
    не размазывать ingest→export в sync-док, если это локальный export контур.
- `core/ioc-application/src/main/java/com/iocextractor/application/export/README.md`
  - отметить, что recovery тоже эмитит `SliceCompleted` на
    `AVAILABLE→COMPLETED`.
- `core/ioc-application/src/main/java/com/iocextractor/application/ingest/README.md`
  - отметить `CanonicalArtifactsChanged` как post-completion control fact.
- `docs/KNOWN-ISSUES.md`
  - `OPS-7` перевести из `открыт` в `закрыт` со ссылкой на коммит/этап; не
    удалять строку — это правило шапки файла.
- `docs/ADR/0014-event-driven-ingest-to-delivery.md`
  - append-style обновить раздел «Статус»: датированная приписка
    `Р1/Р2 реализованы ...`, без переписывания исторического решения;
  - в этом implementation-plan пометить срезы 1-4 как выполненные, чтобы ADR не
    продолжал утверждать «кода ещё нет» после ship'а.

**Финальная проверка:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Рекомендуемая коммитная форма:**

1. `P1 recovery emits SliceCompleted`
2. `P2a emit CanonicalArtifactsChanged from ingest`
3. `P2b/P2c add export nudge fast-path`
4. `Docs mark ADR 0014 P1/P2 shipped`

Если при реализации всё выполняется за один проход, срезы 3 и 4 можно
объединить: они оба в bootstrap и вместе дают end-to-end fast-path. Срезы 1 и 2
лучше не объединять: у них разные bounded contexts и разные тестовые поверхности.
