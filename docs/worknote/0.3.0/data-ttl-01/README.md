---
title: "DATA-TTL-01 — рабочий комплект"
version: "0.3.0"
status: "Implementation"
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
  только после добавления новых public active rows;
- I-21 фиксирует результаты анализа OpenCTI, MISP, STIX 2.1 и Spring runtime
  options: canonical vocabulary использует `valid_until`, а `revoked`, decay
  score и новые scheduling frameworks не входят в V1;
- DATA-TTL-01 принят как отдельный release-blocking scope change под MUST-goal
  `R030-DATA`;
- architecture/ADR/contract приняты и implementation go-ahead получен
  2026-08-16; P0 закрыт;
- P1 framework-free lifecycle model, ports, unit tests и reusable TCK
  реализованы и проверены;
- P2 additive SQLite foundation реализован в dataframe DB: format v4,
  per-artifact lifecycle/history/receipt schema, durable allocators,
  activation/projection CAS и integration/query-plan/concurrency tests;
- foundation остаётся `DISABLED_COMPATIBLE`, не подключена к старому canonical
  runtime path и не включает TTL; следующий slice — P3 atomic write/read path.

## Навигация

| Документ | Назначение |
|---|---|
| [discovery.md](discovery.md) | Полный журнал интервью, варианты, риски и подтверждённые решения I-01..I-21 |
| [release-contract.md](release-contract.md) | Scope, MUST outcome, DoR/DoD, compatibility и обязательное evidence |
| [architecture-project.md](architecture-project.md) | Целевая component/data/transaction/event architecture, module/library decision и risk model |
| [implementation-plan.md](implementation-plan.md) | Порядок P0–P6, границы slices, gates и verification plan |
| [evidence.md](evidence.md) | Выполненные slices, изменённые boundaries и воспроизводимые проверки |
| [ADR-0020](../../../ADR/0020-canonical-record-expiration-lifecycle.md) | Принятое архитектурное решение; dormant storage foundation реализован, runtime activation ещё отсутствует |

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
