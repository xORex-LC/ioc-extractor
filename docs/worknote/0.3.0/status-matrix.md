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
| `R030-DATA` | `verified` | [TTL evidence](data-ttl-01/evidence.md), [export-slot correction](data-ttl-01/export-slot-correction.md), [TTL release contract](data-ttl-01/release-contract.md), [import architecture](dataframe-import/architecture-project.md), [import P0 evidence](dataframe-import/p0-evidence.md), [import P1 evidence](dataframe-import/p1-evidence.md), [import P2 evidence](dataframe-import/p2-evidence.md), [import P3 evidence](dataframe-import/p3-evidence.md), [import P4 evidence](dataframe-import/p4-evidence.md), [import P5 evidence](dataframe-import/p5-evidence.md), [import P6 evidence](dataframe-import/p6-evidence.md), [import P7 evidence](dataframe-import/p7-evidence.md), [import P8 evidence](dataframe-import/p8-evidence.md), [import P9 evidence](dataframe-import/p9-evidence.md), [ADR-0024](../../ADR/0024-managed-dataframe-import.md) | Closed on `b3aee0a3`: packaged exact-v0.2/fresh qualification and fresh full-reactor gate passed. External ADR-0025 H5 breadth is explicitly deferred to `OPS-8`, is not a pass, and does not extend support to unqualified SMB families |
| `R030-BUILD` | `in-progress` | [build-quality ledger](evidence/build-quality-ledger.md) | Lifecycle separation, CI retention, coverage universe/integrity, ratchets and fixed floors are verified; Codecov is configured and non-required, but operational upload evidence and required build-status policy still remain |
| `R030-TEST` global controls | `in-progress` | [test-quality ledger](evidence/test-quality-ledger.md) | `TEST-LIFECYCLE-01` through `TEST-PILOTS-06` are verified; `TEST-CODECOV-07` implementation is committed and awaits its first upstream CI upload/status evidence |
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
| `adapters/adapter-csv` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-manifest-json-jackson` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-store-jdbc` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-transport-smb` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-ingest` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `adapters/adapter-cli-picocli` | `planned` | `planned` | `planned` | `planned` | `planned` |
| `bootstrap/ioc-app` | `planned` | `planned` | `planned` | `planned` | `planned` |

## 3. Work items

| Work item | Goal | Scope | Stage | Owner | State | Evidence |
|---|---|---|---|---|---|---|
| `DATA-TTL-01` | `R030-DATA` | Canonical record expiry, stable sparse reusable export slots, revision-significant identical delivery and bounded idle lifecycle runtime | P0–P9 implementation, automated evidence and packaged qualification complete; lifecycle-owned dataframe schemas through v6 and service foundation v8; combined repository schemas are v9/v9 after DATA-IMPORT-01 migrations. Final committed-HEAD gate passed on `b3aee0a3` with `verify.fresh=true` | AI agent + user review | `verified` | [evidence](data-ttl-01/evidence.md), [export-slot correction](data-ttl-01/export-slot-correction.md), [implementation plan](data-ttl-01/implementation-plan.md), [ADR-0020](../../ADR/0020-canonical-record-expiration-lifecycle.md), [ADR-0021](../../ADR/0021-stable-reusable-export-slots.md), [ADR-0022](../../ADR/0022-revision-significant-identical-export.md), [ADR-0023](../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md) |
| `DATA-IMPORT-01` | `R030-DATA` | Managed local/SMB structured dataframe deliveries into SQLite truth; versioned recognition/mapping, active-only patch, atomic promotion and recovery | P0-P9 implementation, 100k/1M load, fairness, local/live SMB baseline, exact-v0.2/fresh packaged qualification and final committed-HEAD gate complete. ADR-0025 H1-H4 are implemented; unavailable H5 two-identity live breadth is explicitly deferred to `OPS-8`, not reported as a pass, and managed SMB import stays disabled pending deployment qualification | AI agent + user review | `verified` (`H5 breadth deferred`) | [P0 evidence](dataframe-import/p0-evidence.md), [P1 evidence](dataframe-import/p1-evidence.md), [P2 evidence](dataframe-import/p2-evidence.md), [P3 evidence](dataframe-import/p3-evidence.md), [P4 evidence](dataframe-import/p4-evidence.md), [P5 evidence](dataframe-import/p5-evidence.md), [P6 evidence](dataframe-import/p6-evidence.md), [P7 evidence](dataframe-import/p7-evidence.md), [P8 evidence](dataframe-import/p8-evidence.md), [P9 evidence](dataframe-import/p9-evidence.md), [architecture](dataframe-import/architecture-project.md), [release contract](dataframe-import/release-contract.md), [implementation plan](dataframe-import/implementation-plan.md), [verification matrix](dataframe-import/verification-matrix.md), [ADR-0024](../../ADR/0024-managed-dataframe-import.md), [ADR-0025](../../ADR/0025-managed-import-smb-service-namespace-and-terminal-retention.md) |
| `BUILD-SPOTBUGS-01` | `R030-BUILD` | 19 production runtime JAR modules; fail-closed 25-project disposition registry keeps root/build-support POMs and reusable test-contract `ioc-application-tck` explicitly outside bytecode scope | Report-only tool introduction, per-module reports, reactor aggregate and 118-finding baseline; root `validate` checks scope before child builds, late report integrity checks outputs, and a synthetic-reactor matrix protects both contracts | AI agent | `verified` | [SpotBugs rollout](evidence/build-quality-ledger.md#spotbugs-rollout) |
| `BUILD-CPD-02` | `R030-BUILD` | Fail-closed 25-project disposition registry; one repository-wide report over all 851 current Java source paths in 19 production modules; TCK, tests, generated/vendor sources and build-support explicitly excluded | PMD CPD `minimumTokens=75`; the adoption snapshot had 11 raw matches / 10 semantic findings. Post-import requalification reviewed all 26 then-current groups and removed five bounded knowledge duplicates, leaving 21 visible groups with explicit retained/deferred rationale. Follow-up exact group-count ratchet blocks any increase or decrease pending semantic review and same-change snapshot update | AI agent | `verified` | [CPD findings and calibration](evidence/build-quality-ledger.md#pmd-cpd-findings) |
| `BUILD-DEPS-03` | `R030-BUILD` | Maven bytecode dependency analysis over all 20 functional JAR modules, including reusable TCK; root and four build-only POMs explicitly observed as plugin-skipped | Plugin `3.11.0` pinned; 14 direct source/POM mismatches corrected; residual `56 / 34 / 12` findings semantically classified; blocking lifecycle adoption deferred and an opt-in report profile retained without suppressions | AI agent | `verified` | [Dependency-analysis disposition](evidence/build-quality-ledger.md#maven-dependency-analysis-findings) |
| `BUILD-SPOTBUGS-04` | `R030-BUILD` | C4 closure baseline: 77 reviewed findings in the same 19-module production-bytecode scope; one inherited filter had 71 exact selectors for 59 false positives + 18 policy-noise findings and no accepted legacy | Exception boundaries, 11 small local findings and all 44 mutable aliases were hardened with focused regressions. C4 also replaced compiler-generated lambda names with a stable exact class/field selector. Final clean + immediate repeat both passed 24/24 with 836 tests, 2 external SMB skips, 19 module report pairs + aggregate, 0 visible findings and 0 analyzer errors/missing classes. Later concurrency, Tika, nullable-path and SMB upload-plan follow-ups removed `SB04-016`, `SB04-018..019`, `SB04-029..030` and `SB04-033..039`, leaving 65 accepted identities / 61 selectors; focused and full-reactor evidence confirm the removals | AI agent | `verified` | [execution worknote](build-spotbugs-04-worknote.md) |
| `BUILD-SPOTBUGS-05` | `R030-BUILD` | Same 19 production JAR modules; current baseline has 116 exact accepted identities (85 false positives + 31 policy noise), 112 generated narrow selectors, test bytecode still excluded | One raw analysis per module; root validates the structured baseline and concrete review-trigger catalog, then materializes the filter; late gate blocks new/stale/moved/metadata drift and analyzer/report/aggregate failures. Proposal-only tooling emits a non-accepting delta under `target/` and cannot populate acceptance metadata or edit the tracked baseline. Post-import requalification reviewed every current category and location; cleanup-precedence fixes were pinned by tests and the aggregate remains 0 visible findings with no analyzer errors/missing classes | AI agent | `verified` | [blocking-ratchet worknote](build-spotbugs-05-worknote.md) |
| `BUILD-PMD-06` | `R030-BUILD` | Same fail-closed 25-project disposition and 19 production `src/main/java` roots as CPD; root, TCK, tests, generated/vendor sources and build-only POMs excluded | P3 adopted a reduced 22-rule policy with calibrated `CognitiveComplexity=16` and `ExcessiveParameterList.minimum=13`; a separate executable 3-rule watchlist preserves ownership/size signal without regular-CI adoption. Post-import requalification removed bounded defects; 21 policy findings in 16 files and 29 watchlist findings in 19 files remain visible. Follow-up enforcement gives 17 omitted-from-snapshot rules zero tolerance and requires exact counts for five reviewed advisory rules; no finding suppression/identity baseline. Harness: 7 happy / 56 negative scenarios | AI agent | `verified` (`P0/P1/P2/P3` completed; requalified 2026-08-31; ratcheted 2026-09-01) | [PMD adoption worknote](build-pmd-06-worknote.md) |
| `ING-10 / SB04-116` | `R030-QUAL` | Single-daemon ingestion startup, recovery/poller ordering, per-source-key execution and file/JDBC ledger transitions | Five checkpoints implemented the startup barrier, shared keyed guard, fresh-state recovery, monotonic CAS transitions, lifecycle health and exact-1 configuration guard; restart/watched-inbox and observability regressions passed, and `SB04-116` is absent from current reports | AI agent | `verified` | [ING-10 execution worknote](ing-10-ingestion-lifecycle-hardening.md) |

## 4. Deferred, blocked и queue decisions

| Work item/scope | Goal | State | Reason | Missing evidence/exit condition |
|---|---|---|---|---|
| `DATA-IMPORT-01 / ADR-0025 H5 / OPS-8` | `R030-DATA` | `deferred` (`external qualification`) | Для 0.3.0 явно принят ограниченный support claim: недоступный live fixture с раздельными producer/service identities не считается pass и не блокирует закрытие repository/package scope; managed SMB import по умолчанию выключен | Перед заявлением поддержки конкретного SMB family и включением source проходит opt-in `SmbManagedImportHardeningContractTest`: producer denial, capability/missing-namespace, no-replace claim, reconnect, disposition и exact remote purge; недоступные дополнительные families остаются named skips |
| Multi-daemon ingestion lease/fencing | `R030-QUAL` | `deferred` | Поддерживаемый deployment 0.3.0 остаётся single-daemon; process-local guard ING-10 не является cross-process ownership protocol | Concrete multi-daemon deployment requirement plus an explicit durable lease/fencing ownership decision |

## 5. Update rule

Matrix обновляется после каждого analysis или implementation work item.
`verified` требует goal-specific DoD; наличие merged PR само по себе
недостаточно.
