> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0020-canonical-record-expiration-lifecycle.md).

<a id="0020--canonical-record-validity-and-expiration-lifecycle"></a>
# 0020 — Срок действия канонической записи и lifecycle истечения

<a id="status"></a>
## Статус

**Принято 2026-08-16. Реализация в процессе; application contracts P1 завершены,
а storage и runtime behavior ещё не активны.**

Этот ADR устанавливает возможность validity и expiration канонических записей
для релиза 0.3.0. До реализации и активации storage, pipeline и runtime slices
канонические записи сохраняют поведение из существующей storage/export документации.

> **Примечание о реализации (2026-08-23).** Предложения о статусе реализации
> выше исторические: P1-P9 реализованы в candidate 0.3.0, а packaged qualification
> и final committed-HEAD gate ещё не выполнены.
> [ADR-0021](../0021-stable-reusable-export-slots.md) заменяет раздел 4 для
> внешнего export `id`; [ADR-0022](../0022-revision-significant-identical-export.md)
> определяет доставку revision-significant slices с одинаковыми байтами;
> [ADR-0023](../0023-bounded-lifecycle-reconciliation-runtime.md) заменяет
> append-only physical cycle state и связанное retention scheduling.
> Семантика validity, active read, activation, history и clock ниже остаётся в силе.

<a id="context"></a>
## Контекст

SQLite — источник истины канонических строк артефактов. Mutable dataframe CSV
и immutable export slices — проекции. Текущая keep-first model хранит строку
бессрочно, обновляет provenance при повторных наблюдениях и выводит service-owned
public IDs из active storage state.

Эта модель не выражает свежесть. IOC, не подтверждённый в течение определённого
бизнесом периода, продолжает появляться в новых projections и export slices.
Добавить только периодический `DELETE` было бы небезопасно: expiration также
влияет на canonical read semantics, public-ID allocation, revisions,
projection recovery, ingestion duplicates, startup admission, upgrade и rollback.

Требования:

- любое принятое наблюдение может подтвердить canonical record; отсутствие
  в более позднем неполном feed не является отзывом;
- expiration точна на границе чтения и переживает простой процесса;
- наблюдение после expiration начинает новый lifecycle, не оживляя history;
- существующие public artifact schemas остаются совместимыми;
- upgrade записей без lifecycle metadata явен, восстанавливаем и управляется оператором;
- реализация остаётся в существующих clean hexagonal boundaries и
  операционном envelope SQLite.

<a id="decision"></a>
## Решение

<a id="1-validity-belongs-to-the-canonical-record-lifecycle"></a>
### 1. Validity принадлежит lifecycle канонической записи

TTL принадлежит storage-neutral lifecycle канонической artifact record,
а не IOC type, source или provenance row. Source даёт observation evidence;
application policy вычисляет deadline; storage adapter атомарно коммитит
record, provenance и lifecycle.

V1 предоставляет одну `FixedRecordValidityPolicy` за малой application strategy
`RecordValidityPolicy`. Strategy возвращает абсолютное validity decision,
не раскрывая storage или scheduling details. Per-source, per-type и
rules-engine policies отложены. Fixed TTL строго положителен.

Наблюдение подтверждает свежесть только при успешном commit canonical
transaction после parsing и failure-policy checkpoint. File detection, начало
parsing и rolled-back transaction не подтверждают record. Каждая transaction
использует один effective UTC `asOf`, полученный после захвата write ownership.

<a id="2-activity-is-an-exact-half-open-interval"></a>
### 2. Activity — точный полуоткрытый интервал

Активный lifecycle хранит внутренние UTC instants, эквивалентные:

- `first_confirmed_at`;
- `last_confirmed_at`;
- `valid_until`.

Active interval — `[first_confirmed_at, valid_until)`. Каждое canonical read,
питающее dataframe или export, применяет `valid_until > asOf`; поэтому row
неактивна при `asOf == valid_until`, даже если physical cleanup ещё не запускался.
Чтение не пересчитывает stored deadlines по текущей конфигурации.

`valid_until` — внутренняя canonical lifecycle vocabulary, согласованная со
STIX 2.1 Indicator validity boundary. Это не делает artifact row STIX Indicator
и не отображает `first_confirmed_at` в STIX `valid_from`. Source assertions
и STIX projection остаются отдельными versioned mapping contracts.

Когда принятое наблюдение находит active lifecycle, оно продлевает deadline
до `asOf + fixedTtl`. Когда та же row уже due, прежний lifecycle закрывается
и наблюдение создаёт новый. Конкуренция expiration и confirmation разрешается
порядком canonical database transactions; наблюдение никогда не теряется,
history не реактивируется.

<a id="3-active-state-and-bounded-history-have-separate-roles"></a>
### 3. Active state и ограниченная history имеют разные роли

Expired rows покидают active canonical storage. Reconciliation перед удалением
active state копирует в history typed ordered business-row snapshot,
lifecycle facts, прежние identities, close reason и compact source summary.
History не участвует в row-key matching, renewal, projection, export или ID allocation.

History retention настраивается и по умолчанию равна 30 days. V1 предоставляет
aggregate history/reconciliation statistics, а не per-IOC history search.
Retention cleanup использует bounded batches и никогда не меняет active
artifact revisions.

Каждая active artifact table содержит lifecycle fields и index по deadline
и stable row identity. JDBC adapter владеет соответствующими typed history
tables и durable lifecycle control state. Центральная polymorphic lifecycle
table и opaque JSON archive отклонены: ослабляют referential/typed-schema
гарантии и добавляют joins к каждому active read.

<a id="4-service-owned-identity-is-monotonic-across-lifecycles"></a>
### 4. Service-owned identity монотонна между lifecycles

Service-owned public ID никогда не переиспользуется, включая expiration или
history retention. Каждый artifact использует durable allocator state,
не зависящее от `MAX(id)` active rows; reserved but uncommitted ranges могут
создавать пропуски. Internal lifecycle identity использует отдельный durable namespace.

Source-supplied IDs — opaque namespaced provenance references. Они не заменяют
internal или service-owned public identity. Merged outputs сохраняют
service-owned ID; точные source IDs требуют явного source-scoped или namespaced
output contract. Повторное использование source ID для другого IOC по умолчанию
является identity conflict, а не молчаливым remap.

<a id="5-expiration-preserves-insert-driven-immutable-export-revisions"></a>
### 5. Expiration сохраняет insert-driven immutable export revisions

Observation-only renewal меняет внутренние lifecycle/provenance facts,
но не public row bytes, не artifact revision и не запускает export лишь ради
публикации нового времени. Существующая `artifact_revision` остаётся
insert-driven: продвигается только если успешный canonical commit добавил
хотя бы одну новую public active row. Expiration/removal, renewal, identical
duplicate и source без новых public rows её не продвигают и не запрашивают
новый immutable slice. Возврат после expiration создаёт новый lifecycle/public
row и поэтому считается новыми данными.

Следовательно, последний completed immutable slice может содержать уже
истёкшие rows, и опустевший active set автоматически не создаёт пустой slice.
Следующий вызванный новыми данными export читает текущий active snapshot и
исключает все накопленные expired rows. Существующие explicit operator export,
plan-drift и export-recovery behavior отделены от этого automatic daemon trigger rule.

Существующие публичные `time_first_seen` и `time_last_seen` сохраняют позиции
и остаются `NULL` в V1. Internal lifecycle timestamps не отображаются в эти
business fields. `valid_until` не добавляется в существующие dataframe/export
schemas. Будущий consumer deadline — отдельный versioned mapping или
integration contract.

Canonical expiry и convergence производных файлов не являются одной ACID
transaction. Canonical transaction записывает durable mutable-projection work.
Projection или export failure не может воскресить expired row; startup и
periodic reconciliation возвращают mutable dataframe к canonical truth.
Immutable export намеренно не регенерируется только из-за expiration.

Lifecycle projection-work/cycle state отделено от `artifact_revision`.
Оно делает mutable dataframe convergence и crash recovery устойчивыми,
не становясь ещё одним immutable-export trigger.

<a id="6-reconciliation-is-deadline-aware-bounded-and-recoverable"></a>
### 6. Reconciliation учитывает deadline, ограничена и восстанавливаема

Логическое исключение точно на deadline. Physical reconciliation выбирает
due rows индексируемыми keyset batches и bounded SQLite transactions,
затем объединяет projection work один раз на затронутые artifact/cycle.
Она не использует per-row timers, частые full-table scans или полную
материализацию набора в JVM.

Демон поддерживает один nearest-deadline wake-up и периодический correctness
backstop. В здоровом простаивающем daemon reconciliation начинается в течение
пяти секунд после deadline. V1 release evidence охватывает 100,000 одновременно
due records как validation envelope, включая bounded drain, отсутствие writer
starvation и eventual convergence. Это не жёсткий product-size limit.

Bootstrap implementation использует управляемый `SmartLifecycle` scheduler
на явно принадлежащем ей `ScheduledExecutorService`. Он не действует до
открытия общего canonical-data admission barrier, затем перепланируется
из durable nearest-deadline state после каждого aggregate hint или
reconciliation pass.

<a id="7-time-is-an-isolated-system-dependency"></a>
### 7. Время — изолированная системная зависимость

Absolute expiry использует внедрённые system UTC `Clock`; timezone и daylight
saving не влияют на stored instants. Monotonic elapsed time управляет
scheduler waits, retry delays и durations, но не заменяет wall time после restart.

Durable lifecycle time имеет неубывающий high-water mark. Малое обратное
исправление часов ограничивается clamp и сообщается как degraded.
Существенный или длительный rollback делает lifecycle state недостоверным,
сообщает down и закрывает readiness/stateful work. Точные tolerances должны
быть явной configuration или documented policy, выведенной из implementation
и operational evidence. Forward jump может немедленно сделать любое число
records due; это обрабатывает bounded reconciliation.

<a id="8-activation-is-explicit-and-one-way-for-an-existing-database"></a>
### 8. Активация для существующей DB явна и односторонняя

Classpath и upgrade-compatible default отключают record validity.
Новая production installation явно включает fixed TTL с default 12 hours.
Это значение намеренно допускает пустой active set или разрывы между feeds,
если observations приходят реже TTL.

Существующие installations используют двухшаговое внедрение:

1. Развернуть validity-capable application и проверить с отключённым validity mode.
2. Остановить stateful work, сохранить точную configuration и consistent backup
   обеих SQLite DB, затем явно включить fixed record-validity policy с
   именованной activation policy `existing-records: expire`.

Activation идемпотентно выполняется до readiness, intake и export. Она закрывает
все legacy records с audit, activation-cycle и projection recovery, поэтому
active output законно может опустеть. Insert-driven artifact revision не
продвигается. Archived inputs не replay автоматически. Fixed duration `0`
недопустима и никогда не служит destructive command.

После durable activation запуск той же DB с отключённой validity отказывает
закрыто. Изменения duration действуют на будущее: existing absolute deadlines
сохраняются до следующего accepted observation. V1 не имеет deactivation,
retroactive recalculation, bulk extension или manual expiry command.

Успешная readiness — обычная rollback boundary. До неё оператор может
восстановить согласованные pre-activation application, configuration и обе DB.
После неё основными становятся roll-forward и idempotent retry; восстановление
старого snapshot — disaster recovery с возможной потерей более поздних
confirmations. Восстановление одного компонента не поддерживается.

<a id="9-duplicate-source-confirmation-is-a-bounded-optimization"></a>
### 9. Confirmation дублирующего source — ограниченная оптимизация

Source content identity отделена от delivery/observation identity. Каждый
вновь доставленный source получает durable observation identity; recovery
той же claimed delivery переиспользует её. Canonical transaction хранит
artifact-scoped observation commit marker, поэтому сбой между canonical commit
и service-ledger checkpoint не может продлить TTL второй раз.

Identical source может подтвердить prepared canonical observations без ETL
только при наличии полного durable receipt с processing-policy fingerprint,
совпадающим с текущими parsing, normalization, mapping, identity и failure
policies. Receipt retention использует тот же configurable default 30 days,
что и lifecycle history.

После receipt retention или policy drift identical source проходит обычные
ETL и checkpoint. Это сохраняет корректность при ограниченном storage;
no-ETL handling не является бессрочной гарантией.

<a id="10-startup-admission-and-health-are-separate-responsibilities"></a>
### 10. Startup admission и health — разные ответственности

Все stateful entry points используют общую идемпотентную lifecycle admission
sequence после schema и существующего recovery: проверить policy и clock,
возобновить activation, reconcile due state, свести pending service-owned
projections, затем открыть daemon intake или исполнить stateful command.
Health только читает это состояние и не владеет admission.

Recoverable lag — degraded и может оставлять intake открытым, пока доказуем
active-read invariant. Невозможность прочитать/reconcile lifecycle control,
policy/config mismatch, unsafe clock rollback или потеря active predicate —
down и fail-closed. Health и logs показывают aggregate counts, deadlines,
backlog, cycle и history state без IOC values, row keys или source names.

Daemon export backstop продолжает наблюдать insert-driven `artifact_revision`.
Ingestion events остаются latency hints; lifecycle subsystem не публикует
export hint для expiration.

<a id="architecture-placement"></a>
## Архитектурное размещение

- `core/ioc-application` владеет lifecycle semantics, policy, use cases и inward
  ports; reusable port contracts находятся в `ioc-application-tck`.
- `adapter-store-jdbc` владеет SQLite migrations, atomic SQL, indexing, history,
  durable allocators и recovery state.
- `adapter-sink-csv` остаётся projection adapter и не владеет TTL policy/scheduling.
- `adapter-ingest` использует complete receipts или обычный ETL fallback.
- `bootstrap/ioc-app` владеет typed configuration, composition, startup admission,
  deadline scheduling и read-only health.
- `core/ioc-domain` не меняется без доказанной отдельной domain need.

V1 не требует новой runtime library или Maven module.

<a id="consequences"></a>
## Последствия

- Свежесть становится частью canonical data quality, а не downstream delivery concern.
- Каждый canonical read path обязан нести явный effective `asOf`; отсутствие
  predicate — дефект корректности.
- Public identity allocation и mutable-projection recovery становятся durable
  даже при удалении active rows, не меняя insert-driven export revision.
- Существующие installations требуют видимого оператору destructive cutover
  и coordinated rollback point.
- Production default 12 hours предпочитает строгую свежесть непрерывности
  между ежедневными feeds.
- Immutable export намеренно слабее canonical reads и mutable dataframe по
  свежести после expiry: без последующих новых public data последний
  completed slice может оставаться устаревшим бессрочно.
- SQLite остаётся подходящей для ожидаемых десятков тысяч записей, но 100k
  same-deadline evidence и query-plan checks становятся release gates.
- Будущие per-source policy, public timestamps или target-specific deadlines
  можно добавить на правильных policy/mapping boundaries без смены V1 lifecycle ownership.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

<a id="soft-delete-expired-rows-indefinitely-in-active-tables"></a>
### Бессрочный soft-delete истёкших rows в active tables

Отклонено: historical rows продолжали бы конкурировать в row-key и public-ID
semantics, увеличивать каждое active read и делать последующее observation
похожим на реактивацию, а не новый lifecycle.

<a id="use-ttl0-to-clear-legacy-data"></a>
### Использовать `ttl=0` для очистки legacy data

Отклонено: декларативная duration скрывала бы одноразовую destructive operation
и также делала бы expired каждое последующее observation. Activation использует
именованную persisted idempotent policy `existing-records: expire`.

<a id="make-sourceprovenance-own-ttl"></a>
### Передать владение TTL source/provenance

Отклонено: одну record подтверждают многие sources, а V1 не определяет source
withdrawal. Expiry принадлежит canonical lifecycle; provenance остаётся evidence.

<a id="recalculate-all-deadlines-when-configuration-changes"></a>
### Пересчитывать все deadlines при смене configuration

Отклонено: обычное редактирование config молча становилось бы bulk data migration.
Stored deadlines — факты, изменения policy действуют на будущее.

<a id="use-per-row-timers-or-a-new-schedulerdatabase"></a>
### Использовать per-row timers или новый scheduler/database

Отклонено: timers не durable и масштабируются с числом records; существующая
SQLite model даёт indexed deadline discovery, bounded batches и startup
reconciliation без новой integration family.

Spring `@Scheduled` не используется: V1 требует dynamic nearest-deadline
rescheduling и явных startup admission phases. ShedLock не используется:
поддерживаемый deployment имеет один daemon, а корректность уже сериализуют
SQLite transactions; будущий multi-process deployment требует более широкого
writer/fencing design. Spring Batch не используется: его job metadata и
restart/partition model дублировали бы bounded keyset reconciler и durable
lifecycle cycle state в текущем validation envelope.

<a id="map-ordinary-time-expiry-to-revoked-detection-or-decay-score"></a>
### Отображать обычную expiry времени в `revoked`, `detection` или decay score

Отклонено: STIX revocation постоянна для object identity, а expired IOC позднее
может создать новый lifecycle и identity. OpenCTI detection и score —
platform-specific state; у существующего public artifact `score` независимый
контракт. V1 фиксирует `LifecycleCloseReason.EXPIRED` и не вычисляет decay
score на каждом canonical read.

<a id="treat-events-or-successful-projection-as-expiration-authority"></a>
### Считать events или успешную projection authority истечения

Отклонено: events могут потеряться, filesystem/remote side effects не могут
войти в canonical SQLite transaction. Durable canonical state и reconcile —
correctness authority.

<a id="required-evidence-before-acceptance-as-implemented"></a>
## Обязательное evidence до признания реализации

- Детерминированные clock-based lifecycle/boundary tests.
- Real-SQLite confirmation-versus-expiry race и rollback tests.
- Legacy activation, fault injection, restart и projection convergence tests.
- Active-only contracts для canonical, mutable dataframe и immutable export reads.
- Automatic-export trigger tests: expiry сама не создаёт slice, а следующий
  new-data export удаляет накопившееся expired membership.
- Configuration/admission tests daemon и stateful oneshot/export.
- Packaged fresh-install, two-step upgrade и coordinated rollback evidence.
- Воспроизводимое 100k same-deadline evidence, reference-environment profile
  и измеренный drain regression threshold.
- Обновлённые capability, operator, configuration, observability и release docs.
- Final clean reactor verification на release candidate.

<a id="reference-inputs"></a>
## Исходные references

Локальное решение сравнивалось с устоявшимися lifecycle models и runtime
mechanisms. References помогают vocabulary и extension seams, но не принимаются
как wire formats или implementation dependencies:

- [Спецификация STIX 2.1](https://docs.oasis-open.org/cti/stix/v2.1/os/stix-v2.1-os.html)
  для Indicator `valid_until`, `valid_from` и permanent revocation semantics;
- [OpenCTI Indicators lifecycle](https://docs.opencti.io/latest/usage/indicators-lifecycle/),
  [decay rules](https://docs.opencti.io/latest/administration/decay-rules/),
  [retention policies](https://docs.opencti.io/latest/administration/retentions/)
  и его [expiration manager](https://github.com/OpenCTI-Platform/opencti/blob/master/opencti-platform/opencti-graphql/src/manager/expiredManager.js)
  для разделения validity, expiration management и retention;
- MISP [`DecayingModel`](https://github.com/MISP/MISP/blob/2.5/app/Model/DecayingModel.php)
  и versioned [`misp-decaying-models`](https://github.com/MISP/misp-decaying-models)
  catalog для будущей policy/strategy seam;
- [Spring scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html),
  [ShedLock](https://github.com/lukas-krecan/ShedLock) и
  [Spring Batch chunk processing](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html)
  для V1 runtime/framework disposition.

<a id="related-published-documents"></a>
## Связанные опубликованные документы

- [Архитектура](../../ARCHITECTURE.md)
- [Хранение](../../dev/storage.md)
- [Обработка](../../dev/processing.md)
- [Экспорт артефактов](../../dev/artifact-export.md)
- [Инжест](../../dev/ingestion.md)
- [Конфигурация](../../dev/configuration.md)
- [Наблюдаемость](../../dev/observability.md)
- [Процесс релиза](../../RELEASE-PROCESS.md)
