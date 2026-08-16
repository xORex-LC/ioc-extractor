# Event-driven координация

Событийная координация в проекте — это low-latency control-plane поверх
durable ledgers и periodic reconcile. Она связывает capability, не превращая
core в in-process message broker.

## Когда использовать событие

Событие уместно, только если одновременно выполняются условия:

1. durable факт в одном capability должен ускорить работу другого;
2. получатель может повторно прочитать authoritative state;
3. потерянный или повторный event не нарушает корректность, потому что есть
   reconcile/poll backstop;
4. publisher не нуждается в синхронном ответе consumer-а.

Если потеря сообщения означает потерю данных/работы, нужен durable ledger,
outbox или иной delivery protocol — не текущий in-process event.

## Архитектурная граница

`platform-events` содержит только framework-free event model, metadata,
publish-only `ControlEventPublisher` и observer доставки. В нём нет:

- Spring event types;
- очереди, subscriber registry или routing framework;
- serialization/wire format;
- retries, ordering, durable delivery, outbox или DLQ;
- keyed execution.

Текущий bootstrap adapter мостит publisher в Spring application events.
Listener должен лишь валидировать/наблюдать hint и передать работу в
consumer-owned executor или scheduler. Тяжёлый use case не выполняется в потоке
publisher-а.

## Модель корректности

```text
durable state transition
  |-- publish event hint --> listener --> consumer-owned admission --> work
  \-- periodic reconcile -------------------------------------------> work

work -> durable idempotency ledger / authoritative state
```

- Event metadata обеспечивает correlation/causation и версию контракта, но не
  является wire protocol.
- Идемпотентность реализуется ledger/identity consumer-а, а не event id.
- Fast-path и backstop одного consumer-а должны сходиться в один admission path.
- Для remote sync таким path является endpoint-keyed serial executor.
- Для ingest→export это `DaemonExportScheduler.nudge()`, который coalesce-ит
  hints и применяет собственную cadence policy.
- Bounded admission может shed-нуть hint; reconcile обязан восстановить работу.

## Реализованные потоки

### Remote detection → fetch

Periodic/startup/SMB doorbell запускают один detection path. Обнаруженная
bounded batch публикуется как `RemoteChangeBatchDetected`; listener ставит
fetch по endpoint. Durable `remote_fetch_ledger` и следующий detection
переживают потерю/повтор события.

### Export completion → publish

Обычное завершение export и recovery публикуют `SliceCompleted` только после
того, как slice стал durable completed. Listener ускоряет публикацию конкретного
среза; catalog×`publish_ledger` reconcile остаётся backstop.

### Ingestion completion → export

После завершения canonical run публикуется `CanonicalArtifactsChanged`.
Listener вызывает только `DaemonExportScheduler.nudge()`; scheduler сам читает
актуальные revisions и решает, нужен ли export. Periodic export poll закрывает
restart, duplicate-only ingest и потерянный hint.

### Canonical lifecycle → deadline/projection convergence

После lifecycle-aware canonical commit `CanonicalDeadlineScheduleChanged`
ускоряет пересчёт ближайшего aggregate deadline, а
`MutableArtifactProjectionRequired` ускоряет полную mutable CSV projection.
Expiry cycle публикует projection hint не более одного раза на затронутый
artifact. Оба event-а lossy: scheduler всегда перечитывает durable deadline или
projection generation, а `5s` periodic backstop закрывает потерю, duplicate и
restart.

Lifecycle не публикует immutable-export hint. Expiry/renewal не двигают
insert-driven `artifact_revision`; следовательно, событие не может обойти
принятое правило «экспорт только после новых business rows».

## Инварианты

1. Событие публикуется после commit факта, не до него.
2. Payload содержит identity/claim check, а не тяжёлое mutable состояние.
3. Event type и version стабильны; metadata создаётся единообразно.
4. Publisher не зависит от конкретного consumer-а.
5. Consumer idempotent относительно повторов и конкурентных triggers.
6. Любой event path имеет проверяемый non-event backstop.
7. Failure emit/dispatch наблюдаем, но не откатывает уже committed producer
   state.

Эти рамки дополнительно закреплены ArchUnit: platform events остаётся
framework-free/non-serializable, а keyed concurrency живёт отдельно.

## Как добавить новый flow

1. Зафиксировать durable факт и владельца события.
2. Доказать наличие idempotency key и reconcile/backstop.
3. Объявить application-owned `ControlEvent` со стабильными type/version.
4. Публиковать после authoritative transition через `ControlEventPublisher`.
5. В bootstrap listener передать hint в consumer-owned bounded admission.
6. Добавить observer/health evidence для publish, dispatch, shed и work outcome.
7. Протестировать lost event, duplicate event, restart и concurrent trigger.

Новый flow не требует изменения `platform-events`, если базового publish
contract достаточно.

## Когда понадобится брокер/outbox

Триггером является межпроцессная доставка или требование at-least-once самого
события, которое больше нельзя восстановить reconcile-ом. Тогда durable outbox,
broker, redelivery и DLQ реализуются внешним adapter за существующей границей.
Это отдельное архитектурное решение: текущие события не имеют wire schema и не
должны неявно получить её.

## Источники истины

- Event contracts: `platform/platform-events/`.
- Admission primitives: `platform/platform-concurrency/`.
- Application events: packages `application.sync`, `application.export`,
  `application.ingest` и `application.artifact.lifecycle`.
- Spring bridge/listeners/schedulers: `bootstrap/ioc-app`.
- Boundary tests: `ArchitectureTest` и [BOUNDARIES.md](../BOUNDARIES.md).

## Когда обновлять документ

Обновите его при изменении delivery guarantee, event ownership, metadata
contract, admission/backstop doctrine или появлении внешней delivery
инфраструктуры. Новый event record, следующий тем же правилам, достаточно
описать в package/module README.

## Связанные документы

- [sync.md](sync.md) — fetch/publish consumers.
- [artifact-export.md](artifact-export.md) — completion/recovery producer.
- [ingestion.md](ingestion.md) — canonical-change producer.
- [observability.md](observability.md) — event observation.
- [ADR-0013](../ADR/0013-event-driven-coordination.md) и
  [ADR-0014](../ADR/0014-event-driven-ingest-to-delivery.md).
