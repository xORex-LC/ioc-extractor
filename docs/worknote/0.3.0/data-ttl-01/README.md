---
title: "DATA-TTL-01 — рабочий комплект"
version: "0.3.0"
status: "Verified"
document_type: "Worknote bundle index"
source_of_truth: false
language: "ru"
---

# DATA-TTL-01 — рабочий комплект

Этот каталог изолирует все подробные рабочие материалы по TTL canonical records
от остальных заметок релиза 0.3.0. Он не является публикуемой документацией и
не заменяет ADR, capability docs или operator guides.

## Текущий статус

- интервью I-01..I-19 завершено, решения подтверждены заказчиком;
- I-20 закрыт: expiry не инициирует immutable export; automatic slice создаётся
  только после добавления новых canonical active rows;
- I-21 фиксирует результаты анализа OpenCTI, MISP, STIX 2.1 и Spring runtime
  options: canonical vocabulary использует `valid_until`, а `revoked`, decay
  score и новые scheduling frameworks не входят в V1;
- DATA-TTL-01 принят как отдельный release-blocking scope change под MUST-goal
  `R030-DATA`;
- architecture/ADR/contract приняты и implementation go-ahead получен
  2026-08-16; P0–P6 реализованы и проверены;
- P6 включил fresh-install preset `fixed/12h`, опубликовал capability/operator
  documentation и добавил rootless lifecycle smoke/load harness;
- reference profile провёл 100001 canonical rows через active → typed history
  → retention purge с production JVM heap profile, indexed plans и bounded
  transactions;
- privileged packaged stand 2026-08-18/19 проверил v0.2.0 compatibility
  upgrade, explicit activation, expiry/retention, activation rollback, release
  rollback и fresh installation; последняя версия оставлена работающей с
  production `fixed/12h` preset.
- I-22 переоткрыл внешний `id`: это stable sparse reusable `export_slot`, а не
  canonical identity. P6 сохранил силу для TTL lifecycle, но прежний
  ID-non-reuse результат является characterization текущей реализации;
- P7 реализовал stable sparse reusable export slots: surviving rows сохраняют
  mapping, expired slots освобождаются только при eligible export, а новые
  lifecycle получают минимальные holes без compaction;
- P8 требует новую delivery occurrence после принятой reappearance lifecycle,
  даже если CSV bytes и переиспользованные slots совпали с историческим slice;
  active confirmation, renewal и expiry сами export не инициируют;
- P9 заменил idle full reconciliation на read-only nearest-deadline refresh,
  ограничил runtime state singleton checkpoint-ом dataframe v6, отделил hourly
  history/receipt cleanup и убрал пустые lifecycle INFO;
- P7–P9 implementation, automated evidence и повторная packaged qualification
  завершены 2026-09-01 на exact `v0.2.0` upgrade/rollback, explicit activation,
  reusable-slot/reappearance/byte-identical delivery и clean-install matrix;
- final repository gate прошёл на commit `b3aee0a3` 2026-09-01;
  `verify.commit` совпал с `HEAD`, `verify.fresh=true`. DATA-TTL-01 закрыт.

## Навигация

| Документ | Назначение |
|---|---|
| [discovery.md](discovery.md) | Полный журнал интервью, варианты, риски и подтверждённые решения I-01..I-22 |
| [release-contract.md](release-contract.md) | Scope, MUST outcome, DoR/DoD, compatibility и обязательное evidence |
| [architecture-project.md](architecture-project.md) | Целевая component/data/transaction/event architecture, module/library decision и risk model |
| [export-slot-correction.md](export-slot-correction.md) | Исправленный consumer contract, durable registry, reconciliation, migration и P7 acceptance matrix |
| [implementation-plan.md](implementation-plan.md) | Порядок P0–P9, границы slices, gates и verification plan |
| [evidence.md](evidence.md) | Выполненные slices, изменённые boundaries и воспроизводимые проверки |
| [P6 load profile](evidence/p6-load-profile.md) | Reference environment, thresholds, measurements и query plans |
| [release-note input](release-note-input.md) | Curated DATA-TTL-01 material для итоговых release notes 0.3.0 |
| [ADR-0020](../../../ADR/0020-canonical-record-expiration-lifecycle.md) | Принятое архитектурное решение и lifecycle invariants |
| [ADR-0021](../../../ADR/0021-stable-reusable-export-slots.md) | Принятое решение: внешний `id` как stable sparse reusable export slot |
| [ADR-0022](../../../ADR/0022-revision-significant-identical-export.md) | Новая delivery occurrence при более новой covered revision, даже если bytes совпадают |
| [ADR-0023](../../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md) | Deadline-aware backstop, singleton reconcile checkpoint и independent retention cadence |

Будущее execution evidence добавляется только в этот bundle либо в явно
указанный внешний release ledger. В общих `engineering-release.md` и
`status-matrix.md` остаются короткие регистрационные ссылки.

## Зафиксированные defaults V1

- classpath/upgrade default: record validity `disabled`;
- fresh-install production template: fixed TTL `12h`;
- history и complete duplicate-receipt retention: configurable, default `30d`;
- logical active interval: `[first_confirmed_at, valid_until)`;
- healthy idle daemon начинает reconciliation не позднее чем через `5s` после
  deadline;
- public `time_first_seen`/`time_last_seen` сохраняют порядок и остаются `NULL`;
- `valid_until` не входит в существующие dataframe/export schemas.

Значение `12h` осознанно допускает пустой active set и окна между редкими feeds.
Оно не гарантирует непрерывность при суточной доставке источника.

## Граница authority

- Architecture project фиксирует принятый implementation design, но не является
  published authority.
- Принятый ADR фиксирует долгоживущую семантику как published authority.
- Release contract определяет, что блокирует 0.3.0.
- Implementation plan определяет порядок работы, но не доказывает выполнение.
- Discovery сохраняет контекст выбора и не является runtime contract.
- После реализации фактическая механика должна быть перенесена в affected
  `docs/dev/*`, module README, operator guide и release notes.
