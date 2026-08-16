---
title: "DATA-TTL-01 — execution evidence"
version: "0.3.0"
status: "In progress"
document_type: "Implementation evidence"
source_of_truth: false
language: "ru"
---

# DATA-TTL-01 — execution evidence

## P0 — architecture acceptance

Architecture project, release contract и ADR-0020 приняты для реализации
2026-08-16. Тем же решением дан отдельный implementation go-ahead. Значения,
которые не меняют бизнес-семантику — clock tolerances, measured batch size и
reference performance threshold — уточняются в owning slices до их activation.

## P1 — framework-free application contracts

**Статус:** complete, capability dormant.

Реализованы:

- `RecordValidityPolicy` и единственная V1 strategy
  `FixedRecordValidityPolicy` с strictly-positive TTL;
- absolute `ValidityDecision`, `EffectiveTime`, `LifecycleDeadline` и
  half-open `RecordLifecycle`;
- отдельные durable `ObservationId` и `LifecycleId`;
- one-way `LifecycleControlState` и независимый mutable projection generation;
- identity-resolved canonical confirmation command, classified write result и
  active-only snapshot model;
- client-shaped ports `CanonicalArtifactWriter`, `ActiveArtifactReader`,
  `ExpiredArtifactStore`, `LifecycleControlStore` и
  `ArtifactProjectionWorkStore`;
- reusable `CanonicalRecordLifecycleContractTest` для one-`asOf`, active/due,
  renewal, observation replay, due→new lifecycle, bounded expiry, revision,
  projection generation и ID non-reuse;
- unit tests для fixed policy, lifecycle boundary/renewal, activation и result
  invariants.

Отдельный allocator port не создан: reservation является внутренней частью
atomic `CanonicalArtifactWriter`, а non-reuse проверяется его TCK. Physical
allocator tables и JDBC implementation принадлежат P2/P3. Receipt model,
diagnostic codes, ECS events и runtime health также не спекулируются в P1 и
остаются в своих owning slices.

P1 не изменяет существующий `CanonicalArtifactRepository`, pipeline,
configuration, SQLite schema или runtime composition. TTL не может быть включён
этим slice.

### Verification

```text
./mvnw -B -ntp -pl core/ioc-application,core/ioc-application-tck -am test
  BUILD SUCCESS
  ioc-application: 184 tests, 0 failures, 0 errors

./mvnw -B -ntp -pl core/ioc-application,core/ioc-application-tck -am verify
  BUILD SUCCESS
  application boundary enforcer: passed
  ioc-application SpotBugs: 0 visible warnings

make verify
  BUILD SUCCESS
  full reactor: 25 projects, 25 SUCCESS
  aggregate SpotBugs baseline: 65 accepted, 0 visible
```

Reusable TCK компилируется в `ioc-application-tck`; его JDBC subclass и real
SQLite execution становятся обязательным evidence P2/P3.

## P2 — durable storage foundation

**Статус:** complete, capability dormant.

В dataframe format v4 добавлены artifact-independent lifecycle structures:

- one-way `canonical_lifecycle_control` с optimistic version, clock high-water
  полями и resumable activation progress;
- global lifecycle и per-artifact public-ID allocators, независимые от очистки
  active/history rows;
- required/projected generation state, durable observation commits,
  reconciliation cycles и normalized receipt headers/artifact markers;
- retention/status indexes и database constraints для допустимых state shapes.

`DataframeSchemaReconciler` additively создаёт для каждого configured artifact
nullable lifecycle metadata, unique lifecycle identity, deadline index, typed
ordered history/source-summary mirrors и typed receipt rows без service-owned
`id`. Новые configured business columns распространяются на active, history и
receipt schema; incompatible type/index drift отклоняется до mutation.

JDBC foundation реализует:

- one-way activation CAS с SQLite write ownership и set-based invariant scan
  всех configured active tables перед публикацией `ACTIVE`;
- atomic `UPDATE ... RETURNING` reservations для global lifecycle и независимых
  public ID spaces; reservations выполняются отдельной committed transaction и
  не возвращаются после rollback вызывающей canonical transaction;
- direction/identity-epoch validation и upgrade seed по active **и** history;
- projection acknowledgement CAS по observed required generation.

Receipt marker schema намеренно нормализована: отдельная marker row существует
и для artifact с нулём prepared rows. Проверка marker count/row totals и
публикация `COMPLETE` одной transaction принадлежат P3 writer rules; P2 не
создаёт второй, преждевременный runtime path.

### Compatibility and boundaries

- upgrade fixture без lifecycle metadata сохраняет business rows и открывается
  как `DISABLED_COMPATIBLE`; lifecycle columns остаются `NULL`;
- legacy `JdbcCanonicalArtifactRepository`, canonical reads, mutable CSV,
  immutable export, pipeline, bootstrap configuration и service DB не изменены;
- новые JDBC classes не зарегистрированы Spring beans, поэтому schema v4 сама
  по себе не активирует TTL;
- canonical lifecycle TCK subclass остаётся за P3: P2 ещё не реализует
  lifecycle-aware `CanonicalArtifactWriter`/read behavior, которое этот TCK
  обязан проверять;
- новый Maven module, scheduler, event type или external library не добавлены.

### Verification

```text
./mvnw -B -ntp -pl adapters/adapter-store-jdbc -am \
  -Dtest=DataframeSchemaReconcilerTest,JdbcLifecycleStorageFoundationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
  BUILD SUCCESS
  adapter focused: 25 tests, 0 failures, 0 errors

./mvnw -B -ntp -pl adapters/adapter-store-jdbc -am test
  BUILD SUCCESS
  adapter full: 97 tests, 0 failures, 0 errors

make verify
  BUILD SUCCESS
  full reactor: 25 projects, 25 SUCCESS
  ioc-adapter-store-jdbc: 97 tests, 0 failures, 0 errors
  ioc-app: 239 tests, 0 failures, 0 errors
  aggregate SpotBugs baseline: 65 accepted, 0 visible
```

Real SQLite evidence покрывает v3→v4 upgrade, disabled compatibility,
fresh/additive schema, public-column propagation, index drift, activation
invariants и concurrent CAS, ascending/descending allocator restart,
concurrent range uniqueness, outer rollback non-reuse, projection CAS,
state/FK constraints и `EXPLAIN QUERY PLAN` для deadline/retention paths.

## P3 — lifecycle-aware canonical transaction and reads

**Статус:** complete, capability dormant in production composition.

Application command теперь несёт раздельные `observationId`, `sourceKey` и
bounded `ConfirmationReceiptContext`. Receipt identity, processing-policy
fingerprint, expected artifact count и positive retention валидируются без
framework dependencies; reusable lifecycle TCK подключён к real SQLite через
`JdbcCanonicalRecordLifecycleContractTest`.

`JdbcCanonicalLifecycleWriter` реализует одну artifact-scoped canonical
transaction:

- до mutation проверяет durable `(observationId, artifact)` marker и на replay
  возвращает прежний результат без повторного clock sample/renewal;
- резервирует worst-case public/lifecycle ID ranges отдельными committed
  transactions, поэтому rollback оставляет gaps и ID не возвращаются;
- после SQLite write ownership ровно один раз получает effective UTC `asOf`;
- для каждого `row_key` выполняет insert, active renewal либо atomic typed
  history/source-summary copy + delete + new lifecycle/public row;
- fail-closed проверяет persisted lifecycle completeness и порядок
  `firstConfirmed <= lastConfirmed < validUntil`;
- обновляет provenance и observation marker вместе с business rows;
- меняет artifact revision и required projection generation только при новом
  public membership; renewal сохраняет обе величины;
- stage-ит typed prepared rows без service-owned IDs и публикует receipt
  `COMPLETE` только после exact marker/row-count validation. Zero-row artifact
  представлен отдельным marker и считается полноценным подтверждением.

Confirmation и expiry получают общий SQLite write-serialization boundary.
Детерминированные tests с latches доказывают оба порядка гонки: winner
confirmation не теряется при последующем expiry, а winner expiry закрывает
старую lifecycle до создания новой. Реальные sleeps не используются.

Read boundaries работают следующим образом:

- `JdbcActiveArtifactReader` требует explicit `asOf` и возвращает только
  `_valid_until_epoch_ms > asOf`;
- `JdbcCanonicalArtifactRepository.load` сохраняет disabled compatibility, но
  в `ACTIVE` тем же предикатом фильтрует mutable CSV projection; legacy writer
  сериализован с activation и запрещён начиная с `ACTIVATING`;
- `JdbcSnapshotSliceReader` в одной WAL transaction использует общий clock
  sample для metadata timestamp, active coverage и всех artifact row cursors;
  `ACTIVATING` fail-closed;
- public header/order и `time_first_seen`/`time_last_seen == NULL` не меняются.

`JdbcExpiredArtifactStore` в P3 реализует минимальный indexed bounded
archive/delete contract, необходимый TCK и race tests. Он не подключён к runtime:
scheduler, startup admission/reconciliation, projection convergence, retention,
clock high-water, health/diagnostics и post-commit latency events остаются P4.
Pipeline/ingestion activation, duplicate fast path и configuration остаются P5;
production preset по-прежнему `DISABLED_COMPATIBLE`.

### Verification

```text
./mvnw -B -ntp -pl adapters/adapter-store-jdbc -am \
  -Dtest=LifecycleContractModelsTest,JdbcCanonicalLifecycleWriterTest,\
JdbcCanonicalRecordLifecycleContractTest -Dsurefire.failIfNoSpecifiedTests=false test
  BUILD SUCCESS
  focused lifecycle: 19 tests, 0 failures, 0 errors

./mvnw -B -ntp -pl adapters/adapter-store-jdbc -am test
  BUILD SUCCESS
  ioc-application: 185 tests, 0 failures, 0 errors
  ioc-adapter-store-jdbc: 112 tests, 0 failures, 0 errors

make verify
  BUILD SUCCESS
  full reactor: 25 projects, 25 SUCCESS
  ioc-application: 185 tests, 0 failures, 0 errors
  ioc-adapter-store-jdbc: 112 tests, 0 failures, 0 errors
  ioc-app: 239 tests, 0 failures, 0 errors
  aggregate SpotBugs baseline: 78 accepted, 0 visible
```

Тринадцать новых P3 SpotBugs identities для lifecycle SQL приняты только как
точечные reviewed false positives. Artifact/table/column names происходят из
immutable schema catalog, проходят `DataframeColumn.requireSqlIdentifier` и
quoting; row, receipt и time values остаются bound parameters. Восемь прежних
baseline identities обновлены после изменения сигнатур/bytecode. Итоговый
baseline proposal: `78 observed`, `0 new`, `0 stale`; широких class/package
suppression не добавлено.

## P4 — expiry, recovery, scheduling and health

**Статус:** complete, runtime собран, production validity остаётся disabled.

Application slice добавляет отдельные driving ports для admission,
reconciliation, mutable projection convergence и history retention. Durable
SQLite state остаётся единственным correctness authority:

- `LifecycleReconciliationService` фиксирует один `cycleAsOf`, восстанавливает
  незавершённые cycle records, очищает due rows bounded batches и публикует не
  более одного projection hint на затронутый artifact за цикл;
- `ArtifactProjectionConvergenceService` читает durable generation, выполняет
  полную mutable projection и подтверждает только наблюдённое generation.
  Concurrent более новое требование остаётся pending;
- `LifecycleHistoryRetentionService` независимо удаляет не более одного
  indexed batch на artifact за проход; source-summary rows удаляются FK
  cascade-ом;
- post-commit `CanonicalDeadlineScheduleChanged` и
  `MutableArtifactProjectionRequired` являются lossy latency hints. Ни expiry,
  ни renewal не меняют insert-driven `artifact_revision` и не публикуют export
  event.

JDBC slice реализует durable UTC high-water и read-only inspection. Small
rollback до `2s` clamp-ится к high-water и даёт `CLAMPED/DEGRADED`; skew больше
`2s` или clamp дольше `30s` отклоняет stateful lifecycle time и даёт
`UNSAFE/DOWN`. Canonical writer может брать effective time внутри уже открытой
write transaction. Status reader возвращает только aggregate counts,
deadlines, backlog, projection/cycle и clock state — без IOC, row key и source.

Bootstrap собирает один common admission graph после schema/ingest recovery и
до intake/export: clock/control validation → activation resume → due reconcile
→ pending mutable projections → admitted. Stateful oneshot extract/export
защищены тем же use case. Daemon export, deadline scheduler и projection worker
до admission инертны. Deadline worker владеет своим single-thread
`ScheduledExecutorService`, планирует ближайший durable deadline и сохраняет
`5s` periodic backstop; projection worker использует отдельный executor, чтобы
CSV convergence не ждала полного drain большого expiry backlog. Overlap
coalesce-ится process-local guards; correctness сохраняют SQLite generations и
следующий backstop.

Stable diagnostics: `LIFECYCLE.ADMISSION_FAILED`, `CLOCK_UNSAFE`,
`RECONCILIATION_FAILED`, `PROJECTION_FAILED` и `HISTORY_RETENTION_FAILED`.
Actuator health сообщает `UP`, recoverable `DEGRADED` либо fail-closed `DOWN`
и не выполняет mutation. Ручной mutating lifecycle CLI, `@Scheduled`, ShedLock,
Spring Batch, новый Maven module и новая runtime dependency не добавлены.

### Verification

```text
./mvnw -B -ntp \
  -pl core/ioc-application,adapters/adapter-store-jdbc,adapters/adapter-ingest,bootstrap/ioc-app \
  -am \
  -Dtest=LifecycleRuntimeServicesTest,JdbcLifecycleRuntimeTest,\
LifecycleDeadlineSchedulerTest,LifecycleHealthIndicatorTest,\
DaemonExportSchedulerTest,IngestionStartupCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
  BUILD SUCCESS
  focused P4 contracts: 39 tests, 0 failures, 0 errors

./mvnw -B -ntp \
  -pl core/ioc-application,adapters/adapter-store-jdbc,adapters/adapter-ingest,bootstrap/ioc-app \
  -am test
  BUILD SUCCESS
  affected reactor: 21 modules
  ioc-application: 191 tests, 0 failures, 0 errors
  ioc-adapter-store-jdbc: 117 tests, 0 failures, 0 errors
  ioc-adapter-ingest: 42 tests, 0 failures, 0 errors
  ioc-app: 246 tests, 0 failures, 0 errors

./mvnw -B -ntp -pl adapters/adapter-store-jdbc -am \
  -Dtest=JdbcLifecycleRuntimeTest,JdbcLifecycleStorageFoundationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
  BUILD SUCCESS
  lifecycle JDBC/runtime and query-plan contracts: 18 tests, 0 failures, 0 errors

make spotbugs-baseline-proposal
  BUILD SUCCESS
  accepted/observed: 87, new: 0, stale: 0
```

Fault evidence покрывает atomic archive/delete/generation, interrupted-cycle
recovery, clock rollback/clamp, projection CAS/failure state, lost hints,
deadline ≤`5s`, admission-before-intake/export и read-only aggregate health.
Health due-count и cleanup query plans отдельно закреплены на
`ix_*_lifecycle_due`, чтобы aggregate diagnostics не превращались в full scan.

Пять новых P4 lifecycle SQL identities приняты как точечные reviewed false
positives (`P4-SQL-TRUST`): artifact names поступают только из immutable
validated schema catalog, identifiers повторно grammar-check/quote-ятся, а
deadline и batch values bind-ятся. Четыре `THROWS_*` identities приняты как
reviewed policy noise (`P4-FAILURE-CONTRACT`): admission, reconciliation,
projection convergence и bootstrap observer обязаны сохранить точную runtime
ошибку после durable failure accounting, чтобы fail-closed/retry semantics не
ослабли. Широких class/package suppression не добавлено.

Полный 100k same-deadline profile и packaged activation evidence остаются P6 и
P5 соответственно; P4 не выдаёт synthetic unit load за measured release
benchmark.

## P5 — duplicate receipt and explicit upgrade activation

**Статус:** complete, opt-in upgrade path implemented; production presets stay
disabled until P6.

Content identity больше не является identity попытки обработки. Новая delivery
получает отдельный durable `ObservationId`, а `sourceKey` остаётся стабильным
content identity. Повторное исполнение одной observation идемпотентно, но
идентичный документ, доставленный позднее, создаёт новую observation и снова
подтверждает актуальные записи. Service DB migration v8 переносит прежние
ingestion rows в observation-keyed ledger без потери recovery state; старый
`sourceKey` больше не блокирует новую доставку того же content.

Complete confirmation receipt хранит prepared business snapshot по artifact,
source identity и processing-policy fingerprint. Для новой observation с тем же
content и текущим fingerprint `IngestionService` воспроизводит canonical
confirmation из receipt без parse/refang/classify, но проходит тот же atomic
writer и projection/run-ledger lifecycle. Missing, incomplete, expired или
policy-stale receipt автоматически возвращает обработку в обычный ETL. Receipt
и terminal observation markers имеют независимый bounded retention, default
`30d`; TTL duration намеренно не входит в processing fingerprint, поэтому её
prospective изменение не инвалидирует бизнес snapshot.

Upgrade activation реализована отдельным application service и JDBC port:

- `disabled` допустим только в `DISABLED_COMPATIBLE`; после начала activation
  обратный переход запрещён;
- `fixed` требует strictly-positive TTL, совпадение persisted policy
  fingerprint и явный `existing-records: expire` для legacy rows;
- activation CAS-ом переходит в `ACTIVATING`, keyset batches переносит legacy
  business snapshot и compact provenance в history, удаляет active rows и
  фиксирует activation-cycle/projection work без изменения insert-driven
  `artifact_revision`;
- restart продолжает сохранённый artifact/cursor, а `ACTIVE` публикуется только
  после полного удаления legacy rows и convergence всех required mutable
  projections. Пустой active dataset является корректным результатом;
- archive не проигрывается автоматически. Возврат актуальности происходит
  только через новое подтверждение и создаёт новую lifecycle/public identity.

Configuration boundary использует закрытые selectors
`ioc.lifecycle.validity.mode=disabled|fixed` и
`existing-records=reject|expire`; неизвестные значения и non-positive fixed TTL
отклоняются до runtime composition. Добавлен стабильный
`LIFECYCLE.POLICY_MISMATCH`. Один common admission применяется к daemon intake,
stateful one-shot extraction и export. File archive names теперь включают
observation identity и сохраняют распознавание legacy names, поэтому две
одинаковые доставки не конфликтуют физически.

Operator contract закрепляет двухшаговый upgrade: сначала совместимый запуск в
`disabled` и backup точной конфигурации вместе с dataframe/service SQLite, затем
явный `fixed + expire` cutover. Rollback после начала activation возможен только
восстановлением этой согласованной тройки; переключение обратно в `disabled` не
является rollback.

### Verification

```text
Focused application/JDBC/ingest/bootstrap tests
  receipt replay and ETL fallback
  observation migration/recovery and identical-content redelivery
  interrupt/resume activation, history/provenance, empty-active result
  projection-before-ACTIVE and one-way policy enforcement
  strict lifecycle configuration and processing fingerprint
  daemon archive identity compatibility
  passed

make spotbugs-baseline-proposal
  BUILD SUCCESS
  accepted/observed: 93, new: 0, stale: 0

make verify
  BUILD SUCCESS (02:10 wall clock)
  full reactor: 25 projects, 25 SUCCESS
  ioc-application: 197 tests, 0 failures, 0 errors
  ioc-adapter-store-jdbc: 122 tests, 0 failures, 0 errors
  ioc-adapter-ingest: 46 tests, 0 failures, 0 errors
  ioc-app: 250 tests, 0 failures, 0 errors
  aggregate SpotBugs baseline: 93 accepted, 0 visible

git diff --check
  passed

make docs
  608 links, 0 errors
```

SpotBugs первоначально выявил два действительных `DLS_DEAD_LOCAL_STORE`; оба
исправлены удалением перезаписываемого lifecycle read и корректной
definite-assignment локальной переменной. Пять новых SQL identities приняты
только как точечные `P5-SQL-TRUST` false positives: имена таблиц/колонок идут из
immutable validated schema catalog и quote/revalidate-ятся, а runtime values
bind-ятся. Один `THROWS_*` identity принят как `P5-FAILURE-CONTRACT`: stateful
one-shot обязан сохранить первичную runtime ошибку, пометив observation terminal
и добавив cleanup failure как suppressed. Широких suppressions нет.

Fresh-install `fixed/12h`, packaging smoke, 100k same-deadline performance
profile и финальная release документация остаются P6.

## P6 — release closure

**Статус:** in progress; rootless gates complete, privileged packaged stand
pending.

Fresh production template теперь включает `fixed/12h` с безопасным для чистой
БД `existing-records: reject`. Classpath и upgrade-compatible defaults остаются
`disabled`, history и complete-receipt retention — `30d`. Packaging contracts
закрепляют это различие и сохранение изменившегося operator config как
`application.yml.new`; silent activation при upgrade отсутствует.

Опубликованы English capability doc и operator guide с русской локализацией,
обновлены architecture/module maps и affected module README. Operator contract
явно описывает двухшаговую activation, destructive legacy expiry, допустимый
empty active set, UTC clock prerequisite, aggregate health и rollback только
согласованной тройкой configuration + обе SQLite DB. Отдельный curated
[release-note input](release-note-input.md) не выдаётся за итоговые notes всего
релиза и не содержит выдуманных artifact/tag/checksum значений.

Rootless harness использует normal daemon ingestion, bootable fat JAR и
production heap flags. Он проверяет active → typed history → retention purge,
header-only mutable projections, unchanged insert-driven revisions, aggregate
health, absence of an expiry-triggered immutable slice, exact active membership
in the next new-row slice и public-ID non-reuse после нового подтверждения.
`make lifecycle-load`
на clean commit `8f99eb69f30e54e72aaa3bce75cac78fceebd961` провёл `100001`
canonical rows и дал:

- expiry start latency `885ms` при contract limit `5s`;
- deadline spread `5365ms`, drain after latest deadline `5628ms`;
- measured archive/drain throughput `9893.25 rows/s` при regression floor
  `2500 rows/s`;
- history retention drain `40597ms` при guardrail `180s`;
- JVM high-water `582088 KiB` при guardrail `1048576 KiB`;
- `103` minimum bounded expiry transactions и covering-index plans для всех
  expiry/retention paths.

Полный environment, rationale, calibration disposition и query-plan evidence
сохранены в [P6 load profile](evidence/p6-load-profile.md). Raw report остаётся
ignored runtime artifact под `.dev/`.

### Verification выполнено

```text
make lifecycle-smoke
  passed: 1501 canonical rows, expiry/history/retention/ID assertions

make lifecycle-load
  passed: 100001 canonical rows under packaged JVM profile

tools/ci/packaging.sh
  ShellCheck + packaging contracts + tools contracts: passed

make docs
  629 links, 0 errors
```

### Remaining release gate

Repository contract tests и rootless daemon runtime не могут проверить real
systemd ownership, privileged immutable activation, service stop/start и
automatic rollback. Поэтому P6 и `R030-DATA` остаются `in-progress` до
fresh-install → compatibility upgrade → explicit activation → rollback
сценария на disposable systemd host, а также final fresh `make verify` на
release-candidate HEAD. Недоступный privileged stand записан как pending, не
как pass.
