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
