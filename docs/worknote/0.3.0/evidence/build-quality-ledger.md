---
title: "0.3.0 build-quality evidence"
version: "0.3.0"
goal_id: "R030-BUILD"
status: "Not started"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-BUILD — Build-quality evidence

Contract: [R030-BUILD](../goals/R030-BUILD-build-quality.md).

Этот ledger хранит изменяемые reports, findings и adoption decisions.
Нормативный scope и rollout rules находятся только в goal contract.

## Принятый evaluation scope

| Control | Scope 0.3.0 | Начальный режим | Возможный результат |
|---|---|---|---|
| SpotBugs | Все применимые production Java modules | Report only | Blocking no-new-findings check в `verify` |
| PMD CPD | Repository-wide production-source duplication | Report only | Diagnostic control + semantic dispositions |
| Maven `dependency:analyze-only` | Dependency hygiene evaluation | Report only | `Adopt / Adopt with exclusions / Defer` |
| PIT | Только `core/ioc-domain`; ведётся в R030-TEST | Diagnostic pilot | `Adopt / Extend / Defer / Reject` |

## Tool evaluation

| Control | Version/config | Local command | CI/report artifact | Runtime | Signal/noise | Owner | Stage |
|---|---|---|---|---:|---|---|---|
| SpotBugs | TBD | TBD | XML + HTML: TBD | TBD | TBD | TBD | `planned` |
| PMD CPD aggregate | TBD | TBD | TBD | TBD | TBD | TBD | `planned` |
| Maven dependency analysis | TBD | TBD | TBD | TBD | TBD | TBD | `planned` |

Допустимые rollout stages: `planned`, `report-only`, `triaged`, `baselined`,
`blocking` и `tightening`.

## SpotBugs rollout

| Scope/module | Analyzed | Findings | Immediate fixes | Baseline filters | Clean rerun | Blocking evidence |
|---|---|---:|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

### SpotBugs suppression register

| Selector/pattern | Scope | Rationale | Owner | Review/exit condition | Evidence |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

Пустой register означает отсутствие принятых suppressions. Analyzer error,
пропущенный module или отсутствующий report не регистрируются как false
positive.

## SpotBugs findings

| Pattern/category | Scope | Count | Highest risk | False-positive class | Disposition/evidence |
|---|---|---:|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

При adoption отдельно фиксируются accepted rules/severities, baseline format,
new-code ratchet, узкие suppressions и их review conditions.

## PMD CPD findings

| Finding | Occurrences | Shared knowledge/behavior | Semantic differences | Disposition | Rationale | R030-QUAL finding |
|---|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

CPD report не является списком автоматических refactorings. Каждая существенная
находка проходит duplication triage из
[R030-QUAL](../goals/R030-QUAL-code-health.md).

## CPD configuration calibration

| Candidate `minimumTokens` | Finding count | Noise classes | Missed known duplicate | Runtime | Decision |
|---:|---:|---|---|---:|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

Принятый threshold обосновывается repository evidence. Generated/vendor
exclusions перечисляются точными paths/selectors.

## Maven dependency-analysis findings

| Module | Finding type | Dependency | Dynamic/framework evidence | Disposition | Work item |
|---|---|---|---|---|---|
| TBD | `used-undeclared / declared-unused / scope` | TBD | TBD | TBD | TBD |

Исключение содержит точные coordinates, rationale, owner и review condition.
Broad group exclusion не принимается, если возможна более узкая запись.

## Adoption decisions

### Control — decision

- **Decision:** `Adopt | Adopt with exclusions | Defer | Reject`
- **Evidence:**
- **Accepted signal/rules:**
- **Baseline/ratchet:**
- **Suppression policy:**
- **Runtime/CI impact:**
- **Owner:**
- **Revisit condition:**

SpotBugs имеет принятое решение `Adopt` в 0.3.0; этот шаблон фиксирует точный
signal и допустимые exclusions. Для CPD decision описывает diagnostic
configuration и условия возможного будущего no-new-duplication ratchet.

## Deferred tool boundaries

Следующие controls не требуют evaluation для закрытия 0.3.0:

- GitHub CodeQL и SAST/SecOps;
- Spotless и Checkstyle;
- japicmp и Revapi;
- Error Prone и NullAway;
- SonarQube и Qodana;
- полный PMD ruleset помимо CPD.

Их отсутствие не является missing evidence текущего `R030-BUILD`.

## Completion

- [ ] SpotBugs report воспроизводим
- [ ] SpotBugs signal/noise/cost оценены
- [ ] SpotBugs production-module scope подтверждён
- [ ] Immediate-risk SpotBugs findings исправлены
- [ ] SpotBugs baseline filters узкие и обоснованы
- [ ] SpotBugs `check` стабильно входит в Maven `verify`
- [ ] SpotBugs запрещает новые findings принятого signal
- [ ] PMD CPD aggregate report воспроизводим
- [ ] CPD threshold откалиброван на repository evidence
- [ ] Существенные CPD findings переданы в R030-QUAL
- [ ] Существенные CPD findings имеют semantic disposition
- [ ] PMD CPD diagnostic configuration и ownership приняты
- [ ] Maven dependency-analysis report воспроизводим
- [ ] Dynamic/framework false positives проверены
- [ ] Maven dependency-analysis adoption decision принят
- [ ] Adopted ratchets и suppressions документированы
- [ ] Status matrix обновлена
