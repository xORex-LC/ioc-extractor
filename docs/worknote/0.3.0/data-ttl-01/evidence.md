---
title: "DATA-TTL-01 — execution evidence"
version: "0.3.0"
status: "In progress — P7 packaged qualification pending"
document_type: "Implementation evidence"
source_of_truth: false
language: "ru"
---

# DATA-TTL-01 — execution evidence

> **Evidence correction (2026-08-19).** P0–P6 remain valid evidence for the
> implemented TTL lifecycle. Their monotonic public-ID assertions characterize
> the current implementation but do not satisfy the subsequently clarified
> reusable export-slot contract in I-22/ADR-0021. DATA-TTL-01 is reopened until
> P7 implementation and replacement compatibility/performance evidence exist.

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
  projection generation и internal lifecycle ID non-reuse;
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
  только через новое подтверждение и создаёт новую internal lifecycle identity;
  внешнее slot-поведение переоткрыто в P7.

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

**Статус:** complete; rootless и privileged packaged gates выполнены.

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
in the next new-row slice и monotonic public-ID behavior после нового
подтверждения. Последнее является characterization прежней модели, а не pass
для исправленного reusable export-slot requirement.
`make lifecycle-load`
на clean commit `b5bdd1a10802b9f5b7158d2e39ed9d34c2d98537` провёл `100001`
canonical rows и дал:

- expiry start latency `1001ms` при contract limit `5s`;
- deadline spread `5306ms`, drain after latest deadline `4799ms`;
- measured archive/drain throughput `10984.29 rows/s` при regression floor
  `2500 rows/s`;
- history retention drain `40602ms` при guardrail `180s`;
- JVM high-water `571144 KiB` при guardrail `1048576 KiB`;
- `103` minimum bounded expiry transactions и covering-index plans для всех
  expiry/retention paths.

Полный environment, rationale, calibration disposition и query-plan evidence
сохранены в [P6 load profile](evidence/p6-load-profile.md). Raw report остаётся
ignored runtime artifact под `.dev/`.

### Privileged packaged systemd stand (2026-08-18/19)

Проверка выполнена на disposable systemd stand под Ubuntu 24.04.3 LTS с JDK 21.
Это best-effort дистрибутив согласно deployment contract: installer выдал
ожидаемое предупреждение, что официальные platform baselines — Debian 11/12.
Stand закрывает DATA-TTL-01 packaged lifecycle gate; отдельная Debian-specific
квалификация всего release остаётся ответственностью `R030-REL`.

Проверялся bootable JAR с commit `e089ae6a3fe8592eb896878398b04088021f238f`
и SHA-256
`5e89ccd90f9146d36d4327c428119e097a68478702864e424ce85a21b15621d2`.
Все операции проходили через production layout `/srv/ioc-extractor`, реальный
systemd unit и dedicated account `ioc` с `/usr/sbin/nologin`; immutable JAR
остался `root:ioc:0644`, operator config — `root:ioc:0640`, обе DB —
`ioc:ioc:0640`.

| Checkpoint | Наблюдаемый результат |
|---|---|
| Official v0.2.0 baseline | Dataframe/service schema `3/7`, `246` active rows, revision `1` для всех artifacts; четыре mutable projections сохранены как byte-exact baseline |
| Compatibility upgrade | Schema `4/8`, lifecycle `DISABLED_COMPATIBLE`; `246` rows, max public IDs, revisions, operator config SHA-256 `1a4b8941a8167440f7fef44d2de0481a55719fa2f50ffefb4c9be4740c6be81e` и все projection hashes не изменились; новый template появился отдельно |
| Explicit activation | Stand-only `fixed/2m`, `existing-records: expire`, history `4m`, receipts `5m`: active `246 → 0`, typed history `0 → 246`, все причины `LEGACY_ACTIVATION`, projections стали header-only, control state стал `ACTIVE` |
| Fresh confirmation and expiry | Normal daemon ingestion создал `5` active rows с lifecycle IDs `247..251`; все deadline deltas ровно `120000ms`; expiry закрыл строки с `EXPIRED` за `0..3ms` после границы и не изменил insert-driven artifact revisions |
| Reappearance | Тот же content под новой source identity прошёл receipt fast path как новое наблюдение: lifecycle IDs `252..256`, текущая реализация выдала monotonic public IDs `masks 45→46`, `ip_list 22→23`, `hashes 117→118`; address rows также получили новые internal IDs `68/69` вместо `66/67`. Это characterization, которую P7 должен заменить slot-reuse assertions |
| Independent retention | Первый и второй receipt были удалены по собственным `5m` deadlines, legacy и обычная history — по собственным `4m` deadlines; итоговые active/history/receipt counts стали `0/0/0` |
| Export rule I-20 | Expiry не создал immutable slice. Обычный delayed new-row export сработал только из-за insert-driven revision и, поскольку к snapshot rows уже истекли, создал header-only current-membership slices |
| Activation rollback | Consistent pre-activation snapshot восстановил schema `4/8`, lifecycle `DISABLED_COMPATIBLE`, `246` active/`0` history и исходные projection hashes |
| Release rollback | Matching v0.2 binary/config/two-DB backup восстановил schema `3/7`, `246` active rows и byte-exact v0.2 projections; service снова прошёл health gate |
| Fresh installation | После purge installer создал чистый layout со schema `4/8`, `ACTIVE`, production `fixed/12h` + `existing-records: reject`; initial active set был пуст, health — `UP` |

Activation rollback point сохранён под
`/var/tmp/ioc-extractor-p6-pre-activation-20260819T141023Z`, состояние после
activation — под
`/var/tmp/ioc-extractor-p6-activated-state-20260819T142134Z`, а полный v0.2
recovery archive перед итоговой fresh installation — под
`/var/tmp/ioc-extractor-p6-before-fresh-20260819T142653Z`.

После fresh installation normal daemon fixture создал `5` rows (`masks=1`,
`ip_list=1`, `address_blacklist=2`, `hashes=1`) с lifecycle IDs `1..5` и точным
TTL delta `43200000ms`. Все применимые public `time_first_seen` и
`time_last_seen` остались `NULL`. Service restart сохранил exact
`first_confirmed/last_confirmed/valid_until` tuples; после restart aggregate
health показал schemas `4/8`, lifecycle `ACTIVE`, clock `SAFE`, due/history/
projection backlog `0/0/0` и общий status `UP`.

Итоговое состояние стенда намеренно не откатано: active symlink указывает на
`releases/e089ae6a3fe8-p6-fresh`, последняя версия работает с `fixed/12h`, а
пять test records остаются active до ближайшего deadline
`2026-08-20T02:27:29.545Z`.

### Verification выполнено

```text
make lifecycle-smoke
  passed: 1501 canonical rows, expiry/history/retention/ID assertions

make lifecycle-load
  passed: 100001 canonical rows under packaged JVM profile

tools/ci/packaging.sh
  ShellCheck + packaging contracts + tools contracts: passed

Privileged packaged systemd stand
  passed: v0.2.0 compatibility upgrade, activation, expiry/retention,
  activation rollback, release rollback, fresh install and restart

Focused lifecycle/JDBC/config/ingest/export tests
  55 tests, 0 failures, 0 errors

make docs
  641 links, 0 errors

./mvnw -B -ntp -T 1C clean verify
  BUILD SUCCESS (02:14 wall clock)
  full reactor: 25 projects, 25 SUCCESS
  ioc-application: 197 tests, 0 failures, 0 errors
  ioc-adapter-store-jdbc: 122 tests, 0 failures, 0 errors
  ioc-adapter-ingest: 46 tests, 0 failures, 0 errors
  ioc-app: 250 tests, 0 failures, 0 errors
  aggregate SpotBugs baseline: 93 accepted, 0 visible

make spotbugs-baseline-proposal
  proposal: 0 new, 0 stale
```

После перезапуска IDE промежуточное сравнение SpotBugs показывало `53 new / 51
stale`: IDE/JDT успел перезаписать часть Maven `target/classes` и смешал два
варианта compiler-generated bytecode. Этот generated-artifact drift не принят
в baseline. Полный `clean verify` заново собрал весь reactor Maven/Javac и
подтвердил стабильный результат `93 accepted / 0 visible`; немедленное повторное
сравнение дало `0 new / 0 stale`.

### Packaged stand closure

Real systemd ownership, privileged immutable activation, service stop/start,
explicit activation rollback и matching binary/config/two-DB release rollback
проверены на disposable stand. Fresh installation затем повторена из пустого
layout и оставлена работающей на текущем release. Вместе с repository gates и
100k profile закрыли исходный P6 lifecycle scope. Они больше не закрывают
`R030-DATA` целиком: P7 должен повторить затронутые compatibility, performance и
packaged assertions для reusable export slots. Общие 0.3.0 release goals и
официальная Debian 11/12 platform qualification продолжают отслеживаться
отдельно.

## P7 — reusable export-slot correction

**Статус:** implementation и automated evidence complete; packaged
qualification/final gate pending.

Требуемое replacement evidence определено в
[export-slot-correction.md](export-slot-correction.md#10-p7-acceptance-evidence)
и [implementation plan](implementation-plan.md#p7--stable-reusable-export-slots).
P7 не должен удалять или переписывать измеренные P6 факты; он добавляет новый
run, который доказывает survivor stability, smallest-hole reuse, no compaction,
generation-safe snapshot, migration seeding и bounded 100k allocation.

### Реализованный contour (2026-08-20)

- dataframe format повышен `v4 -> v5`; additive migration создаёт
  `export_slot_assignment`, `export_slot_free` и `export_slot_state` в
  dataframe DB, не затрагивая service schema v8;
- `SnapshotSliceReader` остался единственным application output port.
  `JdbcSnapshotSliceReader` при `ACTIVE` сначала захватывает SQLite write
  ownership, затем вызывает package-private `JdbcExportSlotRegistry` и только
  после commit открывает generation-checked read snapshot;
- первичная инициализация seed-ит текущие positive canonical IDs, materialize-ит
  holes ниже seed high-water и fail-closed откатывает весь namespace при
  invalid seed/policy drift;
- дальнейший allocator set-based освобождает vanished assignments, ordinal-но
  сопоставляет ordered free slots и новые lifecycle, а остаток выдаёт из
  durable `next_slot` без `MAX(active.id)+1`;
- active projection выбирает `slot AS id` и сортирует по slot; compatibility
  mode и artifacts без внешней `id` остаются на прежнем пути;
- slot policy `stable-sparse-reusable-v1` включён в `ExportPlan.planHash` только
  для планов, содержащих slotted artifact.

### Automated evidence

| Проверка | Результат |
|---|---|
| `./mvnw -B -ntp -pl adapters/adapter-store-jdbc -am test` | adapter suite `132` tests, `0` failures/errors; focused `JdbcSnapshotSliceReaderTest + DataframeSchemaReconcilerTest` составляют `29` tests и включают `v4 -> v5`, seed rollback, policy drift, restart, generation guard, concurrent readers и slot matrix |
| `JdbcSnapshotSliceReaderTest#reconciles_and_streams_a_100k_slot_reuse_wave_with_indexed_queries` | `100000` active seed, затем `50000` expired + `50000` new при `50000` survivors; оба snapshots streaming, slots `1..100000`, test time `2.580s`, каждый measured reconciliation `<30s` |
| SQLite `EXPLAIN QUERY PLAN` assertions в том же тесте | projection использует covering `ix_export_slot_assignment_slot` + `ux_masks_lifecycle_id`; release membership использует те же indexes; free order использует PK autoindex; temp B-tree для `ORDER BY slot` отсутствует |
| `./mvnw -B -ntp -pl bootstrap/ioc-app -am -Dtest=ReusableExportSlotIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | `1` Spring integration test, `0` failures/errors; три physical CSV slices доказали `A=1,B=2,C=3 -> D=1,C=3 -> D=1,E=2,C=3`, первый slice byte-immutable |
| `make verify` | все `25` reactor modules `SUCCESS`; adapter-store-jdbc `132` tests, bootstrap `251` tests, aggregate SpotBugs `99 accepted / 0 visible` |
| `make spotbugs-baseline-proposal` после triage | `99 observed / 99 accepted / 0 new / 0 stale`; шесть `VA_FORMAT_STRING_USES_NEWLINE` устранены из production code, семь новых registry SQL identities и две изменившиеся snapshot identities приняты точечно как validated/quoted identifier boundaries с bound business values |

До release acceptance остаются: повторный packaged fresh-install/upgrade/rollback
стенд на P7 candidate, фиксация commit/runtime metadata и повторный freshness
gate на финальном закоммиченном HEAD. Поэтому прежний P6 packaged result не
переименован в P7 pass.
