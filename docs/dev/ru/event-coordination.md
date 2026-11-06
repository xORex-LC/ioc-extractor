# Событийная координация

[English version](../event-coordination.md)

Control events уменьшают задержку между возможностями поверх durable ledgers и
periodic reconciliation. Они не превращают core во внутрипроцессный брокер.
Документ описывает service composition; точные контракты reusable event/executor
API остаются в module references и Javadoc.

## Когда подходит событие

Событие уместно при одновременном выполнении условий:

1. Durable факт одной возможности должен ускорить другую.
2. Consumer может перечитать authoritative state.
3. Потеря и повтор hints безопасны благодаря non-event backstop.
4. Publisher не требует синхронного бизнес-ответа.

Если потеря сообщения теряет невосстановимую работу, нужен durable ledger,
outbox или delivery protocol. Текущие control events такой гарантии не дают.

## Архитектурная граница

`platform-events` владеет framework-free event values/metadata, publish-only
`ControlEventPublisher` и наблюдением доставки. В нём нет Spring events, очереди,
subscriber registry, routing framework, wire serialization, retries, ordering,
durable delivery, outbox, DLQ и keyed execution.

[Справочник модуля](../../../platform/platform-events/README.md) владеет JDK-контрактом
и ограничениями recording publisher; [руководство consumer](../../guides/ru/libraries/events.md)
показывает отдельное событие. Metadata сохраняет значения без нормализации.
Успешный возврат publisher не подтверждает каждого handler. Ошибка возможна после
частичной доставки: `published` означает нормальный возврат, `publishFailed` —
ошибку, а не отсутствие уже завершившихся handlers.

[Spring bridge](../../../adapters/adapter-events-spring/README.md) делегирует Spring
application events. Bootstrap `EventCoordinationConfig` явно создаёт его с IOC
observer. Он не заменяет multicaster, error handler и executor. Dispatch наследует
переданную конфигурацию Spring; bridge не обещает async/durable delivery.

Исходный `Error` выходит наружу, включая непосредственно обёрнутый Spring в
`UndeclaredThrowableException`. При сбое observer ошибка publication остаётся
primary, отдельный observer failure добавляется suppressed, если throwable это
разрешает. Runtime failure последнего WARN не меняет delivery outcome producer;
observation/logging `Error` выходит. См.
[ADR 0047](../../ADR/0047-explicit-spring-control-event-adapter.md).
Изолированные JDK/Spring consumers проверяют границы packaged JAR без IOC bootstrap,
с отдельным Maven cache и проверкой обоих собственных артефактов; детали — в
[руководстве публикации](../../guides/library-publication.md#events-packaging-preparation).

Listeners проверяют/наблюдают hint и допускают работу в consumer-owned executor
или scheduler. Тяжёлый use case не выполняется в publisher thread. Обработка
post-commit RuntimeException принадлежит producer: несколько producers сохраняют
committed result и полагаются на backstop. Это не делает любую ошибку безвредной:
проброшенный `Error` может следовать за durable commit.

## Модель корректности

```text
durable state transition
  |-- publish event hint --> listener --> consumer-owned admission --> work
  \-- periodic reconcile -------------------------------------------> work

work -> durable idempotency ledger / authoritative state
```

- Metadata даёт correlation/causation и contract version, не wire protocol.
- Идемпотентность обеспечивает consumer ledger/identity, не event id.
- Fast path и backstop consumer сходятся в один admission path.
- Remote sync использует endpoint-keyed execution, ingest-to-export —
  `DaemonExportScheduler.nudge()` со своей cadence/coalescing policy.
- Bounded admission может отбросить hint; reconcile заново находит eligible work.
- Local WatchService и SMB `CHANGE_NOTIFY` managed import передают source-scoped
  detection hints. Сигнал не является delivery identity, order или ownership evidence.

Backstop зависит от durable state и сохранённых входов. Он не восстанавливает
необнаруженный remote file, удалённый до следующего scan, и не исправляет неверный
ключ идемпотентности. Текущие fetch namespace/collision defects — `OPS-11`/`OPS-12`
в [известных проблемах](../../KNOWN-ISSUES.md); event architecture их не устраняет.

## Контракт keyed execution

Точный API принадлежит [platform-concurrency](../../../platform/platform-concurrency/README.md).
Async execution сериализует принятые задачи одного ключа в порядке admission,
не объединяя повторные hints. Queue bound действует на ключ; глобального
ограничения числа ключей нет. Bootstrap передаёт выделенный pool с явным отказом
и владение shutdown executor. `close()` начинает drain без ожидания;
ограниченный join выполняет lifecycle owner.

Health `running` означает занятую lane, включая ожидание worker thread;
`oldestAge` включает прежнее ожидание текущей задачи в очереди. Synchronous guard
для ingestion/file ledger работает в caller thread без FIFO; вложенные вызовы
одного ключа считаются одним execution. Snapshots наблюдают, не владея admission
или durable recovery.

Ключи принадлежат экземпляру executor. Две JVM не разделяют exclusion, а разные
endpoint keys не защищают общее inbox filename. Ручную CLI operation нужно
согласовать с daemon, а не считать захватывающей его внутрипроцессную lane.

## Реализованные потоки

### Обнаружение remote и fetch

Periodic/startup/SMB doorbells входят в один single-flight detection path на source.
Ограниченные batches публикуются как `RemoteChangeBatchDetected`; listener допускает
fetch по endpoint. `remote_fetch_ledger` и следующий listing восстанавливают
потерянные/повторные hints для подходящих сохранённых объектов. In-flight claims
принадлежат процессу и освобождаются при завершении выполнения или отказе admission.

### Завершение export и publish

Обычное завершение export и recovery публикуют `SliceCompleted` после durable
completion. Listener ускоряет публикацию этого slice;
catalog/`publish_ledger` reconcile остаётся backstop. Завершённый локальный slice
сам по себе не доказывает удалённую доставку.

### Canonical change и export

Завершённый document ingest и committed managed import сообщают public mutations
через `CanonicalArtifactsChanged(operationId, affectedArtifacts)`. Import даёт
факт только после общей dataframe transaction. Listener вызывает
`DaemonExportScheduler.nudge()`; scheduler читает revisions и применяет
quiet/max-cap cadence. Public-field mutation может увеличить revision без нового
canonical lifecycle. Periodic export poll закрывает restart, duplicate/no-op и
потерянный hint; одно событие не заставляет создавать slice.

### Lifecycle, deadline и projection

После lifecycle-aware confirmation `CanonicalDeadlineScheduleChanged` ускоряет
aggregate deadline schedule, `MutableArtifactProjectionRequired` — mutable CSV
convergence. Expiry даёт не более одного projection hint на затронутый artifact.
Оба lossy: scheduler читает indexed deadline/projection generations,
а `5s` backstop закрывает потерю hints и restart.

Expiry и TTL-only renewal не увеличивают public-data revision и не заставляют
immutable export. Insertion, reappearance и поддерживаемые public-field mutations
могут увеличивать её. Deadline/projection hint не обходит export plan/revision
gates. См. [artifact export](artifact-export.md).

### Обнаружение managed import

Local filesystem и SMB signals ускоряют source detector; listing, capability gates
и delivery ledger владеют recognition и ordered admission. Сигнал не содержит
принятые CSV rows и не даёт transport ownership. Import receipt recovery отделён
от document/fetch idempotency.

## Инварианты

1. Публиковать control fact после authoritative transition, не до commit.
2. Передавать identity/claim check, не большое mutable business state.
3. Сохранять event type/version и единообразно создавать metadata.
4. Publisher не зависит от конкретного consumer.
5. Повторы/concurrent triggers обрабатывает собственная authority consumer.
6. Каждый event path имеет проверяемый non-event backstop и retention assumptions.
7. Наблюдать emission/dispatch failure, не объявляя rollback committed state.

ArchUnit защищает framework-free/non-serializable events и отдельную keyed
concurrency. Эти правила не доказывают квалификацию всех operational flows и
внешних targets.

## Добавление потока

1. Определить durable fact и его владельца.
2. Установить idempotency identity, retained state и reconcile backstop.
3. Объявить application-owned `ControlEvent` со стабильными type/version.
4. Публиковать через `ControlEventPublisher` после authoritative transition.
5. Передать работу из bootstrap listener в bounded consumer-owned admission.
6. Раздельно наблюдать publish, dispatch, shed и work outcomes.
7. Проверить lost/duplicate hints, restart, concurrent triggers и failure после
   commit. Подтвердить ограниченное завершение и очистку workers.

Подходящий существующему publish contract поток не требует изменения библиотеки.

## Когда нужен broker или outbox

Межпроцессная доставка или требование at-least-once для невосстановимого через
reconcile события требуют отдельного дизайна. Durable outbox, broker, redelivery
и DLQ принадлежат adapters за явным delivery protocol. У текущих событий нет
wire schema; она не должна появиться неявно.

## Живые источники

- [Композиция publisher](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/EventCoordinationConfig.java)
  и [Spring publisher](../../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/SpringControlEventPublisher.java).
- [Detection coordinator](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/RemoteFetchDetectionCoordinator.java),
  [fetch listener](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/RemoteChangeFetchListener.java)
  и [publish listener](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SliceCompletedPublishListener.java).
- [Import post-commit decorator](../../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/EventPublishingCanonicalImportWriter.java)
  и [canonical confirmation decorator](../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/lifecycle/EventPublishingCanonicalArtifactWriter.java).
- Boundary tests и [BOUNDARIES.md](../../BOUNDARIES.md) проверяют dependency constraints.

## Когда обновлять

Меняйте документ и перевод при изменении delivery guarantees, event ownership,
metadata, admission/backstop doctrine и внешней delivery infrastructure. Новый
record с прежними правилами можно описать в package/module reference.

## Связанные документы

- [Sync](sync.md) — fetch/publish consumers.
- [Ingestion](ingestion.md) — canonical-change producer.
- [Observability](../observability.md) — event observation.
- [ADR-0013](../../ADR/0013-event-driven-coordination.md) и
  [ADR-0014](../../ADR/0014-event-driven-ingest-to-delivery.md).
