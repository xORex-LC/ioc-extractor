---
title: "DATA-TTL-01 — рабочий комплект"
version: "0.3.0"
status: "Review"
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
- DATA-TTL-01 принят как отдельный release-blocking scope change под MUST-goal
  `R030-DATA`;
- P0 оформляет решение и план для review;
- production code, SQLite migrations и runtime activation ещё не начинались;
- P1–P6 начинаются только после отдельного implementation go-ahead.

## Навигация

| Документ | Назначение |
|---|---|
| [discovery.md](discovery.md) | Полный журнал интервью, варианты, риски и подтверждённые решения I-01..I-20 |
| [release-contract.md](release-contract.md) | Scope, MUST outcome, DoR/DoD, compatibility и обязательное evidence |
| [architecture-project.md](architecture-project.md) | Целевая component/data/transaction/event architecture, module/library decision и risk model |
| [implementation-plan.md](implementation-plan.md) | Порядок P0–P6, границы slices, gates и verification plan |
| [ADR-0020](../../../ADR/0020-canonical-record-expiration-lifecycle.md) | Draft decision record; не становится authoritative до завершения architecture review |

Будущее execution evidence добавляется только в этот bundle либо в явно
указанный внешний release ledger. В общих `engineering-release.md` и
`status-matrix.md` остаются короткие регистрационные ссылки.

## Зафиксированные defaults V1

- classpath/upgrade default: expiration `disabled`;
- fresh-install production template: fixed TTL `12h`;
- history и complete duplicate-receipt retention: configurable, default `30d`;
- logical active interval: `[first_confirmed_at, expires_at)`;
- healthy idle daemon начинает reconciliation не позднее чем через `5s` после
  deadline;
- public `time_first_seen`/`time_last_seen` сохраняют порядок и остаются `NULL`;
- `expires_at` не входит в существующие dataframe/export schemas.

Значение `12h` осознанно допускает пустой active set и окна между редкими feeds.
Оно не гарантирует непрерывность при суточной доставке источника.

## Граница authority

- Architecture project фиксирует полный проект для review, но не является
  published authority.
- ADR фиксирует долгоживущую семантику только после отдельного принятия.
- Release contract определяет, что блокирует 0.3.0.
- Implementation plan определяет порядок работы, но не доказывает выполнение.
- Discovery сохраняет контекст выбора и не является runtime contract.
- После реализации фактическая механика должна быть перенесена в affected
  `docs/dev/*`, module README, operator guide и release notes.
