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
| `R030-DATA` | `in-progress` | [TTL evidence](data-ttl-01/evidence.md), [export-slot correction](data-ttl-01/export-slot-correction.md), [release contract](data-ttl-01/release-contract.md) | `DATA-TTL-01/P7`: implement stable reusable export slots, repeat compatibility/performance/packaged evidence and final verification |
| `R030-BUILD` | `in-progress` | [build-quality ledger](evidence/build-quality-ledger.md) | `BUILD-PMD-06/P3`: choose explicit adoption disposition and lifecycle from the completed P2 semantic evidence; SpotBugs blocking adoption remains closed |
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
| `DATA-TTL-01` | `R030-DATA` | Canonical record expiry plus separation of internal lifecycle identity from stable sparse reusable export slots | P0–P6 lifecycle implementation/evidence complete; P7 export-slot design accepted, implementation and replacement evidence pending | AI agent + user review | `in-progress` (`P7`) | [evidence](data-ttl-01/evidence.md), [export-slot correction](data-ttl-01/export-slot-correction.md), [implementation plan](data-ttl-01/implementation-plan.md), [ADR-0020](../../ADR/0020-canonical-record-expiration-lifecycle.md), [ADR-0021](../../ADR/0021-stable-reusable-export-slots.md) |
| `BUILD-SPOTBUGS-01` | `R030-BUILD` | 19 production runtime JAR modules; fail-closed 25-project disposition registry keeps root/build-support POMs and reusable test-contract `ioc-application-tck` explicitly outside bytecode scope | Report-only tool introduction, per-module reports, reactor aggregate and 118-finding baseline; root `validate` checks scope before child builds, late report integrity checks outputs, and a synthetic-reactor matrix protects both contracts | AI agent | `verified` | [SpotBugs rollout](evidence/build-quality-ledger.md#spotbugs-rollout) |
| `BUILD-CPD-02` | `R030-BUILD` | Fail-closed 25-project disposition registry; one repository-wide report over all current Java source paths in 19 production modules — 508 in the latest full report versus 499 in the adoption snapshot; TCK, tests, generated/vendor sources and build-support explicitly excluded | PMD CPD `minimumTokens=75`; 11 raw matches mapped to 10 semantic R030-QUAL findings; shared root scope gate, automated negative matrix and late source/report reconciliation block omissions while findings remain report-only | AI agent | `verified` | [CPD findings and calibration](evidence/build-quality-ledger.md#pmd-cpd-findings) |
| `BUILD-DEPS-03` | `R030-BUILD` | Maven bytecode dependency analysis over all 20 functional JAR modules, including reusable TCK; root and four build-only POMs explicitly observed as plugin-skipped | Plugin `3.11.0` pinned; 14 direct source/POM mismatches corrected; residual `56 / 34 / 12` findings semantically classified; blocking lifecycle adoption deferred and an opt-in report profile retained without suppressions | AI agent | `verified` | [Dependency-analysis disposition](evidence/build-quality-ledger.md#maven-dependency-analysis-findings) |
| `BUILD-SPOTBUGS-04` | `R030-BUILD` | C4 closure baseline: 77 reviewed findings in the same 19-module production-bytecode scope; one inherited filter had 71 exact selectors for 59 false positives + 18 policy-noise findings and no accepted legacy | Exception boundaries, 11 small local findings and all 44 mutable aliases were hardened with focused regressions. C4 also replaced compiler-generated lambda names with a stable exact class/field selector. Final clean + immediate repeat both passed 24/24 with 836 tests, 2 external SMB skips, 19 module report pairs + aggregate, 0 visible findings and 0 analyzer errors/missing classes. Later concurrency, Tika, nullable-path and SMB upload-plan follow-ups removed `SB04-016`, `SB04-018..019`, `SB04-029..030` and `SB04-033..039`, leaving 65 accepted identities / 61 selectors; focused and full-reactor evidence confirm the removals | AI agent | `verified` | [execution worknote](build-spotbugs-04-worknote.md) |
| `BUILD-SPOTBUGS-05` | `R030-BUILD` | Same 19 production JAR modules; 65 exact accepted identities (47 false positives + 18 policy noise), 61 generated narrow selectors, test bytecode still excluded | One raw analysis per module; root validates the structured baseline and concrete review-trigger catalog, then materializes the filter; late gate blocks new/stale/moved/metadata drift and analyzer/report/aggregate failures. Proposal-only tooling emits a non-accepting delta under `target/` and cannot populate acceptance metadata or edit the tracked baseline. Clean-checkout hardening gives raw and filtered executions independent directories and pins their discovery/output contract with three negative mutations. Latest 25-project full reactor passed with the complete module/aggregate report set, 65 raw / 0 visible findings and no analyzer errors/missing classes | AI agent | `verified` | [blocking-ratchet worknote](build-spotbugs-05-worknote.md) |
| `BUILD-PMD-06` | `R030-BUILD` | Bounded source-analysis evaluation over the same 19 production `src/main/java` roots as CPD; fail-closed 25-project disposition excludes root, TCK, tests, generated/vendor sources and other build-only POMs | P0 selected 34 exact rules; P1 proved Plugin `3.28.0` + PMD `7.26.0`, deterministic opt-in reports and 92 raw findings. P2 reviewed all occurrences: 3 bounded fixes, 7 semantic debt hotspots, 5 overlaps and 77 false-positive/policy-noise occurrences; no immediate defect, production change, suppression or baseline. Threshold evidence rejects default cyclomatic policy and hands P3 a 21-rule carry candidate, 2 deferred ownership-noisy rules and 11 drop/replacement candidates | AI agent | `in-progress` (`P0/P1/P2` completed; `P3` pending) | [PMD evaluation worknote](build-pmd-06-worknote.md) |
| `ING-10 / SB04-116` | `R030-QUAL` | Single-daemon ingestion startup, recovery/poller ordering, per-source-key execution and file/JDBC ledger transitions | Five checkpoints implemented the startup barrier, shared keyed guard, fresh-state recovery, monotonic CAS transitions, lifecycle health and exact-1 configuration guard; restart/watched-inbox and observability regressions passed, and `SB04-116` is absent from current reports | AI agent | `verified` | [ING-10 execution worknote](ing-10-ingestion-lifecycle-hardening.md) |

## 4. Deferred, blocked и queue decisions

| Work item/scope | Goal | State | Reason | Missing evidence/exit condition |
|---|---|---|---|---|
| Multi-daemon ingestion lease/fencing | `R030-QUAL` | `deferred` | Поддерживаемый deployment 0.3.0 остаётся single-daemon; process-local guard ING-10 не является cross-process ownership protocol | Concrete multi-daemon deployment requirement plus an explicit durable lease/fencing ownership decision |

## 5. Update rule

Matrix обновляется после каждого analysis или implementation work item.
`verified` требует goal-specific DoD; наличие merged PR само по себе
недостаточно.
