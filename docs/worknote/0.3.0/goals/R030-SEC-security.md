---
title: "R030-SEC — Repository and CI security"
version: "0.3.0"
goal_id: "R030-SEC"
status: "Accepted"
document_type: "Release goal contract"
source_of_truth: false
language: "ru"
---

# R030-SEC — Repository, CI и publication security

## Outcome

Repository-hosted settings, CI/CD и Maven publication path соответствуют
принятому hardening baseline и подтверждены reproducible либо external
evidence.

Execution mode: **primarily global**.

Авторитетные документы:

- [SECURITY-ENGINEERING](../../../SECURITY-ENGINEERING.md);
- [THREAT-MODEL](../../../THREAT-MODEL.md).

## Scope

Результаты goal MUST обновлять состояния control registry в
[SECURITY-ENGINEERING §4](../../../SECURITY-ENGINEERING.md#4-реестр-текущих-контролей)
тем же change, который реализует или изменяет control. Существующий control
сохраняет stable ID; новый control получает новый ID, state и evidence.

### Repository governance

- default branch protection;
- required status checks для project-owned blocking gates; signal-only external
  reporting не включается без отдельного control decision;
- required reviews;
- protected tags/releases;
- CODEOWNERS для sensitive areas;
- evidence внешних settings, не представленных в Git.

### Dependency и source security

- dependency review;
- offline/reproducible vulnerability scan policy;
- update automation;
- immutable/pinned CI Actions;
- secret scanning и credential hygiene;
- justified suppressions с owner/expiry.

### Workflow security

- least-privilege `permissions`;
- trusted triggers;
- untrusted PR isolation;
- protected environments;
- no secret exposure in logs/artifacts;
- explicit artifact retention;
- immutable release inputs;
- external CI/reporting services имеют зарегистрированные permissions,
  credential, data-sharing, outage и enforcement contracts.

### Publication security

- credentials доступны только protected workflow;
- release publication не запускается локально;
- coordinates/version immutable;
- published bytes совпадают с verified bytes;
- overwrite запрещён;
- checksums, provenance/attestation disposition сохранены;
- repository/auth model проходит threat review.

Sensitive paths включают release workflows, packaging, bootstrap configuration,
storage/migrations, messaging contracts и publication metadata.

## Procedure

1. Выполнить gap analysis относительно security docs.
2. Классифицировать controls `Existing/Strengthen/Introduce/Evaluate/Deferred`.
3. Проверить workflows и effective permissions.
4. Проверить dependency/security reports.
5. Собрать external evidence branch/tag/environment settings.
6. Выполнить threat review publication path.
7. Исправлять controls reviewable slices.
8. Сделать stable project-owned blocking checks required; signal-only controls
   оставить non-required.
9. Обновить registry states/evidence или добавить новый control ID.
10. Сохранить residual risks и dispositions.

## Non-goals

- глубокое внедрение SAST и GitHub CodeQL в 0.3.0; этот контур требует
  отдельного security scope, threat/rule model и triage policy;
- трактовка generic SpotBugs bug-pattern analysis как закрытие
  `SEC-VER-1`: без отдельно принятого security ruleset/SAST control он
  остаётся build-quality check, а `SEC-VER-1` — `Planned`;
- случайный набор scanners без signal ownership;
- online/non-reproducible scan как единственный release gate;
- broad permissions «для удобства»;
- локальная release publication;
- хранение credentials в repository/config examples.

## Definition of Ready

- control/risk определён;
- current state и desired state известны;
- repository-hosted evidence доступно либо имеет owner;
- workflow/credential impact понятен;
- verification plan определён.

## Definition of Done

- required protections подтверждены;
- workflows least-privilege и pinned;
- dependency/security gates стабильны;
- publication environment защищён;
- credential boundary проверена;
- critical findings закрыты;
- suppressions/residual risks имеют owner и rationale;
- external settings evidence сохранено;
- каждое реализованное/изменённое security-relevant control отражено в
  `SECURITY-ENGINEERING.md` §4 тем же change.

## Dependencies

Требует `R030-BASE`, использует `R030-BUILD` CI infrastructure, блокирует
release publication в `R030-LIB` и финальный `R030-REL`.
