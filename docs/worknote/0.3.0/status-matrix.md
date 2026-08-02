---
title: "Матрица состояния релиза 0.3.0"
version: "0.3.0"
status: "Active"
document_type: "Execution status"
source_of_truth: false
language: "ru"
---

# Матрица состояния релиза 0.3.0

Правила состояний и переходов: [execution-model.md](execution-model.md).

## 1. Global goals

| Goal | State | Evidence | Next gate |
|---|---|---|---|
| `R030-BASE` | `verified` | [baseline](evidence/baseline.md), [compatibility ledger](evidence/compatibility-ledger.md) | Closed; re-baseline only by explicit scope/contract decision |
| `R030-BUILD` | `in-progress` | [build-quality ledger](evidence/build-quality-ledger.md) | Start `BUILD-SPOTBUGS-05`: convert the verified 77-finding reviewed baseline into a blocking no-new-findings ratchet |
| `R030-TEST` global controls | `planned` | [test-quality ledger](evidence/test-quality-ledger.md) | `TEST-LIFECYCLE-01` taxonomy and lifecycle separation |
| `R030-SEC` | `planned` | — | Security gap analysis |
| `R030-LIB` | `planned` | [shared-code inventory](evidence/shared-code-inventory.md) | `LIB-1` concurrency-library admission record |
| `R030-DOC` | `planned` | — | Translation inventory |
| `R030-REL` | `planned` | — | All prerequisite goals |

## 2. Module hardening matrix

Все cells начинаются с `planned`: applicability подтверждается analysis.

| Maven module | `R030-QUAL` | `R030-RETIRE` | `R030-ARCH` | `R030-TEST` | affected `R030-DOC` |
|---|---|---|---|---|---|
| `platform/platform-errors` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-etl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-events` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-concurrency` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-observability` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `platform/platform-diagnostics-logging` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-domain` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `core/ioc-application-tck` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-regex-re2j` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-psl` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-source-tika` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-sink-csv` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-manifest-json-jackson` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-store-jdbc` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-transport-smb` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-ingest` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-cli-picocli` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `bootstrap/ioc-app` | `planned` | `planned` | `planned` | `planned` | `planned` |

## 3. Work items

| Work item | Goal | Scope | Stage | Owner | State | Evidence |
|---|---|---|---|---|---|---|
| `BUILD-SPOTBUGS-01` | `R030-BUILD` | 19 production runtime JAR modules; fail-closed 24-project disposition registry keeps root/build-support POMs and reusable test-contract `ioc-application-tck` explicitly outside bytecode scope | Report-only tool introduction, per-module reports, reactor aggregate and 118-finding baseline; root `validate` checks scope before child builds, late report integrity checks outputs, and a synthetic-reactor matrix protects both contracts | AI agent | `verified` | [SpotBugs rollout](evidence/build-quality-ledger.md#spotbugs-rollout) |
| `BUILD-CPD-02` | `R030-BUILD` | Fail-closed 24-project disposition registry; one repository-wide report over 499 unique Java source paths in 19 production modules; TCK, tests, generated/vendor sources and build-support explicitly excluded | PMD CPD `minimumTokens=75`; 11 raw matches mapped to 10 semantic R030-QUAL findings; shared root scope gate, automated negative matrix and late source/report reconciliation block omissions while findings remain report-only | AI agent | `verified` | [CPD findings and calibration](evidence/build-quality-ledger.md#pmd-cpd-findings) |
| `BUILD-DEPS-03` | `R030-BUILD` | Maven bytecode dependency analysis over all 20 functional JAR modules, including reusable TCK; root and three build-only POMs explicitly observed as plugin-skipped | Plugin `3.11.0` pinned; 14 direct source/POM mismatches corrected; residual `56 / 34 / 12` findings semantically classified; blocking lifecycle adoption deferred and an opt-in report profile retained without suppressions | AI agent | `verified` | [Dependency-analysis disposition](evidence/build-quality-ledger.md#maven-dependency-analysis-findings) |
| `BUILD-SPOTBUGS-04` | `R030-BUILD` | Final remediation baseline: 77 reviewed findings in the same 19-module production-bytecode scope; one inherited filter has 71 exact selectors for 59 false positives + 18 policy-noise findings and no accepted legacy | Exception boundaries, 11 small local findings and all 44 mutable aliases are hardened with focused regressions. C4 also replaced compiler-generated lambda names with a stable exact class/field selector. Final clean + immediate repeat both pass 24/24 with 836 tests, 2 external SMB skips, 19 module report pairs + aggregate, 0 visible findings and 0 analyzer errors/missing classes. Findings remain report-only until `BUILD-SPOTBUGS-05` | AI agent | `verified` | [execution worknote](build-spotbugs-04-worknote.md) |

## 4. Deferred, blocked и queue decisions

| Work item/scope | Goal | State | Reason | Missing evidence/exit condition |
|---|---|---|---|---|
| `ING-10 / SB04-116` ingest lifecycle + JDBC ledger transitions | `R030-QUAL` | `verified` | Все пять checkpoints реализованы и проверены: startup barrier, shared per-key guard, fresh-state recovery, monotonic file/JDBC CAS, lifecycle health, exact-1 config guard, restart regression и watched-inbox E2E; observability follow-up добавляет typed recovery timeline, duplicate disposition и exact transition-conflict diagnostic без duplicate delivery; `SB04-116` отсутствует в обновлённом отчёте | Вернуть очередь к `BUILD-SPOTBUGS-04/C3`; multi-daemon lease/fencing остаётся отдельным scope |
| Full PMD ruleset beyond CPD | `R030-BUILD` | `deferred` | Unused-code, complexity, performance/object-creation and error-prone rules need their own signal/noise decision; they are not implied by CPD | Revisit after `BUILD-SPOTBUGS-04`, or earlier only after concrete risk/gap evidence and explicit matrix reorder |

## 5. Update rule

Matrix обновляется после каждого analysis или implementation work item.
`verified` требует goal-specific DoD; наличие merged PR само по себе
недостаточно.
