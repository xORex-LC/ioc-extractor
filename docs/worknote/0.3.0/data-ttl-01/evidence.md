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
