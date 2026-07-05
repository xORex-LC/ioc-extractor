# Event-driven координация

Как устроена и **когда применяется** событийная координация в проекте: тонкий
control-plane контракт (`platform-events`) + keyed single-flight исполнение
(`platform-concurrency`). Документ отвечает на два вопроса разработчика/агента:
«стоит ли здесь вообще событие?» и «как его использовать, не нарушив рамки?».

> Статус: **реализовано**. Базовое ядро (ADR 0013, S0–S8) + опциональный SMB2
> `CHANGE_NOTIFY` push. Durable outbox / внешний брокер / DLQ — **сознательно
> отложенные seam'ы** (§7), не реализованы. «Почему так» — в
> [../ADR/0013-event-driven-coordination.md](../ADR/0013-event-driven-coordination.md).

## 1. Что это (и чем НЕ является)

`platform-events` — это **event model + publish contract, и только**. Не брокер,
не шина сообщений, не очередь, не durable delivery, не routing framework, не
subscriber SPI. Доставка — это концерн **адаптера** за портом
`ControlEventPublisher` (сейчас — in-process Spring bridge в bootstrap), а не
внутренность ядра.

Событийная координация — **не ambient-концерн**, как логирование. Логирование
применяют везде по умолчанию; события — в узком наборе ситуаций (§2), под
anti-broker инвариантом. Сейчас события используются **только** в sync-контуре
(fetch/publish); extraction/ingestion/output-mapping их не трогают. Ближе по духу
к «как мы делаем concurrency», чем к «как мы логируем».

Несущий инвариант (ADR 0013, реш. 2/11): **платформа задаёт event-модель и
контракт публикации; вся доставочная механика — за адаптером.**

## 2. Когда прибегать к событиям

Событие уместно, когда выполнены **все три** условия:

1. **Кросс-контекстный факт.** Факт в одном bounded context должен запустить
   работу в другом (export завершил срез → publish должен доставить; удалённый
   источник изменился → fetch должен забрать).
2. **Ускорение поверх корректного backstop.** Событие — это *fast-path* поверх
   периодического reconcile/poll, который **уже** гарантирует корректность сам по
   себе. Событие лишь сокращает латентность.
3. **Потеря терпима.** Пропажа события не нарушает корректность — backstop
   доберёт. Событие несёт *hint*, а не *единственный* сигнал.

Анти-паттерны — когда событие **не** нужно:

- нужна **гарантированная доставка / порядок / exactly-once** → это durable
  ledger + reconcile, а не in-process событие;
- нужен **request/response** (ответ, результат, ошибка вызывающему) → это вызов
  порта, а не событие;
- обе стороны в одном процессе и **хватило бы прямого вызова метода** → не
  плодить событие ради «слабой связанности» там, где её ценность нулевая;
- хочется **durable-очередь/DLQ/outbox сейчас** → это §7 (seam за адаптером), а
  не расширение `platform-events`.

Практический критерий: *«если это событие потеряется, что сломается?»* Если
ответ «ничего, backstop доберёт» — событие уместно. Если «данные/работа
потеряются» — нужен ledger, а не событие.

## 3. Контракт / API

```java
// platform-events (framework-free)
interface ControlEvent { ControlEventMetadata metadata(); }

record ControlEventMetadata(String eventId, String eventType, int eventVersion,
                            Instant occurredAt, String correlationId, String causationId) { … }

@FunctionalInterface
interface ControlEventPublisher { void publish(ControlEvent event); }   // publish-only

interface ControlEventObserver {                 // наблюдаемость доставки
    void published(ControlEvent e);
    void publishFailed(ControlEvent e, RuntimeException f);
    void dispatching(ControlEvent e, String handler);
    void dispatchFailed(ControlEvent e, String handler, RuntimeException f);
}
```

- **`ControlEvent`** — маркер + `metadata()`. Конкретное событие — это `record …
  implements ControlEvent` в том application-контексте, которому принадлежит факт
  (напр. `SliceCompleted` в `application/export`, `RemoteChangeBatchDetected` в
  `application/sync`). У каждого — стабильный `EVENT_TYPE` и `EVENT_VERSION`.
- **`ControlEventMetadata`** — трассировка и версия: `eventId`, `eventType`,
  `eventVersion`, `occurredAt`, `correlationId`, `causationId` (причинно-
  следственная цепочка; `withoutCausation(...)` для корневых событий). Это
  задел под будущие delivery-заголовки, **не** wire-format.
- **Публикация — publish-only и fire-and-observe.** `publish()` не возвращает
  результат и не бросает наружу: сбой публикации *наблюдается*
  (`publishFailed`), но не ломает вызывающую сторону — корректность держит
  backstop, а не успешность emit'а.
- **Wiring доставки — в bootstrap:** `SpringControlEventPublisher` мостит порт в
  Spring `ApplicationEventPublisher`; `LoggingControlEventObserver` пишет
  ECS-наблюдение dispatch'а (`EventCoordinationConfig`). Ядро о Spring не знает.

## 4. Модель корректности

**События — подсказка, ledger + reconcile — истина.** Правило без исключений:
**у каждого event-пути обязан быть не-event backstop.**

- **Идемпотентность — на durable-уровне**, не на событии: `remote_fetch_ledger`
  (по `path+size+mtime`) и `publish_ledger` (CAS-сага по `slice×target`) — они
  гарантируют, что повтор события/тика не породит дубль работы.
- **Reconcile — correctness backstop:** периодический проход сверяет реальное
  состояние (listing / каталог срезов) с ledger и добирает всё, что событие
  потеряло (пропущенный emit, рестарт, overflow push'а).
- **Keyed single-flight (`platform-concurrency`):** и fast-path (событие), и
  backstop (reconcile) идут через **один** `KeyedSerialExecutor` с ключом =
  endpoint. Поэтому работа по одному endpoint сериализована (не гоняется сама с
  собой), разные endpoints параллельны. `BoundedKeyedSerialExecutor` даёт
  bounded admission + shed-to-reconcile: при перегрузке работа сбрасывается, а не
  копится безгранично — её доберёт reconcile.

## 5. Разобранные примеры

**Fetch (detection ⊥ execution).** Триггеры (periodic / startup / push
CHANGE_NOTIFY) сходятся в `RemoteFetchDetectionCoordinator`, который single-flight
запускает `RemoteSourceMonitor.detect(source)`; тот эмитит
`RemoteChangeBatchDetected` (несёт список объектов — claim-check, не содержимое
файлов). `RemoteChangeFetchListener` claim'ит in-flight и ставит keyed-fetch по
endpoint. Backstop — periodic detection.

```text
periodic / startup / CHANGE_NOTIFY ─▶ Coordinator ─▶ RemoteSourceMonitor.detect
        └─ RemoteChangeBatchDetected ─▶ ControlEventPublisher ─▶ FetchListener ─▶ KeyedExecutor(endpoint) ─▶ fetch
```

**Publish.** `ExportService` по завершении среза эмитит `SliceCompleted` (несёт
`manifestSha256`). `SliceCompletedPublishListener` ставит keyed-publish. Backstop
— periodic reconcile каталога срезов × `publish_ledger`.

**CHANGE_NOTIFY как «внешний сигнал → то же событие».** SMB watch не несёт своих
фактов: его doorbell лишь триггерит обычный `detect`, который производит то же
`RemoteChangeBatchDetected`. Внешний push вклинивается в одну точку и не создаёт
второго пути корректности. Детали — [sync.md](sync.md).

## 6. Как добавить новый event-flow

OCP-рецепт (не трогая `platform-events`):

1. **Объявить событие** — `record … implements ControlEvent` в application-
   контексте факта, со стабильным `EVENT_TYPE`/`EVENT_VERSION` и `metadata()`.
2. **Эмитить в источнике факта** через `ControlEventPublisher.publish(...)` —
   там, где факт становится истиной (после commit/durable-записи, не до).
3. **Добавить listener в bootstrap**, который переводит событие в keyed-работу
   (`KeyedSerialExecutor.submit(WorkKey.of(key), …)`), а не делает тяжёлую работу
   в потоке публикации.
4. **Убедиться, что backstop есть** — периодический reconcile/poll, который
   добьёт потерянное событие. Нет backstop'а → это не event-кейс (§2), нужен
   ledger.
5. **Завести health/observability** — исход в `SyncHealthState`/аналог,
   dispatch через `ControlEventObserver`.

## 7. Эволюция: durable outbox и внешний брокер

Все ниже — **сознательно отложенные seam'ы** (ADR 0013 реш. 11; OPS-4 в
[../KNOWN-ISSUES.md](../KNOWN-ISSUES.md)). Ключевое: они подключаются как
**адаптер за `ControlEventPublisher`**, а не как расширение ядра `platform-events`
(OCP — ядро не трогаем).

| Seam | Когда строить (триггер) | Куда встаёт |
|---|---|---|
| **Durable outbox** (Richardson) | нужна at-least-once доставка поверх рестартов сверх того, что уже дают ledgers | адаптер `ControlEventPublisher` + outbox-таблица; polling publisher |
| **Внешний брокер** (Kafka/Rabbit) | **межпроцессная** доставка (несколько инстансов/сервисов), а не in-process | новый `adapter-*` за портом; ядро и события не меняются |
| **DLQ / redelivery policy** | брокер уже есть и нужна карантинная семантика | концерн брокер-адаптера, не ядра |
| **Spring Modulith externalization / module canvas** | стабилизация модульных границ, генерируемая карта событий | поверх текущего Spring bridge |

Пока ни один триггер не наступил: correctness держит reconcile-over-ledgers,
доставка in-process. Строить durable-инфраструктуру заранее — YAGNI.

## 8. Guardrails (enforced by ArchUnit)

Anti-broker инварианты держит не ревью, а сборка ([../BOUNDARIES.md](../BOUNDARIES.md),
`ArchitectureTest`):

- `platform_events_is_framework_free_and_not_a_broker` — в `platform.events`
  нет Spring/Jackson/`java.io`/SI/AMQP/Kafka/JMS/Camel и зависимостей на
  domain/application/adapter/bootstrap/concurrency.
- `control_events_are_not_serializable` — `ControlEvent` не `Serializable` (нет
  скрытого wire-format).
- `keyed_execution_is_not_part_of_platform_events` — keyed-executor/single-flight
  не живут в `platform.events` (разделение event-модели и исполнения).
- `platform_concurrency_is_framework_free_and_event_free` — `platform.concurrent`
  framework-free и не зависит от `platform.events`.
- `core_does_not_use_spring_event_types` — domain/application не знают
  `org.springframework.context` (Spring-мост только в bootstrap).

Нарушение любого — красная сборка. Меняешь событийный контур — сверяйся с этими
правилами, а не «на глаз».

## Референсы

- **Enterprise Integration Patterns** (Hohpe & Woolf) — Event Message,
  Publish-Subscribe Channel, Message Endpoint, **Idempotent Receiver**,
  **Claim Check** (событие несёт ссылки/идентити, не payload).
- **M. Fowler — «What do you mean by Event-Driven?»** — четыре модели; наша =
  **Event Notification** (тонкое событие, получатель сам идёт за данными), а не
  Event-Carried State Transfer / Event Sourcing / CQRS.
- **C. Richardson — microservices.io** — **Transactional Outbox**, Polling
  Publisher, **Idempotent Consumer** (референс для durable-seam'а §7).
- **Spring Modulith** — in-process доменные события за `ApplicationEventPublisher`,
  externalization, module canvas (референс текущего bridge и будущей карты событий).
- **G. Hohpe — reconciliation/«no distributed transactions»** — корректность
  через сверку состояния (reconcile), а не через гарантии доставки.
- **MS-SMB2 §2.2.35 (CHANGE_NOTIFY)** — источник опционального push-сигнала fetch;
  см. [sync.md](sync.md).

## Связанные документы

- Решения и обоснование: [../ADR/0013-event-driven-coordination.md](../ADR/0013-event-driven-coordination.md)
  (+ 0011 remote sync, 0012 export); расширение на цепочку ingest→export→delivery —
  [../ADR/0014-event-driven-ingest-to-delivery.md](../ADR/0014-event-driven-ingest-to-delivery.md).
- Главный потребитель: [sync.md](sync.md).
- Открытые seam'ы: OPS-4/OPS-6 в [../KNOWN-ISSUES.md](../KNOWN-ISSUES.md).
- Сервисы контура: [../SERVICES-CATALOG.md](../SERVICES-CATALOG.md).
