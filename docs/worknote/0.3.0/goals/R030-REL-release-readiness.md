---
title: "R030-REL — Release readiness"
version: "0.3.0"
goal_id: "R030-REL"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-REL — Release readiness

## Outcome

0.3.0 выпускается воспроизводимо, сохраняет supported behavior, документирует
intentional changes и связывает release artifacts с проверенным source state.

Execution mode: **final global gate**.

Авторитетный процесс:

- [RELEASE-PROCESS](../../../RELEASE-PROCESS.md).

## Prerequisites

До release candidate:

- все MUST-goals имеют выполненный DoD либо approved scope change;
- [status matrix](../status-matrix.md) не содержит unexplained open cells;
- clean checkout и toolchain подтверждены;
- release commit/revision определены.

## Non-regression matrix

Проверяются:

- CLI/options/output/exit codes;
- configuration schema/defaults/preflight;
- DB migrations/durable state/upgrade/rollback;
- row identity/public IDs/deduplication;
- CSV/export/manifest contracts;
- diagnostics/logging;
- daemon lifecycle/health;
- remote sync;
- packaging/deployment.

Intentional bug fix или retirement содержит:

- reason;
- previous/new behavior;
- tests;
- compatibility impact;
- migration/rollback guidance;
- release-note entry.

## Required gates и evidence

Автоматический gate блокирует release своим результатом. Recorded evidence
обязательно должно существовать и иметь disposition, но само измеренное
отклонение MAY быть принято после review, если соответствующий contract не
задаёт жёсткий threshold.

- `make context`;
- clean reactor verification;
- tests, ArchUnit и Enforcer;
- JaCoCo reactor aggregate `75%` line / `80%` branch;
- JaCoCo `85%` line / `90%` branch для `core/ioc-domain` и
  `core/ioc-application`;
- accepted per-module coverage ratchets;
- сигнальные Codecov project/base-relative/patch reports, если внешний сервис
  доступен; Codecov status не является required;
- SpotBugs blocking no-new-findings check внутри Maven `verify`;
- PMD CPD repository-wide diagnostic report с semantic dispositions
  существенных findings;
- PIT report-only pilot для `core/ioc-domain` с survived-mutant triage,
  runtime evidence и adoption disposition;
- Maven dependency-analysis report с adoption disposition;
- ratchets принятых code-quality checks;
- существующие dependency/repository security gates; SAST/CodeQL не является
  gate 0.3.0;
- golden/e2e corpus;
- published-library external consumer;
- documentation conventions/links;
- recorded representative performance/resource comparison с обязательной
  disposition;
- packaging/install/deploy checks;
- upgrade и rollback evidence.

Если recorded verification не fresh для release commit, gate выполняется
повторно.

## Artifact identity

- artifacts строятся из protected release source state;
- build commit/version metadata корректны;
- verified bytes являются published bytes;
- release version immutable;
- checksums/evidence сохранены;
- library и runnable artifacts имеют понятный ownership.

## Performance/resources

С baseline сравниваются:

- build/test duration;
- representative extraction/export;
- daemon startup;
- heap/threads/file descriptors;
- DB/output sizes.

Regression не обязательно блокирует release, но MUST иметь analysis и accepted
disposition. Отсутствие согласованного measurement либо disposition блокирует
release как missing evidence.

## Release notes

Curated notes содержат:

- engineering outcomes;
- intentional behavior changes;
- compatibility retirements;
- migration guidance;
- published libraries/coordinates;
- security/build/documentation changes;
- known residual risks;
- verification summary.

Generated changelog не заменяет curated notes.

## Environment-dependent evidence

Packaging, systemd, network transports, repository settings и publication
environment могут требовать внешнего стенда. Пропущенная проверка:

- не считается passed;
- имеет owner;
- содержит reason и impact;
- имеет explicit release disposition.

## Procedure

1. Проверить completion всех goals/matrix cells.
2. Зафиксировать release commit/toolchain.
3. Заполнить non-regression/compatibility matrix.
4. Выполнить clean global gates.
5. Выполнить publication consumer и artifact identity checks.
6. Сравнить performance/resources.
7. Проверить packaging/upgrade/rollback.
8. Синхронизировать фактические статусы затронутых долгов в
   [KNOWN-ISSUES](../../../KNOWN-ISSUES.md).
9. Завершить docs и curated notes.
10. Зафиксировать external evidence.
11. Принять final go/no-go.

## Definition of Ready

- prerequisite goals закрыты;
- release commit/version определены;
- environment credentials/targets доступны;
- release checklist и owners назначены;
- known missing evidence перечислено.

## Definition of Done

- clean release gate green;
- non-regression matrix не имеет unexplained changes;
- `R030-RETIRE` inventory закрыт;
- performance/resource evidence записано, regressions имеют disposition;
- [KNOWN-ISSUES](../../../KNOWN-ISSUES.md) отражает фактические статусы
  затронутых долгов: закрытые пункты перенесены в архив с evidence, частично
  выполненные обновлены;
- publication consumer проходит;
- security/docs/packaging gates завершены;
- artifact identity подтверждена;
- release notes и migration guidance готовы;
- rollback evidence сохранено;
- release artifacts опубликованы только protected workflow.

## Dependencies

Зависит от всех остальных goals и является terminal release gate.
