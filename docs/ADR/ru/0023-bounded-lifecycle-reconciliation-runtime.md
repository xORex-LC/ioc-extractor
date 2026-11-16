> Русский перевод исторической записи. Решение, статус и даты сохранены. [Английский оригинал](../0023-bounded-lifecycle-reconciliation-runtime.md).

<a id="0023--bounded-lifecycle-reconciliation-runtime-state"></a>
# 0023 — Ограниченное runtime state lifecycle reconciliation

<a id="status"></a>
## Статус

**Принято 2026-08-23. Реализовано в candidate 0.3.0; final release qualification
ещё не завершена.**

Этот ADR узко заменяет ADR-0020 там, где каждый периодический expiry check
моделируется append-only row `lifecycle_reconcile_cycle`, а history retention
связана с deadline worker. ADR-0020 остаётся авторитетным для record validity,
expiration semantics, recovery, active reads, projections, history content
и всего остального lifecycle behavior.

<a id="context"></a>
## Контекст

Демон использует теряемые in-process events для снижения latency и
пятисекундный periodic backstop для повторного обнаружения durable lifecycle
work. Первая реализация выполняла полный reconciliation cycle на каждом
backstop tick. Даже без due records она:

- вставляла и завершала durable cycle row;
- выдавала успешный reconciliation INFO event;
- запускала независимую history retention;
- оставляла неограниченный operational journal.

Projection backstop независимо выдавал ещё один INFO event за пустой проход.
При пятисекундном интервале простаивающий экземпляр накапливал примерно
17,280 cycle rows и 34,560 неинформативных INFO events в сутки. Увеличение
интервала скрывало бы симптом ценой ослабления принятого expiry-latency contract.

<a id="decision"></a>
## Решение

<a id="1-keep-events-as-hints-and-make-the-backstop-deadline-aware"></a>
### 1. Сохранить events как hints и учитывать deadline в backstop

`CanonicalDeadlineScheduleChanged` остаётся latency hint. Durable query
`MIN(_valid_until_epoch_ms)` остаётся scheduling authority.

Event nudge и пятисекундный backstop обновляют один aggregate nearest-deadline
timer. Они не начинают reconciliation, пока deadline не наступил. Due timer
запускает существующий bounded idempotent reconciliation use case. Поэтому
restart и lost event всё ещё восстанавливаются в пределах настроенного
backstop interval без timer или job на IOC.

<a id="2-store-only-the-latest-reconciliation-checkpoint"></a>
### 2. Хранить только последний reconciliation checkpoint

Dataframe format v6 добавляет singleton row `lifecycle_reconcile_state`.
Она хранит monotonic `cycle_sequence`, текущее/последнее terminal state,
timestamps, aggregate counters и failure code. Начало реального due cycle
увеличивает sequence; batch accounting и terminal publication обновляют ту же row.

V6 migration заполняет singleton из последней legacy row
`lifecycle_reconcile_cycle` либо `NEVER_RUN`, когда cycle ещё нет. Legacy table
сохраняется read-only и не получает новых rows. Это сохраняет upgrade evidence
и rollback inspection, ограничивая всё post-upgrade state. Health читает
singleton и остаётся read-only.

<a id="3-separate-history-retention-from-deadline-scheduling"></a>
### 3. Отделить history retention от deadline scheduling

History retention получает собственный admission-gated `SmartLifecycle` worker
и cadence `ioc.lifecycle.history-cleanup-interval` (default один час).
Каждая transaction остаётся ограниченной существующим reconciliation batch
size. Когда проход сообщает о дополнительных eligible rows, worker немедленно
планирует следующую task на своём single-thread executor, пока backlog не исчерпан.

Следовательно, expiration и history deletion имеют независимые timing и
failure domains. Business retention duration остаётся
`ioc.lifecycle.history-retention` (default 30 days).

<a id="4-log-outcomes-not-idle-heartbeats"></a>
### 4. Логировать результаты, а не idle heartbeats

Успешная reconciliation логируется на INFO только при expired records.
Projection pass логируется на INFO только при projected artifact или
сохраняющейся pending work. Пустые успешные checks молчат; каждая failure
остаётся diagnostic плюс ERROR. History cleanup уже логирует только non-zero purge.

<a id="5-preserve-existing-boundaries"></a>
### 5. Сохранить существующие границы

Не вводятся broker, outbox, Spring Batch job, per-record scheduler, новый
Maven module или standalone Java library. Framework-free use cases и ports
остаются в `ioc-application`; SQLite details — в `adapter-store-jdbc`;
scheduling и Spring lifecycle ownership — в `ioc-app`.

<a id="consequences"></a>
## Последствия

Положительные последствия:

- Пятисекундная correctness boundary сохраняется без idle SQLite writes.
- Reconciliation runtime state после v6 имеет постоянную cardinality.
- Idle lifecycle logs больше не заглушают существенные transitions и failures.
- Retention cadence меняется без изменения expiry latency.
- Event loss и restart остаются восстанавливаемыми из durable data.

Стоимость и ограничения:

- Legacy journal заморожен, а не удалён; прежние rows продолжают занимать
  pre-upgrade space.
- Сохраняется только последний post-v6 cycle checkpoint; долгосрочные
  operational trends принадлежат external logs/metrics, а не canonical SQLite.
- Periodic projection backstop всё ещё выполняет малые durable reads каждые
  пять секунд: это correctness path при потере projection hint.

<a id="rejected-alternatives"></a>
## Отклонённые альтернативы

- **Увеличить backstop interval:** уменьшает writes, но ослабляет expiry latency.
- **Сохранить append-only cycles и периодически удалять:** создаёт лишние writes,
  cleanup work и вторую retention policy для heartbeat data.
- **Один scheduler для expiry и history retention:** связывает независимые
  cadence и failure domains.
- **Reconciliation только из events:** process crash может потерять hint.
- **Scheduled job на record:** scheduler state становится пропорционален
  cardinality IOC и дублирует persisted deadline authority.
- **Принять Spring Batch или ShedLock сейчас:** текущая single-node bounded
  SQLite workload не оправдывает их runtime/schema complexity.

<a id="verification"></a>
## Проверка

- Повторные empty backstop refreshes не вызывают reconciliation и не продвигают
  durable cycle sequence.
- Due deadline вызывает ровно одну неперекрывающуюся reconciliation и затем
  обновляет следующий durable deadline.
- V5-to-v6 migration сохраняет последний legacy cycle и замораживает old table.
- Прерванное `STARTED` state отмечается `FAILED`, следующий реальный cycle
  завершается с большей sequence.
- Retention scheduling admission-gated, независимо настроено и исчерпывает
  `moreEligible` work ограниченными follow-up tasks.
- No-op reconciliation/projection outcomes не выдают INFO, а существенные и
  неуспешные outcomes сохраняют structured events.
- Config binding, strict validation, health, focused module tests, docs и
  полный repository gate проходят на итоговом commit.
