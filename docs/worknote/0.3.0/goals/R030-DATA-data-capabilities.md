---
title: "R030-DATA — принятые data capabilities"
version: "0.3.0"
goal_id: "R030-DATA"
status: "Verified with explicit external defer"
source_of_truth: false
language: "ru"
---

# R030-DATA — TTL, managed import и IOC aggregate

## Принятый scope и outcome

DATA-TTL-01, DATA-IMPORT-01 и DATA-AGGREGATE-01 реализованы и квалифицированы
в согласованных repository/package scopes. Частичная production activation
не допускается: конфигурация, обе БД, unit и binary образуют coordinated
upgrade/rollback boundary. Это не общий release closure.

| Scope | Постоянный контракт | Квалификация |
|---|---|---|
| Fixed lifecycle, validity, history, receipts, reusable export slots | [Lifecycle](../../../dev/canonical-record-lifecycle.md), ADR 0020–0023 | Explicit existing-record cutover, restart/crash/expiry/reappearance, 100k calibration, packaged fresh/upgrade/rollback |
| Managed local/SMB dataframe import | [Import](../../../dev/dataframe-import.md), [operator guide](../../../guides/dataframe-import.md), ADR 0024–0025 | Snapshot/workspace/recognition, canonical commit receipt, ordered recovery, promotion/rejection, retention/replay; source qualification отдельно |
| Aggregate artifact / ordered names | [Aggregate guide](../../../guides/ioc-aggregate.md), [processing](../../../dev/processing.md), [ADR 0030](../../../ADR/0030-registered-observation-order-for-artifact-fields.md) | Empty/no-backfill migration, multi-type rows, keep-first legacy artifacts, ordered observation names, packaged and live activation |

Старые P0–P9 чек-листы не являются действующим API/config contract. Исходные
commit/JAR/config identities, failed attempts, calibration и recovery сохранены
в [постоянной записи](../../../../.github/release-evidence/ru/0.3.0.md).
Историческая lifecycle non-reusable public-id проверка superseded reusable
export slots; canonical lifecycle IDs остаются non-reusable.

## Оставшиеся обязательства и границы поддержки

- ADR 0025 H5 / [OPS-8](../../../KNOWN-ISSUES.md#ops-8): two-identity live SMB
  hardening явно deferred. Producer denial, service capability, missing namespace,
  no-replace claim, reconnect, disposition и exact remote purge проверяются на
  конкретном source/server family перед включением. Неисполненная family — skip.
  Default import disabled; Samba/Windows отдельные результаты не дают общего
  support claim.
- [TOOL-10](../../../KNOWN-ISSUES.md#tool-10): текущий lifecycle smoke/load runner
  не принят; историческая P6 калибровка не закрывает поломанный harness.
- [OPS-10](../../../KNOWN-ISSUES.md#ops-10), [OPS-14](../../../KNOWN-ISSUES.md#ops-14)
  и другие текущие Known Issues сохраняются отдельными задачами. Удаление
  проектных записок не означает исправление source defects.
- Новая candidate identity требует fresh gates и applicable deployment evidence;
  подготовка нового релиза не разрешает включать import на неквалифицированном
  target либо применять binary-only downgrade к изменённой schema.

## Условие сохранения verified

Не ослаблять explicit activation, lifecycle/match/export identity, canonical
receipt authority, failure-policy checkpoint, idempotent recovery и no-partial
promotion. При изменении этих границ обновляются owning ADR/docs/tests и
повторяется затронутая qualification; принятый H5 defer остаётся явным до его
фактического выполнения.
