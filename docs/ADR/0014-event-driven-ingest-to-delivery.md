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
  2. **Р2 — `CanonicalArtifactsChanged`** (M). Coordinator + эмиссия; backstop не
     трогаем. Развязывает export-латентность от поллинга.
  3. **Р3 — delivery fan-out** (L, на коннектор). Браться, когда появится реальный
     не-SMB target; проектировать сразу `DeliverySink` + обобщённый retention guard.
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
  no-op'ается до reconcile? Проверить (безвредно в любом случае, но определяет,
  материализуется ли выигрыш латентности).
- **Р2 гранулярность:** `CanonicalArtifactsChanged` per-artifact или per-run; нести
  ли значение revision для точного таргетинга; переиспользовать
  `RemoteFetchDetectionCoordinator` или отдельный маленький `ExportTriggerCoordinator`.
- **Р3 контракт:** форма `DeliverySink`/`DeliveryLedger` и как обобщённый retention
  guard агрегирует terminal-состояние по разнородным каналам; единообразна ли
  конфигурация delivery-целей.
- Заводить ли tracking-ID в KNOWN-ISSUES сразу (напр. `OPS-7` под ingest→export).
