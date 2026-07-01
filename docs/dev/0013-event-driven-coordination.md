# 0013 — Гибридная модель координации: control-plane события + edge-IO + ETL-ядро

## Статус

**Принято, не реализовано.** Design-решение зафиксировано по итогам дизайн-диалога
поверх эксплуатации [0011](0011-remote-sync.md) (delivery/SMB) и
[0012](0012-streaming-dataframe-emission.md) (export-срезы). Документ вводит **трёх-плановую
модель координации** и инфраструктурный каркас событий (новый платформенный модуль
`platform-events`). Он **реализует и заменяет** раздел 0011 «Post-v1 уточнение модели:
detection и execution должны быть разделены» и техдолг **OPS-4**, обобщая их с частного
hardening sync до переиспользуемого application-каркаса. Кода ещё нет; ниже — обоснование,
модель, применимость, отклонённые альтернативы и план реализации по срезам.

**Hardening-проход после ревью свёрнут в документ (находки R0–R6):** **R0** —
`@TransactionalEventListener(AFTER_COMMIT)` не сработает без обрамляющей use-case-транзакции
(транзакции замкнуты внутри JDBC-адаптеров) → publish-after-commit по **program order** +
reconcile-backstop (реш. 8); **R1** — база называется `ControlEvent`, не `DomainEvent`
(DDD-терминология, реш. 2); **R2** — порт **publish-only**, `EventSubscriber`-SPI **не вводим**
(consume-seam = команда; уточнено R29) (реш. 2/2a); **R3** — publish fast-path **slice-specific** (`PublishCompletedSliceCommand` +
`CompletedSliceCatalog.find`, реш. 5); **R4** — fetch executor = **command handler**, детекция в
`RemoteSourceMonitor` (реш. 5); **R5** — **metadata-конверт** события (реш. 6); **R6** —
**`KeyedSerialExecutor`** вместо общего `@Async`-пула (реш. 9).

**Второй проход ревью (R7–R13):** **R7** — контракт `publish` **fire-and-observe**: producer не
падает из-за сбоя dispatch'а, ошибки → diagnostics/observer, корректность → reconcile (реш. 2);
**R8** — `eventId` ≠ ключ бизнес-идемпотентности (Idempotent Receiver по `(sliceId,targetId)` /
`RemoteObjectIdentity`, реш. 6); **R9** — `eventVersion` закладываем сразу (реш. 6); **R10** —
keyed executor: **FIFO/coalescing без потери факта работы** (не drop) + bounded-очередь и
shutdown/rejection-политика с reconcile-backstop (реш. 9); **R11** — fingerprint-кэш монитора —
latency-оптимизация, не source of truth; re-emit на рестарте безопасит ledger (реш. 5); **R12** —
единый `SpringControlEventPublisher` (bridge-bean), никто не дёргает `ApplicationEventPublisher` напрямую
(реш. 2/6); **R13** — `SliceCompleted` эмитится только в ветке `AVAILABLE→COMPLETED`, не через общий
`ExportObserver.completed` (который зовётся и на `SKIPPED`) (реш. 5).

**Третий проход ревью (R14–R23):** **R14** — порт переименован `EventBus`→**`ControlEventPublisher`**
(publish-only, in-process, non-durable; README-дисклеймер), bridge-bean = `SpringControlEventPublisher`
(реш. 2); **R15** — правило **событие=факт / команда=намерение**, запрет `*Requested`-псевдособытий
(реш. 6); **R16** — **MDC/correlation-проброс** через keyed-executor (`MdcScope`, реш. 9); **R17** —
**health очереди** (depth/oldest-age/rejected/running keys, реш. 10); **R18** — ключ executor'а = по
**внешнему ресурсу** (`endpoint+remotePath` при пересечении), не просто id (реш. 9); **R19** —
**bounded batch** `RemoteChangeBatchDetected(…, hasMore)`, не жирный payload (реш. 5–6); **R20** —
**startup reconcile** — обязательная фаза до fast-path (реш. 7); **R21** — **single-instance v1**;
multi-instance = leases/fencing seam (реш. 13); **R22** — **consumer-failure/poison**: bounded retry →
ledger `FAILED` → снятие с ключа → reconcile (**operator-visible failed state, не своя DLQ**,
реш. 9); **R23** — **TCK-style контракт-тесты** модуля для
`ControlEventPublisher`/`KeyedSerialExecutor` (E0/E1); **R24** — зафиксирована **архитектурная
граница**: `platform-events` = event model + publishing contract, доставка = адаптеры; **брокер
внутри ядра не строим** (реш. 2/11).

**Четвёртый проход — reference-grounded review + anti-broker инвариант (R25–R35):** **R25** —
зафиксирован несущий **anti-broker** инвариант: `platform-events` = event model + publish contract,
**не** механизм доставки; линейка «ядро даёт / не реализует / отдаёт адаптеру» + diff «при приходе
брокера = ядро 0 изменений» (реш. 2a); **R26** — `KeyedSerialExecutor` **вынесен из**
`platform-events` (общий примитив конкуренции, не события; cohesion/SRP) — реш. 9; **R27** — реш. 9
расщеплён на три концерна: (1) per-key single-flight (перманентно), (2) admission/queue (заменяемый
seam), (3) redelivery/durability/DLQ (**не строим**: reconcile сейчас, брокер потом); **R28** — ключ
executor'а = **endpoint** (реальная единица сериализации транспорта; per-path параллелизм на одном
endpoint в v1 недостижим — single-writer); **R29** — consume-side seam = **команда** (use-case input
port), не `EventSubscriber`-SPI: брокер-listener = тонкий транслятор в ту же команду; **R30** —
reconcile может стать **DB-driven** (ledger в SQLite приехал с ING-4), а не filesystem-rehash;
**R31** — явный **retention-инвариант**: не выкашивать срез с не-`SUCCEEDED` парой (TOCTOU
`SliceCompleted`→`find`); **R32** — **admission-control** на насыщенном ключе (без livelock
bounded-queue↔reconcile); **R33** — **Spring Modulith Event Publication Registry** зафиксирован как
delivery-адаптер за портом / отклонён по **гранулярности**: ретрай частичного отказа по targets
держит **только** ledger-reconcile (он же добивает потерянный триггер) → Modulith **аддитивен, не
заменяющий** (деталь — «Отклонённые»); **R34** — проставлены reference-опоры: реш. 8 ← Spring docs, реш. 6 ←
Fowler (Event Notification), реш. 5 ← EIP, реш. 9 ← Akka/single-writer; **R35** — имя события
унифицировано `RemoteChangeDetected`→`RemoteChangeBatchDetected`, уточнены порядок E2 (C2) и
`correlationId` cross-run (C3).

**Пятый проход — резолюции открытых вопросов + verification (R36):** закрыты Q4–Q9 —
**Q9** монитор на `PeriodicDaemonCycle` (CHANGE_NOTIFY/SI не вводим; латентность = короткий
`fetch.interval`); **Q6** reconcile DB-driven по `publish_ledger` (O(pending)) + discovery
dir-listing/анти-join/rehash-once (F3 сохранён); **Q7** admission high/low-water с гистерезисом,
no-op-shedding в reconcile, per-key, правило в коде; **Q4** fingerprint = `(path,size,mtime)`-set +
set-diff (= ключ fetch-ledger), без агрегированного хэша; **Q5** tx-границу не поднимаем (saga +
reconcile-backstop; outbox адаптер-локален) — **зазор «commit↔event» verified покрытым по коду**
(`_SUCCESS` durable атомарным rename раньше COMPLETED+события; discovery читает ФС, не событие).
**R36** — найдено при verification: `listCompleted` бросает на первом битом дочернем каталоге →
требование к E5: discovery **resilient** (skip+quarantine+diagnose, не abort).

Грунт — текущий код: `platform-etl` (Pipes-and-Filters: `Stage`/`Envelope`/`PipelineRunner`/
`PipelineObserver`), `application/sync` (`Retrier`/`RetryPolicy`, fetch/publish use-cases),
JDBC-леджеры (`RemoteFetchLedger`, `PublishLedger`), `adapter-ingest`
(единственное место Spring Integration в проде), `DiagnosticSink`/ECS-observability,
`DaemonFetchScheduler`/`DaemonPublishScheduler`.

## Контекст

### Триггер — реальный прогон 0011 на хосте

Sync доставляет данные корректно, но обнажил структурный изъян: **детекция изменений не
отделена от исполнения**, и каждая зона ответственности, работающая с удалённым хранилищем,
сама инициирует удалённую операцию на каждом тике своего таймера.

Из лога установившегося режима (оба планировщика тикают ~раз в 10 с при пустом хранилище):

- **Fetch** ([`DaemonFetchScheduler`](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DaemonFetchScheduler.java)
  → [`RemoteFetchService.fetch`](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteFetchService.java))
  на каждом тике первым делом зовёт `transport.list(endpoint, remotePath)` — **удалённый
  SMB-вызов**. Этот `list` одновременно является и проверкой «что-то изменилось?», и началом
  работы. При пустом источнике — `fetched=0, skipped=7` и две INFO-строки в лог.
- **Publish** ([`DaemonPublishScheduler`](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DaemonPublishScheduler.java)
  → [`ArtifactPublishService.publish`](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/ArtifactPublishService.java))
  на каждом тике делает **локальный** `sliceCatalog.listCompleted(profile)` —
  скан `var/export` + пере-декод манифеста + **пере-хеш всех файлов среза** + `ensurePending`
  по каждой паре `(slice, target)`, даже когда все пары уже `SUCCEEDED`. SMB не трогается, но
  это дорогая идемпотентная работа вхолостую плюс две INFO-строки.

### Что НЕ является проблемой (сверено с кодом)

- **Транспорт thread-safe и хорошо сделан.** [`SmbFileTransport`](../../adapters/adapter-transport-smb/src/main/java/com/iocextractor/adapter/out/transport/smb/SmbFileTransport.java)
  держит per-endpoint `synchronized` + `ConcurrentHashMap`-кэш клиентов, эвикцию на
  transient-сбое и честный `closeIdle()` по `idle-timeout`. Соединение в установившемся режиме
  **тёплое и переиспользуется** (трогается каждые ~10 с → idle 5m не наступает). Реконнект в
  логе — **разовая** stale-session история (сервер уронил idle-сессию: `Read timed out`), а не
  системный thrash.
- Значит «постоянно дёргает удалёнку» относится только к fetch и только в виде дешёвого `list`;
  publish удалёнку при пустой работе вообще не трогает. Источник боли — **холостая детекция,
  вшитая в исполнение** (fetch — по сети, publish — по локальному IO), и **лог-спам** на no-op
  циклах.

### Индустриальная опора

- **EIP** (Hohpe & Woolf, *Enterprise Integration Patterns*): Message/Channel/Endpoint, **Polling
  Consumer vs Event-Driven Consumer**, **Channel Adapter**, **Idempotent Receiver**, Dead Letter
  Channel. Конвейерная часть — **Pipes-and-Filters**.
- **Spring `ApplicationEventPublisher`** — встроенный in-process Observer/pub-sub; с 4.2 событие
  — любой POJO; `@TransactionalEventListener(AFTER_COMMIT)`, `@Async`, `@Order`, SpEL-`condition`,
  настраиваемый `ErrorHandler` у `ApplicationEventMulticaster`. **Не несёт** retry/DLQ/
  сериализации/durability — это примитив доставки, не каркас.
- **Transactional Outbox** (Richardson, microservices.io) — именованный ответ на durable
  at-least-once, **если** in-process событий недостаточно. Классический outbox решает dual-write
  **«БД + брокер»**; у нас брокера нет → классический outbox YAGNI (реш. 11). Под-проблему
  «атомарность state-change + dispatch при краше» **могли бы** закрыть Registry/outbox, но в этом ADR
  она закрывается **reconcile-over-ledgers** (реш. 7/8, зазор verified — Q5); Registry рассмотрен и
  **отклонён** ниже (Отклонённые альтернативы).
- **Spring Modulith — Event Publication Registry** — фреймворковый durable-журнал публикаций
  (`PUBLISHED→PROCESSING→COMPLETED/FAILED`, staleness-монитор, republish-on-restart, JDBC-стартер):
  каноничный «in-process outbox». Для нас — **кандидат-адаптер доставки за портом**, не часть ядра;
  отклонён в пользу reconcile-over-ledgers (реш. 7/11, Отклонённые).
- **Event Notification vs Event-Carried State Transfer** (Fowler, «The Many Meanings of EDA») — наши
  события **тонкие** (Event Notification: id + конверт, не данные); потребитель дочитывает состояние
  callback'ом к продюсеру (`CompletedSliceCatalog.find`). Жирный payload (ECST) — отвергнут (реш. 6/R19).
- **Data-plane оркестраторы** (Beam, NiFi, Airflow) — про движение/трансформацию **данных**, а не
  про координацию внутренних сервисов. Конфляция этих двух планов — категориальная ошибка,
  которую модель ниже разводит явно.

### Сквозной принцип проекта (несущая стена)

`core/ioc-domain` и `core/ioc-application` — **framework-free** (Spring/SI забанены ArchUnit'ом;
фреймворки только в adapters + bootstrap). Любое решение про события обязано это сохранить.

## Решения

### 1. Три плана координации, каждый в родной роли

Система разделяется на три ортогональных плана. Это центральное решение ADR; остальные его
детализируют.

| План | Назначение | Инструмент | Где живёт |
|---|---|---|---|
| **Control-plane** | Координация сервисов: **факты и сигналы**, не данные | **`ControlEventPublisher` port** (current adapter: Spring `ApplicationEventPublisher`) | порт в `platform-events`, адаптер в `bootstrap` |
| **Transformation** | Бизнес-ETL над одним источником: refang/extract/classify/dedup/write | **`platform-etl`** (Pipes-and-Filters как **библиотека**) | `core` (framework-free) |
| **Edge-IO** | Двигать **байты через границу системы** | **Spring Integration** (Channel Adapter/Polling Consumer) | adapters |

**Обоснование.** Каждый план имеет разную природу управления: control-plane — событийная
развязка (IoC к нашему коду через порт), transformation — синхронная композиция функций (мы
владеем control flow), edge-IO — фреймворк-владелец поллинга на кромке. Смешение планов и есть
исходный дефект (детекция в исполнении) и соблазн (ETL на SI). Разнеся их, получаем единый
**ментальный** каркас координации, не жертвуя framework-free ядром.

### 2. Control-plane — `ApplicationEventPublisher` за **publish-only** портом `ControlEventPublisher` (новый `platform-events`)

> **Архитектурная граница (несущая для всего ADR):**
> **`platform-events` задаёт *модель события приложения* и *контракт публикации*. Механизмы
> доставки — адаптеры.** (*“`platform-events` defines the application event model and publishing
> contract. Delivery mechanisms are adapters.”*)
>
> Это **не** «наш RabbitMQ/Kafka»: модуль — маленькое **EIP-ядро терминов и контрактов** (опора), а
> **не** реализация брокера. Spring-события сегодня, outbox / RabbitMQ / Kafka завтра — всё это
> **подключаемые адаптеры доставки за тем же портом**, не переписывание ядра. Это и есть OCP-точка:
> новая доставка = новый adapter, а не правка `core`. Любой соблазн завести внутри `platform-events`
> очередь/redelivery/DLQ/сериализацию/роутинг — **признак съезда в «мини-RMQ»** и должен
> отвергаться (durable-механика живёт в адаптере доставки, реш. 11).

Control-события циркулируют через **framework-free порт**, а Spring-события — **деталь
адаптера**. Прецедент в репозитории: `DiagnosticSink` → `LoggingDiagnosticSink`,
`CanonicalArtifactRepository` → Hikari-адаптер. Тот же приём.

- Новый платформенный модуль **`platform-events`** (рядом с `platform-etl`/`platform-diagnostics`):
  - **`ControlEvent`** — базовый тип события (НЕ `DomainEvent`: по DDD `DomainEvent` — это факт
    доменной модели/агрегата, а наши `SliceCompleted`/`RemoteChangeBatchDetected`/`PublishCompleted` —
    application/control-plane факты; терминологию не размываем). Несёт **минимальный
    metadata-конверт** (реш. 6), идентификаторы и счётчики — **без business data / bytes / rows**
    (bounded work-references / identities допускаются, напр. `RemoteChangeBatchDetected.items`, реш. 6/R19);
  - порт **`ControlEventPublisher` — publish-only** (имя честнее `EventBus`: это **не** durable
    message bus, не broker, не routing-framework — `platform-events/README` обязан это прямо
    зафиксировать, чтобы имя не обещало больше, чем даёт). Метод `publish(ControlEvent)`. Подписка в
    v1 — Spring `@EventListener`-бины в bootstrap, переводящие событие в **команду** (use-case input
    port). Framework-free `EventSubscriber`-SPI **не вводим вообще** (а не «откладываем»): стабильный
    consume-seam — это **команда**, и broker-listener потом транслирует сообщение в **ту же команду**
    (реш. 2a/R29). `EventSubscriber` был бы преждевременным куском брокера;
  - тестовые дублёры порта — **`NoopControlEventPublisher`**, **`RecordingControlEventPublisher`**;
  - bootstrap-реализация порта = **`SpringControlEventPublisher`** (единый bridge-bean, реш. 2 ниже);
  - `ControlEventObserver` — наблюдаемость публикации (мост к existing ECS, не новый стек).
- **Контракт `publish` — fire-and-observe, producer-нейтральный.** `publish(...)` **не
  пробрасывает** сбой доставки/постановки события в producer'а: исход бизнес-операции **не зависит**
  от dispatch'а события. Ошибки публикации/диспетчеризации идут в **diagnostics + `ControlEventObserver`**,
  корректность восстанавливает **reconcile** (реш. 7). Иначе получаем плохой UX: export уже durable
  COMPLETED, а CLI/daemon падает из-за сбоя постановки fast-path-события.
- Конкретные события — **рядом с bounded area** в `core/ioc-application`, не общей свалкой в
  `platform-events`.
- **Единый `SpringControlEventPublisher` (bridge-bean) в bootstrap** — единственная точка публикации:
  через него идёт и реализация framework-free порта `ControlEventPublisher` (для core-издателей), и
  adapter-originated события (inbox SI-flow / `RemoteSourceMonitor` на `PeriodicDaemonCycle`). Никто **не дёргает `ApplicationEventPublisher`
  напрямую** — иначе `ControlEventObserver` обходится и теряется единообразие наблюдаемости. Spring в
  core всё равно не попадает (core видит только порт).
- Подписчики — Spring-бины `@EventListener` (driving-адаптеры, зовущие use-case-команды).
  `@TransactionalEventListener`/`@Async` — только где это технически корректно (см. реш. 8–9).

**Почему не SI-`MessageChannel` для шины событий.** SI сейчас — *inbound channel adapter* (см.
реш. 4), а не шина доменных событий. Гонять control-события через SI-каналы = **новая поверхность
SI**, а развязка producer/consumer в одном JVM — прямое назначение `ApplicationEventPublisher`.
30-строчный адаптер на Spring-событиях проще SI-каналов. SI достаём только при появлении durable
queue / multi-step routing (реш. 11).

### 2a. Anti-broker boundary — несущий инвариант платформы

> **`platform-events` — это контракт-ядро (event model + publish contract), а НЕ механизм
> доставки.** Всё, что закрывает собой внешний брокер, ядро **не реализует**: оно либо живёт за
> портом как адаптер (сейчас), либо приходит вместе с брокером (потом). **Приход брокера = новый
> адаптер за тем же портом; ядро не трогается.** Любой PR в `platform-events` проверяется по линейке
> ниже — это защита от сползания в самодельный «мини-RMQ».

| | Концерн | Где живёт |
|---|---|---|
| ✅ **ядро ДАёт** | `ControlEvent` + неизменяемый конверт (`eventId/eventType/eventVersion/occurredAt/correlationId/causationId?`) — это и есть **интеграционный контракт** (брокер маппит конверт в message headers) | `platform-events` |
| | publish-only порт `ControlEventPublisher`; `ControlEventObserver`; тест-дублёры | `platform-events` |
| | дисциплина «событие = тонкий факт» (Event Notification) | `platform-events` |
| ❌ **ядро НЕ реализует** (это и есть брокер) | durable-очередь · redelivery / ack-nack / retry **доставки** · Dead Letter Channel · сериализация / wire-format · routing / matching подписок · ordering и competing-consumers **между процессами** | — (broker) |
| 🔌 **снаружи сейчас → заменит брокер** | in-JVM dispatch | `SpringControlEventPublisher` (bootstrap) |
| | исполнение + per-key single-flight | `KeyedSerialExecutor` (вне `platform-events`, реш. 9) |
| | at-least-once | reconcile-over-ledgers (остаётся **и** при брокере) |

**Consume-side seam = команда, НЕ subscription-SPI.** Стабильная граница на приёме — это **use-case
input port (команда)**, а не `EventSubscriber`. Сейчас тонкий `@EventListener`-бин переводит событие
в `PublishCompletedSliceCommand`/`RemoteFetchWork` и зовёт command handler; с брокером рядом встаёт
`@BrokerListener`, переводящий сообщение в **ту же команду**. Меняется только тонкий транслятор —
handler и use-case не вырезаются. Поэтому отсрочка `EventSubscriber`-SPI (реш. 2) — **соблюдение**
инварианта, а не недоделка (иначе SPI = преждевременный кусок брокера).

**Diff «в день интеграции брокера» (проверка не-вырезаемости):**
- `ControlEventPublisher` ← новая реализация-адаптер (broker-backed); порт тот же.
- consumer: рядом с `@EventListener` встаёт `@BrokerListener` → **те же команды**.
- bounded in-memory queue исполнителя ← заменяется broker-queue (admission-seam, реш. 9).
- reconcile-over-ledgers ← **остаётся** (теперь belt-and-suspenders поверх broker at-least-once).
- per-key single-flight ← **остаётся** (или переезжает в partitioning брокера по ключу).
- **`platform-events` — 0 изменений** (конверт уже = headers, порт уже publish-only).

ArchUnit-стражи инварианта: в `platform-events` запрещены зависимости на очереди/брокеры/сериализацию;
`KeyedSerialExecutor` **не** в `platform-events`; `ControlEvent` **не** `Serializable` до появления
брокера (сериализация активируется только адаптером доставки).

### 3. `platform-etl` остаётся ядром трансформации; SI в ядро не вносим

Pipes-and-Filters — паттерн; SI — один из его движков; **мы этот паттерн уже реализовали**
framework-free и по своим границам. «Перестроить ETL на SI» имеет ровно два исхода, и оба плохи:

- **(a)** стейджи остаются POJO, SI лишь связывает их service-activator'ами → `Stage`/`Envelope`
  не удаляются, а оборачиваются; выигрыш ≈ 0, зависимость в пути оркестрации +1;
- **(b)** стейджи становятся SI-эндпоинтами (`@Transformer`/`@Splitter`) → доменная логика
  (refang, RE2, PSL, match-policy) импортирует `org.springframework.integration.*`, теряет
  переносимость и framework-free тестируемость, ArchUnit падает.

Единственная чистая версия (a) бесполезна; полезная (b) ломает несущую стену.

**Главная причина — Library vs Framework / направление IoC.** `platform-etl` — **библиотека/SPI**:
control flow у нас (`runner.run(stages, envelope)`). SI — **фреймворк**: оркестрацию владеет он.
Смысл гексагона — не отдавать control flow ядра фреймворку. Это тот же аргумент, по которому в
0011 отвергнут Camel, а в 0012 — Spark («Dataflow-модель без тяжёлого фреймворка»).

**Бонусы SI ядру не нужны** (поток линейный, один файл за раз, демон однопоточный): retry уже
есть (`Retrier`), error-channel дублировал бы `DiagnosticSink`, splitter/aggregator/backpressure/
message-store/wiretap решают проблемы, которых нет; observability уже закрыта `PipelineObserver`
+ ECS; framework-free стейджи тестируются чистым JUnit без контекста.

**Условия пересмотра** (когда ETL-на-SI станет оправдан): много-маршрутность (N источников →
роутинг → M синков); параллелизм/backpressure между стадиями; durable рестарт с середины
конвейера (а не «перезапуск файла целиком»); распределение стадий по процессам. Сейчас не
выполнено ни одно.

### 4. SI остаётся и расширяется только на edge-IO (Channel Adapter)

Текущая роль SI в проде — единственный узел [`IngestFlowConfiguration`](../../adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/in/ingest/IngestFlowConfiguration.java):
`FileReadingMessageSource` (входной Channel Adapter) + опциональный `WatchService` +
`IngestFileListFilter` (stability quiet-period) + `Pollers.fixedDelay(reconcileInterval)` +
`.handle()` (Service Activator → `IngestSourceUseCase`). Байты строк он **не трогает** — отдаёт
`File`-ссылку в доменный конвейер.

Этот inbox-flow — **эталон** модели «WatchService (событие) + reconcileInterval-поллер (backstop
корректности)», то есть «события = латентность, поллинг = надёжность» уже в коде. Поэтому SI
расширяем строго на кромке IO:

- **inbox-детект** — уже есть;
- **`RemoteSourceMonitor`** для fetch — **удалённый близнец** inbox-flow (поллинг + fingerprint за
  SMB-портом). Это IO через границу системы, не доменная трансформация.

> **Уточнение (A3):** существующий remote-fetch сейчас **не на SI** (`DaemonFetchScheduler` =
> обычный `ScheduledExecutorService`), а реш. 12 вводит общий `PeriodicDaemonCycle`. Чтобы не
> плодить **два** механизма поллинга, выбор «монитор на SI vs на `PeriodicDaemonCycle`» **решён (Q9)
> в пользу `PeriodicDaemonCycle`**: SI здесь не даёт реального переиспользования
> (`FileReadingMessageSource` не говорит по SMB → всё равно кастомный `MessageSource`), а второй
> механизм поллинга не нужен. SI оправдан только в паре с CHANGE_NOTIFY-push (не вводим, реш. 11).
> И терминологически: монитор **двигает метаданные и рождает
> control-факт** — он ближе к детектору на стыке edge-IO/control-plane, чем к чистому edge-IO;
> таксономия реш. 1 здесь сознательно нестрогая.

### 5. Detection ⊥ Execution — единый каркас (реализует OPS-4)

Детекция работы выносится из исполнителей. **Channel Adapters** (источники событий) питают шину;
**Event-Driven Consumers** (исполнители) реагируют. Единообразие достигается на уровне
**шины + контракта потребителя**, а не «все источники пушат».

```text
[export-сага]            --emit SliceCompleted (after durable finish / program-order after commit)-->  ┐
                                                                  ├─►  ControlEventPublisher  ─►  Event-Driven Consumers
[RemoteSourceMonitor]    --emit RemoteChangeBatchDetected------>  ┘   (порт +         ├─ FetchExecutor   (RemoteFetchService)
   (monitor poll + fingerprint, тёплое соединение)                    ApplicationEventPublisher-адаптер)
                                                                                       ├─ PublishExecutor (ArtifactPublishService)
                                                                                       ├─ best-effort: metrics / cleanup / notify
```

- **Исполнители — command handlers, НЕ polling consumers.** Каждый исполнитель получает
  **конкретную единицу работы**, а не «иди разберись, что делать»:
  - **publish** — источник изменения **локальный, в том же JVM** (export-сага формирует срез с
    `_SUCCESS`). Настоящий event-driven, **бесплатно**: `SliceCompleted` → команда
    **`PublishCompletedSliceCommand(profile, sliceId, sliceName, manifestSha256)`** → исполнитель
    публикует **ровно один срез**. Никакого `publish(profile)` со сканом всех completed slices на
    fast-path — иначе выгода размывается. Для адресной выборки нужен порт-метод
    **`CompletedSliceCatalog.find(profile, sliceId)`** (find + верификация цепочки
    `_SUCCESS→manifest→files` одной единицы, не доверяем вслепую). Reconcile остаётся scan-based,
    но это **медленный backstop**, а не fast-path (и его стоит удешевить — DB-driven, реш. 7/R30).
    **Retention-инвариант (TOCTOU fast-path).** Между эмиссией `SliceCompleted` и `find` срез могла
    бы выкосить slice-retention, и тогда publish тихо потеряется (reconcile тоже сканирует уже
    пустую ФС). Поэтому жёстко: **retention НЕ выкашивает срез, у которого в `publish_ledger` есть
    хоть одна не-`SUCCEEDED` пара** (delivery-aware retention поверх ledger, а не по возрасту/
    счётчику). `PublishLedgerSliceRetentionGuard` обязан проверять именно это (R31).
    **Точка эмиссии строго специфична:** `SliceCompleted` поднимается **только при реально
    доступном новом срезе** — в ветке `AVAILABLE → COMPLETED` `ExportService` (после `ledger.finish`),
    **не** через общий `ExportObserver.completed(...)`. Тот зовётся и на **`SKIPPED`** (staging
    отброшен, нового slice нет — `ExportService` строки ~127/140), поэтому эмиссия события через
    lifecycle-observer без фильтрации дала бы ложный publish-триггер. Control-событие и
    lifecycle-observer **не смешиваем**.
  - **fetch** — источник изменения **на удалённом сервере**, который не пушит. Дешёвого
    event-driven не существует → **`RemoteSourceMonitor` = детектор**: `list` + include/exclude +
    **fingerprint-diff**, эмитит `RemoteChangeBatchDetected` с **конкретными work-items**
    (identities). `RemoteFetchService` перестаёт делать `list`/фильтрацию внутри и становится
    **command handler «скачай вот эти объекты»** (`RemoteFetchWork`). Транспортно-нейтрально
    (работает и для будущего S3/SFTP).
    **Состояние fingerprint-кэша монитора — latency-оптимизация, НЕ source of truth.** Форма (Q4) —
    in-memory **set of `RemoteObjectIdentity(path,size,mtime)`** (= ключ fetch-ledger, нового identity
    не вводим); detection = **set-diff** → bounded-батчи; без агрегированного хэша листинга. В v1 это
    **in-flight registry**, общий для monitor и listener: identity атомарно claim-ится listener-ом
    непосредственно перед admission в keyed executor, а monitor исключает claimed identity из
    следующих тиков. Claim ставится не до publish, иначе потерянный dispatch подавил бы повторную
    детекцию. После success/failure/rejection identity освобождается; `FETCHED` окончательно отсекает
    durable ledger. Registry — in-memory (durable monitor state — отложенный seam). На рестарте
    монитор вправе **переэмитить** уже известные identities; безопасность повторной эмиссии обязан
    обеспечить **ledger как Idempotent Receiver**. Корректность держится на ledger, не на registry.

**Асимметрия осознанная.** «Держать открытую сессию-watcher» отвергнуто: именно простаивающая
открытая SMB-сессия и протухает (тот самый `Read timed out`). Smart-poll переиспользует и
оздоровляет соединение, а не ждёт на мёртвом. Истинный push (CHANGE_NOTIFY) — реш. 11 (отложен).

### 6. Словарь control-событий, metadata-конверт и правило размещения издателя

**Metadata-конверт (`ControlEvent`).** Каждое событие несёт минимальный неизменяемый конверт —
**не** брокерные заголовки и **не** сериализацию, а опору для tracing/идемпотентности/будущего
outbox:

| Поле | Назначение |
|---|---|
| `eventId` | уникальный id **самого события** (event-level tracing/дедуп, ключ будущего outbox-relay). **НЕ** бизнес-ключ идемпотентности |
| `eventType` | стабильный тип (роутинг/фильтрация/метрики) |
| `eventVersion` | версия схемы события — **закладываем сразу** (дёшево), чтобы будущая сериализация/outbox мигрировали упорядоченно, а не хаотично |
| `occurredAt` | момент факта (`Clock`-инъекция, как везде в проекте) |
| `correlationId` | сквозная корреляция — **переиспользуем `ioc.run.id`** (0011 уже растянул его на pipeline/fetch/publish-цикл), не вводим новый |
| `causationId?` | опционально: какое событие/команда породило это (run→slice→publish-цепочка) |

**`eventId` ≠ ключ бизнес-идемпотентности.** Идемпотентность исполнения держат **Idempotent
Receivers по бизнес-ключам**, не по `eventId`: publish — `(sliceId, targetId)` в `PublishLedger`;
fetch — `RemoteObjectIdentity` в fetch-ledger. `eventId` — для трассировки и event-level дедупа
(будущий outbox), повторная доставка одного факта с тем же бизнес-ключом всё равно безопасна.
При желании событие может нести явный `deduplicationKey` (бизнес-ключ) как seam — но это **не**
заменяет ledger.

Граница YAGNI: конверт держим **минимальным**; разрастание в broker-метаданные — только вместе с
реальным брокером/outbox (реш. 11).

**Уточнение `correlationId` для cross-run событий (R35/C3).** `SliceCompleted` рождается под
`ioc.run.id` **export-прогона**, а publish, который он триггерит, — отдельная операция. Правило:
consumer **наследует** `correlationId` события (тянет цепочку run→slice→publish в одном следе), а
`causationId` несёт `eventId`/команду-предка. Новый `run.id` на стороне publish **не** заводим —
иначе след «факт→команда» рвётся.

**Словарь** — факты с идентификаторами, не данные (та же дисциплина, что manifest-coverage в
0012). Стартовый, расширяемый набор:

| Событие | Когда | Несёт | Издатель |
|---|---|---|---|
| `IngestionFinished` | файл извлечён в canonical + спроецирован (после commit) | `run_id`, `source.id`, counts | adapter (ingest-сага) → напрямую |
| `SliceCompleted` | export-сага закрыла срез (`_SUCCESS`, после commit) | `profile`, `slice_id`, `revision` | use-case → **через порт** |
| `RemoteChangeBatchDetected` | монитор зафиксировал дельту листинга | `endpoint`, `source.id`, `batchId`, **bounded** `items`, `hasMore` | adapter (монитор) → bridge |
| `PublishCompleted` | пара `(slice, target)` доведена до `SUCCEEDED` | `profile`, `slice_id`, `target_id` | use-case → **через порт** |

**Событие = факт (past tense), команда = намерение (imperative).** Тонкое событие = **Event
Notification** по Фаулеру (id + конверт, не данные; потребитель дочитывает состояние через `find`).
Жёсткое правило, чтобы шина не
выродилась в command-bus под видом event-bus: имена событий — **свершившиеся факты**
(`SliceCompleted`, `RemoteChangeBatchDetected`, `PublishCompleted`, `IngestionFinished`); имена
команд — **повелительные** (`PublishCompletedSliceCommand`, `RemoteFetchWork`). В v1 **запрещены**
«события»-намерения вида `PublishSliceRequested`/`FetchRequested`, если за ними нет реально
зафиксированного факта. Поток всегда: *факт-событие* → подписчик решает → *команда* исполнителю.

**Bounded batch для remote-дельты (НЕ жирный payload).** Если на удалёнке внезапно тысячи файлов,
одно событие со списком всех identities превратится в data-payload (нарушает «событие = факт+id, не
данные»). Поэтому монитор эмитит **ограниченные батчи**: `RemoteChangeBatchDetected(sourceId,
batchId, items, hasMore)` с `maxWorkItemsPerEvent`; `hasMore=true` означает «будут ещё батчи». Это
держит и память события, и keyed-executor под контролем.

**Правило размещения `publish()`** (определяет, нужен ли порт):

- издатель **внутри use-case** (доменный факт, напр. `SliceCompleted`) → публикуем через
  framework-free порт `ControlEventPublisher` (Spring в core нельзя);
- издатель **в адаптерной обвязке** (напр. SI-flow закончил перенос → `IngestionFinished`;
  монитор → `RemoteChangeBatchDetected`) → адаптер уже в bootstrap, публикует через **единый
  `SpringControlEventPublisher` (bridge-bean)** (реш. 2), а **не** raw `ApplicationEventPublisher` — чтобы
  `ControlEventObserver` не обходился. Framework-free порт тут не обязателен (издатель уже в bootstrap),
  но точка публикации — одна.

Не «портируем» всё подряд — framework-free порт только там, где издатель доменный; точка публикации
(bridge-bean) — общая для всех.

### 7. Классификация подписчиков: correctness-bearing vs best-effort

Подписчики **не равны**, и это определяет требования к надёжности:

- **correctness-bearing** (напр. триггер publish от `SliceCompleted`) — пропуск недопустим.
  Durability держит **не событие**, а **reconcile-over-ledgers**: событие — быстрый путь,
  периодический reconcile поверх durable `publish_ledger`/`remote_fetch_ledger` — надёжный
  backstop (at-least-once после крэша/потерянного события). Это прямое следствие 0011
  («события — оптимизация латентности, корректность на ledgers + reconcile»).
- **best-effort** (метрики/Prometheus, cleanup временных файлов, уведомления/Telegram) — потеря
  при крэше терпима; durable-шина не нужна.

Следствие: соблазн тащить outbox/DLQ ради метрик снимается — durable инфраструктура нужна только
correctness-носителям, а у них backstop уже есть (reconcile). Reconcile-цикл при этом понижается
до **редкого** safety-net (минуты), а не остаётся основным двигателем.

**Reconcile должен стать дешёвым, а не только редким — ING-4 это разблокировал (R30).** Сейчас и
`publish`, и `reconcile` идут через `CompletedSliceCatalog.listCompleted` → пере-декод манифеста +
**пере-хеш всех файлов** каждого среза (filesystem-scan). После прихода storage-слоя (SQLite-ледгеры,
ING-4) `export_run`/`publish_ledger` **уже хранят durable slice-metadata** (`slice_name`,
`manifest_sha256`) — основа для DB-driven reconcile есть, но **готового DB-backed `CompletedSliceCatalog`
ещё нет**: **E5 добавляет read-model/queries**. С ними reconcile-backstop **= DB-driven** (запрос
pending-пар по `publish_ledger`, O(pending)), а не filesystem-rehash
(O(срезы×файлы)). **Discovery** новых срезов (без пар в ledger) — дешёвый dir-listing + анти-join,
с **rehash-once** только новых; export-таблицы 0012 НЕ читаем (граница F3 — ФС). Так
«дорогая идемпотентная работа вхолостую» из OPS-4 убирается и с fast-path, и **с медленного пути**:
per-tick rehash исчезает, остаётся «раз на срез при обнаружении» (Q6 закрыт).

**Почему не Spring Modulith Event Publication Registry.** Фреймворк уже даёт ровно этот каркас
(durable-журнал публикаций, at-least-once, staleness-reconcile, republish-on-restart, JDBC-стартер).
Мы его **не берём**, осознанно: бизнес-**ледгеры уже являются Idempotent Receiver по бизнес-ключу**
(`(sliceId,targetId)`, `RemoteObjectIdentity`, реш. 6/R8), то есть at-least-once + reconcile-субстрат
**уже есть**; Modulith-журнал по `eventId` поверх них — **избыточная вторая durability**. И в любом
исходе Registry — это **delivery-адаптер за `ControlEventPublisher`**, а не часть `platform-events`
(реш. 2a). Зафиксировано в «Отклонённых альтернативах».

**Startup reconcile — обязательная фаза, не только фоновый backstop.** При старте демона порядок
строгий: сначала **синхронный reconcile/backfill** (verified slices × targets → `publish_ledger`;
remote-fetch backlog), и **только потом** включаются fast-path listeners/monitors. Это закрывает
downtime-window: накопленная за простой работа добивается сразу, а не ждёт следующего длинного
интервала. Прецедент уже есть — `DaemonPublishScheduler.start()` в 0011 делает reconcile перед
periodic-loop; обобщаем это правило на обе стороны и фиксируем как инвариант жизненного цикла
(совместимо с phase-ordering export→publish→retention).

### 8. Транзакционная привязка — publish строго после commit **по program order**, НЕ `@TransactionalEventListener`

**Важная техническая поправка (риск-находка ревью).** `@TransactionalEventListener(AFTER_COMMIT)`
срабатывает, только если в момент `publish` на потоке есть **активная Spring-транзакция**
([подтверждено Spring-документацией](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html):
без активной tx событие отбрасывается, если не задан `fallbackExecution`; а `fallbackExecution=true`
+ async может сработать **до** commit). В
проекте транзакции замкнуты **внутри JDBC-адаптеров** (`TransactionTemplate.execute(...)` в
`JdbcExportRunLedger.finish()` и т.п.), **вокруг use-case транзакции нет**. Значит к моменту
`controlEventPublisher.publish(...)` коммит уже произошёл, активной транзакции нет, и
`@TransactionalEventListener` без `fallbackExecution=true` событие **не обработает вовсе** (а с
`fallbackExecution=true` — выполнит немедленно, обессмыслив фазу). Поэтому:

- **Правило корректности — program order, а не фаза транзакции:** use-case публикует событие
  **строго после возврата** из `ledger.finish(...)`/проекции — то есть факт по построению уже
  «after commit». Подписчик — **обычный `@EventListener`** (синхронный hand-off на потоке
  издателя), затем тяжёлый IO уходит на keyed-executor (реш. 9).
- **`@TransactionalEventListener(AFTER_COMMIT)` применяем ТОЛЬКО там, где реальная Spring-tx
  оборачивает publish** (сейчас — нигде; появится, если транзакцию поднимут на уровень use-case).
- **Атомарность «state change + event publication»** (краш между commit и publish) закрывает **не**
  фаза слушателя, а **reconcile-over-ledgers** (реш. 7): потерянное событие восстанавливает
  следующий reconcile-тик. **Зазор verified покрытым** (Q5): `_SUCCESS` среза durable атомарным
  rename (`makeAvailable`) **раньше** COMPLETED-commit и события → discovery (`listCompleted`, ФС) на
  рестарте находит срез независимо от события. Истинный atomic-outbox / Spring-Modulith-подобный
  publication-registry — **отложенный seam** (реш. 11), и даже он **адаптер-локален** (outbox-row в
  той же tx ledger'а), use-case-tx не требует (Q5).
- Дисциплина «publish после успешной записи, не до» совпадает с DDD-правилом
  (publish-after-save).

### 9. Исполнение — **keyed single-flight executor**, не «просто `@Async`»

`ApplicationEventPublisher` лишь делает hand-off к multicaster'у — он **не обещает** ни
асинхронности, ни немедленности, ни порядка. Для publish/fetch важны две вещи, которых общий
`@Async`-пул не гарантирует: **порядок** и **отсутствие overlap по ключу работы**. Поэтому вводим
явную абстракцию, а не надеемся на пул:

**Расщепление трёх концернов (защита от брокер-creep, реш. 2a/R27).** `KeyedSerialExecutor` несёт
**только** (1) per-key single-flight (защита локального ресурса — нужна **и при брокере**); (2)
admission/очередь — **заменяемый seam** (in-memory сейчас, broker-queue потом, поэтому источник
работы — инъектируемая граница, а не зашитый `LinkedBlockingQueue`); (3) redelivery/durability/DLQ —
**не строим вообще** (reconcile сейчас, брокер потом). Сам executor — **общий примитив конкуренции,
а не события**: живёт **вне `platform-events`** (`platform-concurrency` или application-порт +
bootstrap-реализация), иначе платформенное ядро событий распухает в исполнителя (cohesion/SRP).

- **`KeyedSerialExecutor`/`SingleFlightWorkExecutor`** — контракт: «по каждому ключу работа
  исполняется последовательно и **без перекрытия**; разные ключи — параллельно».
- **Ключ = реальная единица конкуренции транспорта, а не «просто id сущности» (R28/B1).**
  `SmbFileTransport` уже сериализует **все операции per-endpoint** (`synchronized` по
  endpoint-монитору), поэтому единица overlap'а — **endpoint**, а не `endpoint + remotePath`: два
  work-item по одному endpoint всё равно сериализуются в транспорте, и более тонкий ключ дал бы
  **иллюзию** параллелизма. Стартовая раскладка: publish → `endpoint` (за ним `targetId`-метаданные),
  fetch → `endpoint`. Cross-path параллелизм на одном endpoint в v1 **недостижим** и помечен как seam
  (когда транспорт научится мультиплексировать). Правило (single-writer): ключ = ресурс, конкурентная
  запись которого ломает инвариант. Обобщает текущий **глобальный** `running`-CAS планировщиков в
  **per-key single-flight**.
- **Single-flight защищает boundary исполнения, а не только event-listener.** Все локальные пути,
  которые могут выполнить remote publish для endpoint — event fast-path, periodic reconcile/publish,
  manual/ops bridge если он появится в daemon graph — должны входить через один и тот же
  `KeyedSerialExecutor`. Иначе появляется обход single-flight: fast-path и scheduler могут
  одновременно вести одну `(slice,target)` пару, ledger CAS сохранит данные, но telemetry/health
  увидят ложный conflict. Reconcile/discovery без remote write может оставаться синхронным только до
  hand-off и выполняется **один раз на export profile**, а не per target; execution retryable work
  идёт через keyed boundary.
- **НЕ «drop при занятом ключе».** Сигнал нельзя терять: второй `SliceCompleted` по тому же
  `targetId` — это **новый slice**, не дубль. Контракт: *«no overlap per key; сигналы либо
  **FIFO-очередь** по ключу, либо coalescing **с гарантированным rerun** — но без потери факта,
  что есть ещё работа»*. Для **slice-specific publish — предпочтительно FIFO** (каждый slice —
  отдельная единица). Coalescing допустим только для «refresh»-сигналов с идемпотентным повтором
  (напр. reconcile-tick).
- **Shutdown / rejection policy (часть delivery-semantics, не мелочь):** очередь по ключу —
  **bounded** (backpressure, а не безграничный рост); при остановке демона — **graceful drain** с
  таймаутом (как нынешний `STOP_TIMEOUT`), затем `shutdownNow`; **недослитая/rejected работа НЕ
  теряется по корректности** — её переэмитит **reconcile** поверх ledgers (реш. 7), а сам факт
  отказа/недослива логируется и идёт в диагностику. Bounded-очередь + reconcile-backstop = ровно
  та же модель «fast path можно потерять, медленный путь добивает». **Admission при насыщении
  (R32/B4, Q7 закрыт):** **high/low-water с гистерезисом** (триггер `depth ≥ high-water` **ИЛИ**
  `oldest-age ≥ max-age`, сигналы — health реш. 10). Выше high-water fast-path-enqueue по ключу =
  **no-op** (DEBUG «shed to reconcile», без rejection-churn), ключ дренирует **reconcile** (DB-driven,
  Q6) в ledger-порядке; ниже low-water admission возобновляется. **Per-key** (медленный endpoint не
  глушит другие); water-marks/queue-size — конфиг, **правило сброса — в коде**; сброс **наблюдаем**
  (health-флаг + диагностика). Под насыщением slice-специфичность всё равно бесполезна — деградация в
  «reconcile дренирует все pending-пары endpoint» естественна (load-shedding на durable backstop).
- Контракт — framework-free, но **в `platform-concurrency` / application-порт, НЕ в `platform-events`**
  (реш. 2a/R26 — это примитив конкуренции, не события); реализация (`java.util.concurrent`) — в bootstrap.
- Подписчик-`@EventListener` только **ставит work-item в keyed-executor** и сразу возвращает поток
  издателя (export-сага/нотификатор не блокируются тяжёлым SMB-IO).
- Внутри work-item — **переиспользуем существующий `Retrier`/`RetryPolicy`**, не плодим новый
  retry-механизм.
- **MDC/correlation-проброс через hand-off.** При переходе на поток keyed-executor'а MDC теряется.
  Требование: work-wrapper **восстанавливает** `ioc.run.id` (=`correlationId`), `event.id`,
  `event.type`, `causation.id?` в MDC на время обработки (переиспускаем existing `MdcScope` из
  `platform-observability`), и чистит после. Иначе ECS-логи исполнителя теряют корреляцию
  факт→команда.
- **Consumer failure / poison-policy (operator-visible failed state, НЕ своя DLQ).** Падение
  work-item **не крутится вечно** в петле executor'а и **не блокирует ключ навсегда**: внутри —
  bounded retry (`Retrier`, max-attempts), затем item переводит ledger в **`FAILED`/retryable**,
  факт идёт в диагностику, item **снимается с ключа** (FIFO двигается дальше), а повтор отдаётся
  **reconcile**/ручному ops. Так «отравленное» событие не подвешивает очередь. **Важно — это не
  message dead-letter:** ledger `FAILED` — это **operator-visible failed state саги** (виден в
  health/диагностике, добивается reconcile'ом), а не наша собственная DLQ-инфраструктура. Настоящий
  dead-letter-channel появится только вместе с durable-доставкой (broker/outbox) и будет её
  адаптерным seam'ом (реш. 11), а не частью `platform-events`.

Это закрывает и прежний открытый вопрос про гранулярность executor'ов: ответ — keyed single-flight
(FIFO per key) с bounded-очередью, MDC-пробросом, poison→ledger-FAILED и reconcile-backstop, а не
shared pool.

### 10. Наблюдаемость и диагностика шины — переиспользуем стек

- `ControlEventObserver` логирует **publication / dispatch / admission** через existing
  `LogEvents`/ECS (термин «delivery» резервируем за broker/outbox-адаптерами — у in-process-шины
  гарантии доставки нет, реш. 2). No-op-сигналов нет по определению — событие = факт изменения, поэтому **лог-спам
  снимается структурно**: INFO только на реальные факты/сбои, рутинные no-op детект-тики уходят
  на DEBUG.
- Диагностика сбоев **публикации/диспетчеризации** — через `DiagnosticSink` (возможна категория `EVENTS`/переиспользование
  `SYNC`); `event.action` (`event_publish`/`event_dispatch`) и `ioc.*`-поля заводятся **вместе с
  producer'ом** (дисциплина полноты, как в 0011/0003).
- **Health/readiness для keyed-executor'а — operator-facing сигнал** (в `ioc health`/sync-health,
  реш. 9): **queue depth per key**, **oldest-work age**, **rejected count**, **running keys**. Без
  этого зависший SMB-publish даёт ложное «демон жив», пока очередь тухнет. Эти метрики дополняют
  существующий `SyncHealthIndicator` (последний fetch/publish, pending/failed пары).

### 11. YAGNI-seam'ы: сериализация, DLQ, durable outbox, CHANGE_NOTIFY — зарезервированы, не строим

Сознательно **не реализуем** сейчас. Ключевое: все они — **концерны адаптера доставки за
границей `ControlEventPublisher`** (реш. 2), а **не** внутренности `platform-events`. Когда
понадобятся — добавляем adapter (outbox / RabbitMQ / Kafka), ядро событий не трогаем (OCP). Порт/
модель проектируем так, чтобы это подключалось локально:

- **Сериализация событий** — нужна только при пересечении границы процесса или persist. In-process
  Spring-события — обычные объекты. Активировать с брокером/outbox.
- **DLQ** — имеет смысл только при durable queue с redelivery. In-process sync-событие при сбое
  просто бросает. Зарезервировать dead-letter-sink как seam.
- **Durable outbox-шина** — активировать, если reconcile-латентность/стоимость станут проблемой
  для correctness-носителей. Сейчас backstop = reconcile (реш. 7).
- **SMB2 CHANGE_NOTIFY** (истинный push для fetch) — **не вводим** (Q9). Латентность входа в v1
  регулируется **коротким `fetch.interval`** (smart-poll на тёплом соединении), чего достаточно без
  sub-second SLA. CHANGE_NOTIFY **строго аддитивен** (поллинг/reconcile остаётся — push теряет
  события при server-side overflow), SMB-специфичен, тащит хрупкий watch-lifecycle (срыв → re-arm +
  reconcile) + keepalive против rot открытой сессии (та же протухающая сессия, что отвергнута в
  реш. 5), и **smbj-поддержку надо сперва проверить**. Когда/если приземлим — хостится через SI
  `@InboundChannelAdapter` (тогда же пересматривается Q9). Критерий входа: измеренный latency-SLA,
  который поллинг доказуемо не вытягивает.

### 12. Границы и устранение дублирования

- **ArchUnit:** `org.springframework.context.ApplicationEvent*`/`ApplicationEventPublisher` и
  `@EventListener` **не видны из `core`**; порт `ControlEventPublisher` в `platform-events` — framework-free
  (как `platform-etl`/`platform-diagnostics`). Spring-мост — только в bootstrap.
- **DRY:** общий `PeriodicDaemonCycle`/каркас исполнителя (композиция, **не наследование** —
  lifecycle не наследуем) гасит ~95% дублирования между `DaemonFetchScheduler` и
  `DaemonPublishScheduler` (CAS-guard, single-thread executor, `closeIdle` в finally, stop/phase,
  start/complete-логи). Исполнители поставляют только work-`Runnable`.

### 13. Область применимости — **single-instance v1** (multi-instance = leases/fencing, seam)

Явная enterprise-оговорка: `KeyedSerialExecutor` гарантирует single-flight **только внутри одного
JVM**, не между процессами. Поэтому **v1 рассчитан на один экземпляр демона**. Если когда-нибудь
запустим несколько экземпляров (HA/масштабирование), понадобятся **ledger leases / fencing tokens /
claim-семантика** на уровне service-DB, иначе два процесса возьмут одну (slice,target)/identity
параллельно. Частичный задел уже есть в 0012 (DB-backed global single-flight через partial unique
index + OS file-lease для recovery-ownership), но fetch/publish-исполнители на него пока не
опираются. **Cross-process координация — отложенный seam**, не v1; в доке фиксируем как известную
границу, чтобы её не приняли молча за «работает в кластере».

## Модель в сборе

```text
                 ┌──────────────────────── bootstrap (Spring) ────────────────────────┐
 edge-IO (SI)    │  inbox FileReadingMessageSource ──► IngestSourceUseCase            │
                 │  RemoteSourceMonitor (monitor poll+fingerprint) ──┐                │
                 │                                              │ emit                │
 control-plane   │   SpringControlEventPublisher bridge (ApplicationEventPublisher,   │
                 │     @EventListener adapters, keyed executor, ControlEventObserver)     │
                 └───────────────▲───────────────────────────────────┬───────────────┘
                                 │ publish(ControlEvent) via порт     │ dispatch → keyed executor
 ┌─────────────── core/application (framework-free) ─────────────────┼───────────────┐
 │  use-cases ──► ControlEventPublisher.publish (publish-only порт)               ▼               │
 │  platform-etl (Stage/Envelope/PipelineRunner) ── transformation   FetchExecutor   (command handler)
 │  Retrier/RetryPolicy · *_ledger (Idempotent Receiver) · reconcile PublishExecutor (one slice/cmd) │
 └───────────────────────────────────────────────────────────────────────────────────┘
```

**Карта переиспользования** (DRY на уровне репозитория):

| Потребность | Что уже есть | Берём как |
|---|---|---|
| retry/backoff | `Retrier`/`RetryPolicy` (`application/sync`) | исполнитель зовёт существующий |
| идемпотентный приём | `RemoteFetchLedger`/`PublishLedger` | Idempotent Receiver + reconcile backstop |
| трансформация | `platform-etl` стейджи | ядро, не трогаем |
| наблюдаемость | `LogEvents`/ECS/`PipelineObserver` | `ControlEventObserver` поверх |
| диагностика | `DiagnosticSink` | сбои доставки |
| edge-poll эталон | inbox `FileReadingMessageSource` | шаблон `RemoteSourceMonitor` |

## Применимость

1. **Hardening sync (немедленная цель, = OPS-4).** Fetch → `RemoteSourceMonitor` (smart-poll +
   fingerprint) emits `RemoteChangeBatchDetected`; `FetchExecutor` качает только дельту. Publish →
   `SliceCompleted` от export-саги триггерит `PublishExecutor`; reconcile понижен до редкого
   backstop; пере-хеш каждый тик исчезает. Лог-спам снят структурно (реш. 10).
2. **Переиспользуемость (главный мотив ADR).** Любой новый кросс-каттинг-потребитель (метрики,
   cleanup, Telegram-отчёт админам) подвешивается как **независимый best-effort подписчик** без
   правки конвейера — это и есть «инфраструктурный каркас, чтобы не изобретать подписку/логирование/
   обработку ошибок заново». Коннекторы — каждый своим адаптером, по потребности (YAGNI: строим
   seam, не коннекторы).
3. **Будущие саги** (STIX/OpenIOC export sink из бэклога) садятся на тот же контракт событий и
   reconcile-backstop.

## Следствия

- Новый модуль **`platform-events`**: `ControlEvent` (+ metadata-конверт), **publish-only** порт
  `ControlEventPublisher`, `NoopControlEventPublisher`/`RecordingControlEventPublisher`, `ControlEventObserver`,
  package-info/README (дисклеймер «не durable bus/broker/routing»). Версия в parent
  `dependencyManagement` — событий-only, без внешних библиотек (framework-free). **`KeyedSerialExecutor`
  — НЕ здесь** (отдельный `platform-concurrency` или application-порт, реш. 2a/9). `EventSubscriber`-SPI
  **не вводим** (consume-seam = команда, реш. 2a).
- **bootstrap:** `ApplicationEventPublisher`-адаптер порта, `@EventListener`-driving-бины,
  `KeyedSerialExecutor`-реализация (`java.util.concurrent`), `RemoteSourceMonitor` (poll+fingerprint
  за SMB-портом, **на `PeriodicDaemonCycle`** — Q9), общий `PeriodicDaemonCycle`.
  `@TransactionalEventListener` — **только** где реальная Spring-tx (сейчас нигде).
- **application:** use-cases публикуют control-факты через порт **после** возврата из
  ledger/проекции (реш. 8); `RemoteFetchService`/`ArtifactPublishService` становятся **command
  handlers** (одна единица работы), а не polling consumers; reconcile-методы остаются scan-based
  safety-net; новый порт-метод `CompletedSliceCatalog.find(profile, sliceId)`; конкретные события —
  record-факты рядом с bounded area.
- **Наблюдаемость/диагностика:** `event.action` (`event_publish`/`event_dispatch`), `ioc.*`-поля,
  возможная `DiagnosticCategory.EVENTS`; регенерация diagnostic-catalog при появлении кодов.
- **ArchUnit:** правило «Spring events не видны из core»; `platform-events` остаётся framework-free
  **и anti-broker** (нет зависимостей на очереди/брокеры/сериализацию; `KeyedSerialExecutor` не в
  `platform-events`; `ControlEvent` не `Serializable` до появления брокера).
- **Доки:** обновить `architecture.md` (три плана), `ingestion.md`/`sync.md` (event-driven контур),
  `logging.md`/`diagnostics.md` (event-таксономия); строка в `docs/dev/README.md`; закрыть/
  пере-адресовать **OPS-4** на этот ADR в `techdebt.md`.
- `DaemonFetchScheduler`/`DaemonPublishScheduler` схлопываются в общий каркас + тонкие
  исполнители-подписчики.

## План реализации по срезам

Каждый срез — отдельный коммит, обновление directory README затронутых каталогов и тесты на новую
логику/инварианты. Порядок: сначала framework-free каркас, затем мост и наблюдаемость, потом
перевод sync-сторон, в конце — edge-монитор и финальный гейт.

- **E0 — `platform-events` контракт.** `ControlEvent` + metadata-конверт, **publish-only** порт
  `ControlEventPublisher`, `NoopControlEventPublisher`/`RecordingControlEventPublisher`, `ControlEventObserver`, ArchUnit-правила: framework-free **и anti-broker** (нет зависимостей на
  очереди/сериализацию; `ControlEvent` не `Serializable`), README-дисклеймер. **`KeyedSerialExecutor`-
  контракт сюда НЕ входит** — общий примитив конкуренции, выносится в E0a (реш. 2a/9). **TCK-style
  контракт-тесты** (как `IngestionLedger` TCK в `ioc-application-tck`): `ControlEventPublisher` —
  fire-and-observe, observer-called-on-failure. **БЕЗ** `EventSubscriber`-SPI (consume-seam = команда,
  реш. 2a). Коммит: `ARCH: introduce control event publisher port`.
- **E0a — `platform-concurrency` (keyed single-flight).** `KeyedSerialExecutor`/
  `SingleFlightWorkExecutor`-контракт (per-key FIFO no-overlap, admission-seam, **ключ = endpoint**),
  framework-free, TCK: no-overlap per key, FIFO per key, different keys parallel, shutdown-drain,
  rejection-observed. **БЕЗ** durable redelivery/DLQ (реш. 2a/9). Коммит:
  `ARCH: introduce keyed single-flight executor`.
- **E1 — Spring-мост в bootstrap.** Единый **`SpringControlEventPublisher` (bridge-bean)** (реализация
  порта + точка для adapter-originated событий), `@EventListener`-driving-бины → use-case-команды,
  `KeyedSerialExecutor`-реализация (FIFO per key, bounded-очередь, graceful drain),
  `ControlEventObserver`→ECS. **`SpringControlEventPublisher` гоняет E0-TCK; реализация
  `KeyedSerialExecutor` гоняет E0a-TCK**; плюс тесты: publish-after-commit по program order, hand-off
  без блокировки издателя, **MDC/correlation-проброс** через keyed-executor,
  poison→`FAILED`+снятие-с-ключа, health-сигналы очереди. **НЕ** `@TransactionalEventListener` (нет
  обрамляющей tx). Коммит: `FEATURE: bridge event bus to Spring`.
- **E2 — общий каркас исполнителя.** `PeriodicDaemonCycle` (composition) + перенос overlap-guard/
  closeIdle/stop/phase; рефактор обоих планировщиков без смены поведения. Коммит:
  `REFACTOR: extract periodic daemon cycle`. **Sequencing (C2):** E2 извлекает периодический цикл, а
  E3/E4 тут же выхолащивают периодический fast-path в редкий reconcile-backstop — поэтому общую
  абстракцию проектируем **сразу под конечную роль** (`ReconcileCycle` отдельно от
  keyed-executor-driver), а не под «периодический-всё».
- **E3 — publish event-driven (slice-specific).** `SliceCompleted` эмитится **только в ветке
  `AVAILABLE→COMPLETED`** `ExportService` (не через `ExportObserver.completed`, который зовётся и на
  `SKIPPED`), publish после commit (реш. 8) → `PublishCompletedSliceCommand(profile, sliceId, …)` →
  `CompletedSliceCatalog.find(profile, sliceId)` → публикация одного среза через keyed-executor;
  убрать пере-хеш каждый тик (fast-path трогает один срез). Тесты: SKIPPED **не**
  триггерит publish; потерянное событие добивает reconcile. Коммит:
  `FEATURE: drive publish by slice-completed events`.
- **E4 — fetch detection/execution split.** `RemoteSourceMonitor` (monitor poll + include/exclude +
  fingerprint) emits `RemoteChangeBatchDetected` с work-items; `RemoteFetchService` → command handler
  «скачай эти identities» (перестаёт сам делать `list`/фильтр); ledger — backstop. Коммит:
  `FEATURE: split remote fetch detection from execution`. **Ключ executor'а = endpoint** (реш. 9/B1);
  **монитор на `PeriodicDaemonCycle`** (Q9 закрыт), CHANGE_NOTIFY/SI не вводим (реш. 11).
- **E5 — DB-driven reconcile, наблюдаемость, лог-альтитуда, доки, гейт.** **DB-driven
  reconcile-backstop** (гон пар по `publish_ledger`, O(pending); discovery новых срезов =
  dir-listing + анти-join + rehash-once; F3-граница сохранена, реш. 7/R30/Q6).
  **Discovery resilient к битому срезу (R36):** один corrupt/partial/leftover каталог в
  `root/<profile>/` НЕ должен ронять discovery всех срезов профиля — **skip + quarantine +
  diagnose**, не abort. (Сейчас `FileSystemCompletedSliceCatalog.listCompleted` **бросает** на первом
  невалидном дочернем каталоге → один битый срез блокирует backstop профиля; backstop обязан
  переживать «грязные» состояния.) `event.action`/поля/диагностика, INFO→DEBUG на рутинных тиках,
  обновление published docs, закрытие OPS-4; e2e: событие→command handler,
  потеря-события→reconcile-восстановление, publish-after-commit корректность, keyed single-flight,
  **reconcile без per-tick rehash**, **битый срез не блокирует reconcile профиля**. Коммит:
  `TEST: gate event-driven coordination end to end`.

**Финальный гейт:**

```bash
./mvnw -B -ntp -T 1C verify
```

## Открытые вопросы

1. ✅ **Где живёт словарь событий** — РЕШЕНО (реш. 2): базовый `ControlEvent` + инфраструктура в
   `platform-events`, конкретные record-факты — рядом с bounded area в `application`.
2. ✅ **Reuse `SYNC` в v1** — РЕШЕНО (CODE-дисциплина «код заводится с producer'ом»): реальных кодов
   сбоя доставки сейчас нет (`publish` — fire-and-observe, реш. 2; ошибки исполнителя ложатся на
   `SYNC.*` — события обслуживают ровно sync-контур fetch/publish). Отдельную
   `DiagnosticCategory.EVENTS` заводим **только** при первом кросс-доменном (не-sync) потребителе шины
   со своими кодами, не укладывающимися в `SYNC`. До тех пор — `EVENTS` это seam, не категория.
3. ✅ **Гранулярность/ключ executor'а** — РЕШЕНО (реш. 9/R28): `KeyedSerialExecutor` (single-flight),
   **ключ = `endpoint` в v1** (реальная единица сериализации транспорта — `SmbFileTransport`
   сериализует per-endpoint); более тонкий ключ (`endpoint+remotePath`) — только если транспорт
   научится мультиплексировать. Не shared `@Async`-пул.
4. ✅ **Fingerprint = `(path,size,mtime)`-set + set-diff** — РЕШЕНО (реш. 5): in-memory набор
   `RemoteObjectIdentity(path,size,mtime)` — **тот же ключ, что у fetch-ledger** (нового identity не
   вводим); detection = set-diff → bounded-батчи work-items (R19); ledger — backstop-дедуп.
   **Без** агрегированного хэша листинга (negligible выгода — `list` и так делаем каждый тик,
   set-diff тривиален). Content-hash change-detection — **seam** (SMB не даёт дешёвый server-side
   хэш, то же ограничение, что в `publishAtomically`-верификации 0011). Cap/LRU кэша — seam на случай
   огромных каталогов.
5. ✅ **Tx-границу НЕ поднимаем на use-case** — РЕШЕНО (реш. 7–8): (1) подъём конфликтует с
   saga-моделью (пошаговые CAS-коммиты + recovery, не одна большая tx — регресс); (2) atomicity даёт
   лишь скорость восстановления редкого crash-window, который **reconcile уже покрывает** (YAGNI);
   (3) даже будущий outbox **адаптер-локален** (терминальный статус + outbox-row в одной tx ledger'а +
   relay), use-case-tx не требует. **Зазор «commit ↔ event» verified покрытым по коду:** `_SUCCESS`
   среза становится durable атомарным rename в `makeAvailable` — **раньше** COMPLETED-commit и
   события; reconnect/restart → `listCompleted` (ФС) → `ensurePending` → publish (discovery читает
   диск, не событие). Пересмотр — только если появится потребитель с истинной exactly-once на
   НЕ-идемпотентный сайд-эффект, невыразимый идемпотентным receiver'ом по бизнес-ключу.
6. ✅ **DB-driven reconcile** — РЕШЕНО (реш. 7/R30): reconcile-backstop = **DB-driven по
   `publish_ledger`** для гона известных пар (`status NOT IN (SUCCEEDED, ABANDONED)`, O(pending), без
   касания ФС). **Discovery** новых срезов (crash-window: событие потеряно И упали до `ensurePending`)
   — дешёвый **dir-listing + анти-join** с `publish_ledger`, и **rehash-once** только новых,
   незаведённых срезов; **НЕ** чтение export-таблиц 0012 — граница F3 (formation↔delivery = файловая
   система) сохранена. Per-tick rehash убран **с медленного пути полностью**, а не «реже»: rehash
   остаётся только «раз на срез при обнаружении». Вводим: fast-path `find` — E3, DB-driven
   reconcile + discovery-by-listing — E5. Рассинхрон БД↔ФС держит retention-инвариант R31.
7. ✅ **Admission насыщенного ключа** — РЕШЕНО (реш. 9/R32): **high/low-water с гистерезисом**,
   триггер `depth ≥ high-water` **ИЛИ** `oldest-age ≥ max-age`. Выше high-water fast-path-enqueue по
   ключу = **no-op** (событие наблюдается на DEBUG «shed to reconcile», без rejection-churn) — ключ
   дренирует **reconcile** (DB-driven, Q6) в ledger-порядке; ниже low-water admission возобновляется.
   **Per-key** (медленный endpoint не глушит другие). Water-marks/queue-size — конфиг; **правило
   сброса — в коде** (корректность). Сам сброс **наблюдаем** (health-флаг «key shed to reconcile» +
   диагностика), иначе тихая деградация спрячет залипший таргет.
8. ✅ **Buy-vs-build Spring Modulith** — РЕШЕНО (реш. 7, Отклонённые): не берём, ледгеры уже дают
   business-level at-least-once; Registry — потенциальный delivery-адаптер за портом, не часть ядра.
9. ✅ **Монитор на `PeriodicDaemonCycle`** — РЕШЕНО: монитор сидит на общем `PeriodicDaemonCycle`
   (один механизм поллинга, не два). SI здесь не даёт реального переиспользования
   (`FileReadingMessageSource` не говорит по SMB → всё равно кастомный `MessageSource`). Латентность
   входа регулируется **коротким `fetch.interval`** (5–15s; `list` на тёплом соединении дёшев) —
   достаточно без жёсткого sub-second SLA. **SI и CHANGE_NOTIFY связаны и отложены вместе** (реш. 11):
   SI как `@InboundChannelAdapter` оправдан только если/когда приземлим CHANGE_NOTIFY-push.

## Отклонённые альтернативы (сводно)

- **ETL-ядро на Spring Integration** — реш. 3: library-vs-framework (инверсия IoC к фреймворку в
  центре), пробой dependency rule в варианте (b), бесполезность варианта (a), бонусы SI не нужны
  линейному однопоточному потоку. Консистентно с отказом от Camel (0011) и Spark (0012).
- **SI-`MessageChannel` как шина доменных событий** — реш. 2: новая поверхность SI ради того, что
  `ApplicationEventPublisher` делает «из коробки» проще.
- **Spring-события прямо в `application`** — реш. 12: пробивает framework-free ядро; решается
  портом + bootstrap-адаптером.
- **Держать открытую SMB-сессию-watcher** — реш. 5: простаивающая сессия протухает; smart-poll
  надёжнее, CHANGE_NOTIFY — отдельная честная опция (реш. 11).
- **Durable outbox/DLQ/сериализация сейчас** — реш. 7/11: correctness держит reconcile-over-ledgers;
  durable-инфраструктура — YAGNI до реальной потребности, seam зарезервирован.
- **Истинный push (CHANGE_NOTIFY) в v1** — реш. 11: SMB-специфичный хрупкий watch-lifecycle;
  оптимизация латентности, не основа корректности.
- **Самодельный брокер внутри `platform-events`** («мини-RMQ»: своя durable-очередь, redelivery,
  DLQ, сериализация, роутинг) — реш. 2/11: ядро задаёт только event model + publishing contract;
  доставочная механика — адаптер за `ControlEventPublisher`. Строить брокер внутри — переусложнение
  и нарушение OCP-границы.
- **Spring Modulith Event Publication Registry как наш durable-слой** — реш. 2a/7. Рассмотрено
  всерьёз (после ING-4 JDBC-стартер дёшев, framework-free-граница не страдает — Registry жил бы в
  bootstrap за портом), но отклонено по **гранулярности at-least-once**:
  - единица гарантии Modulith — **событие** (`eventId`; `COMPLETED` = «листенер вернул управление»);
    наша единица корректности — **пара `(slice, target)`** (бизнес-ключ; `SUCCEEDED` = сверка remote
    `_SUCCESS`-маркера). Одно `SliceCompleted` разворачивается в **N пар**;
  - при **частичном отказе по targets** (A — успех, B — недоступен) листенер вернул управление →
    событие `COMPLETED` → Modulith пару B **не передоставит**. Ретрай `(slice, B)` возможен **только**
    через reconcile-over-`publish_ledger` (`ArtifactPublishService.publishRetryable`);
  - значит **reconcile-over-ledgers обязателен в любом случае** (единственный путь ретрая частичного
    отказа), и он же **как побочный эффект** восстанавливает потерянный триггер (срез `COMPLETED` на
    диске → reconcile подберёт). Поэтому event-durability Modulith **избыточна**, а не «не нужна»;
  - итог: Modulith был бы **аддитивным, не заменяющим** — пришлось бы держать event-registry **плюс**
    тот же ledger-reconcile. Больше машинерии, не меньше.

  Плюс два побочных минуса: **(1) не drop-in** — Registry требует publish **внутри обрамляющей tx**,
  а у нас транзакции замкнуты в JDBC-адаптерах (реш. 8) → форс смены границы tx на use-case (откр.
  вопрос 5); **(2)** `@ApplicationModuleListener` диспетчит на **общем async-пуле без per-key
  сериализации** → overlap по endpoint не решает, `KeyedSerialExecutor` (реш. 9) остаётся всё равно
  (Modulith скорее усугубил бы overlap). **Пересмотр — только если** перевернётся Q5 (нужна истинная
  атомарность, а не at-least-once) или Q6 (reconcile дорог несмотря на DB-driven); заходит тогда за
  `ControlEventPublisher` как delivery-адаптер (anti-broker, реш. 2a).
- **Вообще без шины — прямые порты-слушатели (`SliceCompletionListener`/`RemoteChangeListener`)** —
  рассмотрено и отвергнуто: detection⊥execution и async hand-off достижимы прямыми портами +
  keyed-executor **без** `ApplicationEventPublisher`. Но это убирает **платформенный seam**, который
  должен быть broker-ready и не вырезаться при интеграции брокера (реш. 2a), и единую точку
  наблюдаемости/расширяемости (метрики/cleanup/notify через OCP). Async/decoupling оправдывают
  **executor**; шину-как-контракт оправдывает **broker-readiness + fan-out**. Поэтому платформу
  держим, но дисциплинированно тонкой (реш. 2a).

> Связанные документы: [0011](0011-remote-sync.md) (реализует его «Post-v1 уточнение модели» и
> OPS-4), [0012](0012-streaming-dataframe-emission.md) (источник `SliceCompleted`),
> [0005](0005-services-and-pipeline.md) (ETL-конвейер), [0007](0007-logging-observability.md)
> (ECS/observability), [../techdebt.md](../techdebt.md) (OPS-4).

## Операционный план реализации

Этот раздел фиксирует практический план для реализации ADR. Цель плана — вести изменения
маленькими проверяемыми состояниями, с коммитами между срезами, не смешивая платформенный каркас,
publish/fetch-поведение и финальную документацию. Если несколько срезов выполняются в одном run,
коммиты всё равно ставятся между срезами.

Перед началом реализации нужно зафиксировать состояние ADR: либо отдельным doc-коммитом, либо
явно оставить ADR вне staged area и коммитить код точечным списком файлов. Реализационные коммиты
не должны случайно подтягивать незавершённые правки дизайн-проекта.

### S0 — module skeleton and guardrails

**Цель:** завести места для платформенных примитивов и сразу поставить архитектурные предохранители.

**Изменения:**

- добавить Maven-модули `platform/platform-events` и `platform/platform-concurrency` в parent `pom.xml`;
- добавить `README.md`/`package-info.java` в новые модули;
- обновить `platform/README.md`;
- расширить `bootstrap/ioc-app/.../ArchitectureTest.java` правилами:
  - `platform-events` framework-free;
  - `platform-events` anti-broker: нет зависимостей на Spring, очереди, брокеры, сериализацию/wire
    format, routing-framework;
  - `KeyedSerialExecutor`/single-flight primitives не находятся в `platform-events`;
  - `ControlEvent` не `Serializable` до появления настоящего delivery/broker adapter;
  - Spring event types не видны из `core`.

**Проверки:**

```bash
./mvnw -pl bootstrap/ioc-app -Dtest=ArchitectureTest test
```

**Коммит:** `ARCH: add event coordination platform modules`.

### S1 — platform-events contract

**Цель:** реализовать thin event model + publish-only port без механики доставки.

**Изменения в `platform-events`:**

- `ControlEvent` — минимальный маркер/контракт события приложения;
- `ControlEventMetadata` — immutable envelope:
  - `eventId`;
  - `eventType`;
  - `eventVersion`;
  - `occurredAt`;
  - `correlationId`;
  - optional `causationId`;
- `ControlEventPublisher` — publish-only порт;
- `ControlEventObserver` — наблюдение за publish/dispatch без превращения observer в handler;
- `NoopControlEventPublisher` и `RecordingControlEventPublisher` для тестов и default wiring.

**Не делать в этом срезе:**

- `EventSubscriber` SPI;
- durable queue;
- retry/redelivery/DLQ;
- routing/matching subscriptions;
- serialization/wire format;
- любые Spring-типы.

**Тесты:**

- metadata validation;
- immutability/required fields;
- `NoopControlEventPublisher` не падает;
- `RecordingControlEventPublisher` сохраняет порядок опубликованных событий;
- observer-failure не должен ломать бизнес-поток, если контракт будет fire-and-observe.

**Проверки:**

```bash
./mvnw -pl platform/platform-events -am test
./mvnw -pl bootstrap/ioc-app -Dtest=ArchitectureTest test
```

**Коммит:** `ARCH: introduce control event publisher port`.

### S2 — platform-concurrency keyed single-flight

**Цель:** вынести конкуренцию/очередность из event-модели в отдельный общий примитив.

**Изменения в `platform-concurrency`:**

- контракт `KeyedSerialExecutor` или `SingleFlightWorkExecutor`;
- value-типы для ключа/результата admission, если они реально нужны;
- framework-free реализация на `java.util.concurrent` либо контракт + bootstrap-реализация.

Рекомендация для реализации: держать чистую `java.util.concurrent` реализацию в
`platform-concurrency`, потому что это не adapter к внешней библиотеке и её легче покрыть тестами.
`bootstrap` в таком варианте только конфигурирует lifecycle и параметры очередей. Если при
реализации окажется, что lifecycle слишком Spring-зависим, реализацию можно оставить в `bootstrap`,
но контракт всё равно не должен переезжать в `platform-events`.

**Семантика:**

- no-overlap per key;
- FIFO внутри одного key;
- разные key могут выполняться параллельно;
- bounded admission;
- high/low-water seam;
- graceful shutdown/drain;
- отсутствие durable redelivery/DLQ.

**Ключ v1:** `endpoint`. Более тонкий ключ (`endpoint + remotePath`) откладывается до транспорта,
который реально умеет мультиплексировать.

**Тесты:**

- две задачи с одним key не пересекаются;
- порядок задач внутри key сохраняется;
- разные key могут идти параллельно;
- saturated key уходит в admission/no-op path, не ломая другие key;
- shutdown не оставляет running-задачи без завершения/наблюдения.

**Проверки:**

```bash
./mvnw -pl platform/platform-concurrency -am test
./mvnw -pl bootstrap/ioc-app -Dtest=ArchitectureTest test
```

**Коммит:** `ARCH: introduce keyed single-flight executor`.

### S3 — Spring bridge in bootstrap

**Цель:** дать текущий delivery adapter за `ControlEventPublisher`, не протаскивая Spring в core.

**Изменения в `bootstrap`:**

- `SpringControlEventPublisher` поверх `ApplicationEventPublisher`;
- bean-конфигурация в composition root;
- `ControlEventObserver` bridge в ECS/diagnostics;
- тестовый wiring с `RecordingControlEventPublisher`/noop для режимов, где события не нужны;
- `@EventListener` допускается только в bootstrap-facing beans.

**Инварианты:**

- `core/ioc-application` зависит от `platform-events`, но не от Spring;
- события публикуются как факты, а не как команды с бизнес-данными;
- ошибки observer не должны ломать use-case;
- listener вызывает use-case command handler, а не содержит бизнес-логику.

**Тесты:**

- Spring bridge публикует событие;
- observer получает publish/dispatch telemetry;
- core не видит Spring event types через ArchUnit;
- hand-off не блокирует publisher дольше, чем требуется выбранной delivery-механике.

**Проверки:**

```bash
./mvnw -pl bootstrap/ioc-app -am test
```

**Коммит:** `FEATURE: bridge control events to Spring`.

**Заметки после реализации S0–S3:**

- Spring bridge обязан оставаться **fire-and-observe**: сбой `ApplicationEventPublisher`/синхронного
  listener hand-off наблюдается через `ControlEventObserver`, но не пробрасывается producer'у.
- `publishFailed`/`dispatchFailed` — операторский сигнал уровня **ERROR/WARN**, не DEBUG. Иначе после
  проглатывания dispatch-сбоя событие становится тихо потерянным до следующего reconcile.
- `KeyedSerialExecutor` уже имеет telemetry seam (`rejected`, `failed`, `dispatchRejected`), но
  **high/low-water hysteresis ещё не реализован как политика**. До подключения S5/S7 к executor нужно
  явно решить admission policy: когда ключ уходит в shed-to-reconcile, как снимается shed state ниже
  low-water, какие сигналы попадают в health/logs.
- `REJECTED`/`dispatchRejected` не должны быть тупиком: caller/listener обязан route'ить их в
  reconcile/backstop path и operator-visible state. Executor остаётся in-memory primitive, а не
  durable delivery.
- Future `@EventListener`/enqueue beans должны быть **daemon-gated** или no-op в режимах, где
  background delivery не должен стартовать. Нельзя подключать listener так, чтобы `oneshot`/manual
  `ioc export` внезапно создавал publish/fetch work.

### S4 — periodic/reconcile cycle extraction

**Цель:** убрать дублирование lifecycle-кода из fetch/publish schedulers до перевода fast-path на события.

**Текущий код:**

- `DaemonFetchScheduler` содержит fixed-delay, `AtomicBoolean` overlap guard, `closeIdle`, stop timeout;
- `DaemonPublishScheduler` содержит почти тот же lifecycle-каркас.

**Изменения:**

- выделить `PeriodicDaemonCycle` или более узкий `ReconcileCycle`;
- оставить phase/health/logging на тонких fetch/publish исполнителях;
- сохранить текущее поведение до event-driven изменений;
- не проектировать абстракцию как «универсальный scheduler всего»: после E3/E4 она должна быть
  backstop/reconcile-механизмом, а не основным fast-path.

**Тесты:**

- non-overlap preserved;
- closeIdle вызывается после цикла;
- ошибка одного source/target не останавливает остальные;
- stop корректно завершает executor.

**Проверки:**

```bash
./mvnw -pl bootstrap/ioc-app -am test
```

**Коммит:** `REFACTOR: extract periodic daemon cycle`.

### S5 — publish fast-path by `SliceCompleted`

**Цель:** перевести нормальный publish path с полного scan на адресный event-driven запуск.

**Текущий код:**

- `ExportService` вызывает `ExportObserver.completed(terminal)` и при `COMPLETED`, и при `SKIPPED`;
- `ArtifactPublishService.publish()` проходит `selectedProfiles -> listCompleted(profile) -> targets`;
- `CompletedSliceCatalog` умеет только `listCompleted(profile)`.

**Изменения:**

- добавить конкретное событие `SliceCompleted` рядом с bounded area в `core/ioc-application`;
- внедрить `ControlEventPublisher` в `ExportService`;
- публиковать `SliceCompleted` только после durable перехода `AVAILABLE -> COMPLETED`;
- не публиковать событие для `SKIPPED`;
- добавить command handler уровня application:
  - `PublishCompletedSliceCommand(profile, sliceId, correlationId/causationId...)`;
  - метод use-case для публикации одного среза;
- расширить `CompletedSliceCatalog` методом `find(profile, sliceId)`;
- реализовать `find` в `FileSystemCompletedSliceCatalog` без полного scan профиля;
- listener в bootstrap принимает `SliceCompleted`, разворачивает targets профиля и ставит работу в
  keyed executor по `endpoint`;
- periodic publish path использует **тот же** keyed executor по `endpoint`, что и listener fast-path;
  scheduler может синхронно ждать completion work-item, но не имеет права вызывать remote publish
  use-case в обход single-flight;
- listener/worker wiring включается только в daemon-capable runtime path; в `oneshot`/manual export
  событие может публиковаться как факт, но background enqueue должен быть no-op;
- обработать `WorkAdmission.REJECTED` и `dispatchRejected` как shed-to-reconcile signal:
  - не retry loop в listener;
  - operator-visible health/log state;
  - correctness через S6 reconcile по ledger.

**Инварианты:**

- событие не несёт rows/bytes/business payload;
- событие несёт только identity/reference среза;
- publish handler идемпотентен через `publish_ledger.ensurePending`;
- потерянное событие не критично: reconcile подберёт срез позже.

**Тесты:**

- `COMPLETED` export emits `SliceCompleted`;
- `SKIPPED` export does not emit `SliceCompleted`;
- listener создаёт publish work для targets профиля;
- listener не активен в runtime modes, где background publish запрещён;
- saturated/rejected key не теряет correctness и помечается как shed-to-reconcile;
- повторное событие не создаёт дублей из-за ledger;
- `CompletedSliceCatalog.find` валидирует конкретный срез и не сканирует весь профиль.

**Проверки:**

```bash
./mvnw -pl core/ioc-application,adapters/adapter-sink-csv,bootstrap/ioc-app -am test
```

**Коммит:** `FEATURE: drive publish by slice completed events`.

### S6 — DB-driven publish reconcile

**Цель:** сделать reconcile страховочной петлёй по ledger state, а не регулярным filesystem rehash.

**Текущий код:**

- `ArtifactPublishService.reconcile()` идёт через `CompletedSliceCatalog.listCompletedSliceNames`
  один раз на profile и делает anti-join по `publish_ledger`; `publish()` гонит retryable ledger rows;
- `JdbcPublishLedger.findRetryable()` уже умеет выбирать `PENDING`/`FAILED`;
- discovery новых срезов пока завязан на полный scan catalog.

**Изменения:**

- разделить fast-path publish одного среза и backstop reconcile;
- reconcile известных пар вести DB-driven по `publish_ledger`:
  - `PENDING`;
  - `FAILED`;
  - при необходимости зависшие `IN_PROGRESS` через отдельное recovery-правило;
- discovery новых срезов оставить как редкий/дешёвый проход:
  - dir-listing;
  - anti-join с `publish_ledger`;
  - verify/rehash только новых незаведённых срезов;
- не читать export-таблицы 0012: граница formation/delivery остаётся файловой;
- доработать `FileSystemCompletedSliceCatalog` так, чтобы один corrupt/partial каталог не блокировал
  весь профиль:
  - skip;
  - diagnostic;
  - опционально quarantine/marker в рамках согласованной политики.

**Тесты:**

- retryable records выбираются из DB без чтения всех срезов;
- discovery заводит missing `(slice, target)` пары;
- corrupt slice не валит discovery всего профиля;
- reconcile восстанавливает потерянное `SliceCompleted`;
- per-tick rehash отсутствует для уже известных ledger pairs.

**Проверки:**

```bash
./mvnw -pl core/ioc-application,adapters/adapter-store-jdbc,adapters/adapter-sink-csv,bootstrap/ioc-app -am test
```

**Коммит:** `FEATURE: reconcile publish from ledger state`.

### S7 — fetch detection/execution split

**Цель:** разорвать смешение monitor/detection и execution в `RemoteFetchService`.

**Текущий код:**

- `RemoteFetchService.fetch()` делает `transport.list`, include/exclude filtering и скачивание;
- `DaemonFetchScheduler` периодически вызывает fetch по source;
- correctness держится `RemoteFetchLedger`, но detection и execution не разделены.

**Изменения:**

- добавить `RemoteSourceMonitor`:
  - `transport.list`;
  - include/exclude;
  - fingerprint set по `RemoteObjectIdentity(path,size,mtime)`;
  - set-diff;
  - bounded batch;
  - событие `RemoteChangeBatchDetected`;
- добавить command handler в application:
  - `FetchRemoteObjectsCommand`;
  - список work-items/identities;
- `RemoteFetchService` должен уметь скачивать переданные identities/work-items без собственного
  `list`;
- текущую CLI/manual команду сохранить как reconcile/manual path или адаптировать через новый monitor
  без регресса поведения;
- listener в bootstrap принимает `RemoteChangeBatchDetected` и ставит work в keyed executor по
  `endpoint`;
- общий process-local in-flight registry claim-ит identity перед admission и освобождает после
  success/failure/rejection, чтобы медленный endpoint не получал дубли каждого monitor tick;
- монитор запускается через `PeriodicDaemonCycle`, не через Spring Integration;
- до включения listener'а реализовать per-key high/low-water policy поверх `KeyedSerialExecutorObserver`:
  `depth >= high-water` или `oldest-age >= max-age` переводит key в shed-to-reconcile, ниже low-water
  admission возобновляется.

**Инварианты:**

- событие не несёт содержимое файлов;
- ledger остаётся idempotent receiver/backstop;
- CHANGE_NOTIFY/SI push не вводится в v1;
- насыщенный endpoint может shed-to-reconcile без потери correctness.

**Тесты:**

- monitor emits только новые/изменённые identities;
- include/exclude сохраняет прежнюю семантику;
- bounded batch режет большую пачку;
- повторный detection не скачивает уже `FETCHED`;
- listener serializes work per endpoint;
- saturated endpoint уходит в shed-to-reconcile без busy retry и без потери ledger correctness.

**Проверки:**

```bash
./mvnw -pl core/ioc-application,bootstrap/ioc-app -am test
```

**Коммит:** `FEATURE: split remote fetch detection from execution`.

### S8 — observability, health, docs and final gate

**Цель:** закрыть эксплуатационные швы и зафиксировать новую модель в опубликованных документах.

**Изменения:**

- добавить/уточнить ECS actions:
  - event publish;
  - event dispatch;
  - keyed executor admission/shed;
  - reconcile recovery;
- рутинные пустые тики перевести с INFO на DEBUG, если они не несут операторского сигнала;
- health/actuator state:
  - queue depth per key;
  - running keys;
  - oldest age;
  - shed-to-reconcile flag;
  - last dispatch failure;
- связать `KeyedSerialExecutorObserver` с ECS/health так, чтобы `rejected`, `failed` и
  `dispatchRejected` были видны без DEBUG-логов;
- diagnostics:
  - использовать `SYNC` для sync-событий v1;
  - не заводить `EVENTS`, пока нет кросс-доменного потребителя с собственными кодами;
- обновить опубликованные docs:
  - `docs/architecture.md`;
  - `docs/ingestion.md`/sync-разделы;
  - `docs/logging.md`;
  - `docs/diagnostics.md`;
  - `docs/dev/README.md`;
  - `docs/techdebt.md` / OPS-4.

**E2E/regression checks:**

- событие `SliceCompleted` приводит к publish command handler;
- потерянное событие восстанавливается через reconcile;
- `SKIPPED` export не запускает publish;
- keyed executor не допускает overlap per endpoint между event fast-path и periodic publish path;
- corrupt slice не блокирует reconcile профиля;
- core остаётся framework-free;
- `platform-events` остаётся anti-broker.

**Финальный гейт:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Коммит:** `TEST: gate event-driven coordination end to end`.

### Рекомендуемое разбиение

- **Run 1:** S0–S3. Каркас, event-contract, single-flight и Spring bridge. Риск бизнес-регрессии
  низкий, но важно сразу закрепить ArchUnit.
- **Run 2:** S4–S6. Publish-контур: extraction cycle, `SliceCompleted`, slice-specific publish,
  DB-driven reconcile. Это самый чувствительный run, потому что затрагивает export completion,
  filesystem catalog и `publish_ledger`.
- **Run 3:** S7–S8. Fetch split, observability/health, опубликованные docs и полный `verify`.

Внутри одного run допускается выполнять несколько срезов подряд, но коммиты ставятся строго по
границам S-состояний. Если на середине run появляется архитектурное отклонение от ADR, сначала
обновляется ADR/план, затем код — не наоборот.
