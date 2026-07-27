---
title: "0.3.0 test-quality evidence"
version: "0.3.0"
goal_id: "R030-TEST"
status: "Not started"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-TEST — Test-quality evidence

Contract: [R030-TEST](../goals/R030-TEST-test-quality.md).

Этот ledger хранит изменяемые измерения и dispositions. Нормативные thresholds,
taxonomy и scope находятся только в goal contract.

## Instrumentation

| Control | Version/config | Local command | CI evidence | State |
|---|---|---|---|---|
| JaCoCo agent/report | TBD | TBD | TBD | `planned` |
| JaCoCo per-module check | TBD | TBD | TBD | `planned` |
| JaCoCo aggregate check | TBD | TBD | TBD | `planned` |
| Surefire unit lifecycle | TBD | TBD | TBD | `existing-unclassified` |
| Failsafe integration lifecycle | TBD | TBD | TBD | `planned` |
| JUnit tag convention | TBD | TBD | TBD | `planned` |
| Codecov best-effort upload | TBD | N/A | TBD | `planned` |
| Codecov project/patch signals | TBD | N/A | TBD | `planned` |
| Coverage/test artifacts | TBD | TBD | TBD | `planned` |

## Coverage baseline и ratchet

| Module/scope | Production universe | Line baseline | Branch baseline | Release floor | Current | Ratchet | State |
|---|---|---:|---:|---|---|---|---|
| Reactor aggregate | TBD | TBD | TBD | `75% / 80%` | TBD | TBD | `planned` |
| `platform/platform-errors` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `platform/platform-diagnostics` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `platform/platform-etl` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `platform/platform-events` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `platform/platform-concurrency` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `platform/platform-observability` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `platform/platform-diagnostics-logging` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `core/ioc-domain` | TBD | TBD | TBD | `85% / 90%` | TBD | TBD | `planned` |
| `core/ioc-application` | TBD | TBD | TBD | `85% / 90%` | TBD | TBD | `planned` |
| `core/ioc-application-tck` | test-support | TBD | TBD | contract execution | TBD | TBD | `planned` |
| `adapters/adapter-regex-re2j` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-psl` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-source-tika` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-sink-csv` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-manifest-json-jackson` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-store-jdbc` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-transport-smb` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-ingest` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `adapters/adapter-cli-picocli` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |
| `bootstrap/ioc-app` | TBD | TBD | TBD | baseline | TBD | TBD | `planned` |

Для small-denominator modules в evidence добавляются absolute missed
instructions/branches.

## Coverage exclusions

| Selector | Scope/type | Rationale | Denominator impact | Owner | Review condition | State |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — |

Пустая таблица означает отсутствие принятых exclusions, а не отсутствие
проверки.

## Test taxonomy migration

| Current test/suite | Observed level | Target lifecycle/name | Tags | Action | Evidence | State |
|---|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | `planned` |

## Risk и effectiveness findings

| Finding | Scope/behavior | Gap type | Risk | Required evidence | Disposition | Work item |
|---|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

Gap type examples: `negative`, `boundary`, `error`, `recovery`,
`concurrency`, `migration`, `contract`, `assertion-quality`.

## Flake, wait и duration findings

| Test/suite | Signal | Reproduction/seed | Duration | Owner | Disposition | Exit condition |
|---|---|---|---:|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

## Codecov

| Check | Expected signal | Run/status evidence | Branch protection | State |
|---|---|---|---|---|
| Project floor | `75%`, threshold `0%` | TBD | not required | `planned` |
| Project ratchet | `auto`, threshold `<= 0.1%` | TBD | not required | `planned` |
| Patch changed lines | target `90%`, threshold `0%` | TBD | not required | `planned` |
| Missing Codecov report/upload | external reporting failure | TBD | not required | `planned` |

## Diagnostic pilots

| Pilot | Scope | Command/config | Report artifact | Signal/noise | Runtime cost | Decision | Evidence |
|---|---|---|---|---|---:|---|---|
| PIT | `core/ioc-domain` | TBD | HTML/XML: TBD | TBD | TBD | TBD | TBD |
| Random order/repeat | scheduled selected/full suites | TBD | TBD | TBD | TBD | TBD | TBD |
| Invariant-oriented tests | selected domain rules | TBD | TBD | TBD | TBD | TBD | TBD |

### PIT survived-mutant triage

| Mutant/location | Critical rule | Classification | Test/work item | Rationale | State |
|---|---|---|---|---|---|
| TBD | TBD | `test-gap / equivalent-noise / non-critical / investigate` | TBD | TBD | TBD |

Если полный domain pilot разделён на package/capability profiles, evidence MUST
показывать, что profiles непересекаются и вместе покрывают принятый mutation
universe.

## Completion

- [ ] Test inventory and lifecycle classification complete
- [ ] Per-module and aggregate baseline captured
- [ ] Coverage universe and exclusions accepted
- [ ] Fixed floors and per-module ratchets enforced
- [ ] Codecov signal operational либо имеет external-unavailability disposition
- [ ] Codecov status подтверждён как non-required
- [ ] Risk-based gaps have disposition
- [ ] Flake/wait/duration findings have disposition
- [ ] PIT command/profile и reports воспроизводимы
- [ ] PIT survived mutants классифицированы
- [ ] PIT runtime cost измерена
- [ ] Diagnostic pilots have adoption decisions
- [ ] Published testing documentation matches live build
- [ ] Status matrix updated
