---
title: "0.3.0 code-health review ledger"
version: "0.3.0"
goal_id: "R030-QUAL"
status: "Baseline intake ready"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-QUAL — Review ledger

Contract: [R030-QUAL](../goals/R030-QUAL-code-health.md).

`BASE-INVENTORIES-09` создаёт только initial intake. Состояние `planned` ниже
означает, что scope известен и имеет baseline navigation signals, но ещё не
прошёл полный goal-specific review. Coverage, analyzer или package-size signal
сам по себе не является code-health finding.

## Module review status

| Module/capability | State | Reviewer/owner | Baseline navigation signals |
|---|---|---|---|
| `platform/platform-errors` | `planned` | `R030-QUAL` / `R030-LIB` | Minimal root exception; service-specific semantics must be separated from generic publication claims |
| `platform/platform-diagnostics` | `planned` | `R030-QUAL` / `R030-LIB` | Generic result/sink mechanics coexist with IOC capability catalogs and `platform-errors` dependency |
| `platform/platform-etl` | `planned` | `R030-QUAL` / `R030-LIB` | Framework-free ETL kernel; genericity is constrained by diagnostics/error closure |
| `platform/platform-events` | `planned` | `R030-QUAL` / `R030-ARCH` | Thin local publish-only control-event API; anti-broker boundary is supported |
| `platform/platform-concurrency` | `planned` | `R030-QUAL` / `R030-LIB` | Framework-free keyed execution; strongest low-dependency publication candidate |
| `platform/platform-observability` | `planned` | `R030-QUAL` / `R030-LIB` | Generic logging mechanics coexist with IOC actions/fields and ETL observer dependency |
| `platform/platform-diagnostics-logging` | `planned` | `R030-QUAL` / `R030-LIB` | Bridge inherits diagnostics and observability coupling; not an independent first publication unit |
| `core/ioc-domain` | `planned` | `R030-QUAL` | Pure domain boundary passes; 24 missed branches and accepted PIT/invariant pilot scope |
| `core/ioc-application` | `planned` | `R030-QUAL` / `R030-RETIRE` | Largest inward module; 263 missed branches plus one proven orphaned `StableArtifactId` value type |
| `core/ioc-application-tck` | `planned` | `R030-QUAL` / `R030-TEST` | Test-support artifact is executed by three adapter consumers; outside production coverage denominator |
| `adapters/adapter-regex-re2j` | `planned` | `R030-QUAL` / `R030-TEST` | Supported JDK fallback has no behavioral consumer; formal cross-goal finding below |
| `adapters/adapter-psl` | `planned` | `R030-QUAL` | Small focused adapter; no initial exceptional signal |
| `adapters/adapter-source-tika` | `planned` | `R030-QUAL` / `R030-BUILD` | Parser SPI is dynamic dependency evidence; adapter corpus owns supported formats |
| `adapters/adapter-sink-csv` | `planned` | `R030-QUAL` / `R030-BUILD` | 132 missed branches, one dependency candidate and stale active POM description |
| `adapters/adapter-manifest-json-jackson` | `planned` | `R030-QUAL` / `R030-TEST` | Strict manifest v1 is an external wire contract; small missed denominator |
| `adapters/adapter-store-jdbc` | `planned` | `R030-QUAL` | 167 missed branches; migration, CAS, recovery and ledger semantics are high-risk review areas |
| `adapters/adapter-transport-smb` | `planned` | `R030-QUAL` / `R030-TEST` | 117 missed branches and external-path concentration; live fixture unavailable |
| `adapters/adapter-ingest` | `planned` | `R030-QUAL` / `R030-TEST` | File lifecycle/retry/recovery plus wait-bearing test paths |
| `adapters/adapter-cli-picocli` | `planned` | `R030-QUAL` / `R030-TEST` | CLI/exit codes are external contract; health/status mapping has 87 missed branches |
| `bootstrap/ioc-app` | `planned` | `R030-QUAL` / `R030-ARCH` | 67 production classes remain in one root package; config/health/composition hotspots total 359 missed branches |

Common evidence:

- [baseline](baseline.md) for module graph, build and high-level signals;
- [test-quality ledger](test-quality-ledger.md) for coverage/risk navigation;
- [build-quality ledger](build-quality-ledger.md) for warning/dependency
  candidates;
- [compatibility ledger](compatibility-ledger.md) for protected external
  surfaces.

## Findings

| ID | Module/capability | Category | Severity/risk | Summary | Disposition | Owner | Work item |
|---|---|---|---|---|---|---|---|
| `QUAL-BOOT-1` | `bootstrap/ioc-app` | responsibility/cohesion, navigation | Medium | 67 production classes share `com.iocextractor.bootstrap` with no child package; composition remains one Maven module but internal navigation/ownership needs capability grouping | `fix-now` through architecture analysis | `R030-ARCH` | Bootstrap package-map analysis |
| `QUAL-REGEX-1` | `adapter-regex-re2j` + bootstrap selection | testability, compatibility | Medium | Documented `ioc.engine=jdk` fallback has no common behavioral contract or composition-selection test | `fix-now` | `R030-TEST` | `TEST-REGEX-03` |
| `QUAL-CSV-1` | `adapter-sink-csv/pom.xml` | documentation drift | Low | Active Maven description still calls the module an `IocSink` implementation although the one-phase sink was retired | `fix-now` in module documentation slice | `R030-QUAL` / `R030-DOC` | CSV adapter metadata alignment |

Categories include:

- responsibility/cohesion;
- state/invariant;
- error/resource handling;
- concurrency;
- dependency/boundary;
- duplication;
- performance smell;
- testability;
- documentation drift.

## Finding detail template

### `QUAL-<SCOPE>-<N>` — Title

- **Scope:**
- **Category:**
- **Observed behavior:**
- **Evidence:**
- **Risk:**
- **Dependencies/public API:**
- **Disposition:** `fix-now | debt | accepted | not-applicable`
- **Rationale:**
- **Acceptance criteria:**
- **Tests/verification:**
- **Related goals:**
- **Owner:**

## Duplication detail

Для duplicate finding дополнительно:

- detector/manual source, включая CPD report reference;
- occurrences;
- shared knowledge/behavior;
- semantic differences;
- reason to change;
- divergence risk;
- minimal solution boundary;
- visibility/dependency impact.

## Completed work items

| Work item | Finding(s) | PR/commit | Verification | Behavior change | Matrix updated |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

## Deferred/accepted summary

| Finding | Disposition | Rationale | Owner | Revisit condition |
|---|---|---|---|---|
| Raw Maven dependency findings | Not yet promoted to code findings | Bytecode analysis contains starter/SPI/test-stack noise; semantic validation remains in `R030-BUILD` | `R030-BUILD` | Promote only a coordinate with static, resource and dynamic evidence |
| Coverage hotspots | Navigation only | Missed branches do not identify whether behavior is valuable, infeasible, external or dead | Module owner / `R030-TEST` | Convert during risk-based module review |
| Duplication | Await CPD + manual review | No repository-wide CPD evidence exists yet; repeated tokens are not shared knowledge by default | `R030-BUILD` / `R030-QUAL` | Triage calibrated CPD clusters semantically |
