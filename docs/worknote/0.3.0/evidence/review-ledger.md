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
| `QUAL-CPD-01` | bootstrap strict-configuration preflight | duplication, state/invariant | Medium | Config-path tokenization and reflective type helpers were duplicated between environment matching and unknown-key preflight | `fixed`: one characterized `IocConfigurationPropertyShape` model | `R030-QUAL` / `R030-BUILD` | Revisit only when configuration path grammar changes |
| `QUAL-CPD-02` | CLI + daemon ingest diagnostics | duplication, observability | Low | Extraction-result diagnostic fields are mapped twice across adapters | `debt`: boundary decision required | `R030-QUAL` | Diagnostic field mapping review |
| `QUAL-CPD-03` | bootstrap control-event listeners | duplication, observability | Low | Common event metadata is copied into MDC by three handlers, with handler-specific tail fields | `debt`: defer pending ECS helper boundary review | `R030-QUAL` / `R030-ARCH` | Event MDC mapping review |
| `QUAL-CPD-04` | application export fingerprinting | duplication, state/invariant | Medium | Multiple fingerprints independently implemented the same length-prefixed SHA-256 framing | `fixed`: `FingerprintFraming` owns the byte contract | `R030-QUAL` | Revisit on fingerprint framing/version change |
| `QUAL-CPD-05` | platform diagnostic code catalogs | duplication | Low | Enum implementations repeat interface boilerplate but own distinct category, impact and message semantics | `accepted`: retain | `R030-QUAL` | Revisit only on a third semantic change |
| `QUAL-CPD-06` | completed-slice filesystem catalog | duplication, resource handling | Low | Two list operations repeated safe directory traversal but returned and validated different projections | `fixed`: private traversal seam, separate validators/results retained | `R030-QUAL` | Revisit if traversal provenance or symlink policy changes |
| `QUAL-CPD-07` | SMB adapter session factories | duplication, resource/security | Medium | Two factories repeated connect, authentication and password-wiping setup | `fixed`: shared adapter-local setup, distinct returned owners retained | `R030-QUAL` / `R030-SEC` | Revisit on SMB authentication/session ownership change |
| `QUAL-CPD-08` | daemon executor lifecycles | duplication, concurrency | Medium | Two schedulers share graceful executor shutdown mechanics while owning different scheduling state | `debt`: defer to lifecycle/concurrency review | `R030-QUAL` / `R030-ARCH` | Scheduler shutdown review |
| `QUAL-CPD-09` | legacy ingestion ledger migration | duplication, retirement | Low | Legacy property parsing exists in the file ledger and JDBC importer across different migration owners | `accepted`: retain while both migration paths exist | `R030-RETIRE` | Revisit when legacy file ledger retires |
| `QUAL-CPD-10` | maintenance + slice-retention schedulers | duplication, lifecycle | Low | Two independent cadence owners share small start/stop boilerplate | `accepted`: retain | `R030-QUAL` | Revisit if lifecycle policy diverges or repeats again |
| `QUAL-CPD-11` | JDBC managed-import file digests | duplication, error handling | Low | Workspace sealing and canonical promotion repeated the same byte-level SHA-256 stream algorithm but require different operator diagnostics | `fixed`: package-private algorithm helper, caller-owned diagnostics retained | `R030-QUAL` / `R030-BUILD` | Revisit on digest algorithm/version change |
| `QUAL-CPD-12` | CSV managed-import reader entry points | duplication, resource handling | Low | Header-only and streaming-record entry points repeat the strict reader/decorator/parser stack inside one adapter | `debt`: retain local duplication until one helper preserves TWR ownership and distinct error/result contracts | `R030-QUAL` | CSV parser ownership/error-contract review |
| `QUAL-PMD-01` | `adapter-ingest/FileSourceMessageHandler.handle` | responsibility/cohesion, error handling | Medium | Hashing and ingestion retries, durable rejection and terminal diagnostic propagation share one control-flow boundary (cognitive 20) | `debt`: characterize before decomposition | `R030-QUAL` / `R030-TEST` | Ingest handler retry/rejection review |
| `QUAL-PMD-02` | `adapter-transport-smb/SmbFileTransport.publish` | responsibility/cohesion, resource handling | Medium | One method owns idempotency check, temporary upload, verification, replacement and cleanup for atomic publish | `debt`: preserve the remote commit protocol before extracting seams | `R030-QUAL` / `R030-TEST` | SMB atomic publish seam review |
| `QUAL-PMD-03` | `ioc-application/ExportService` construction | dependency/boundary, cohesion | Low | Overloaded construction exposes a broad export-orchestration collaborator set; two PMD occurrences describe the same seam | `debt`: review a cohesive construction bundle without hiding ports | `R030-QUAL` / `R030-ARCH` | Export orchestration construction review |
| `QUAL-PMD-04` | `ioc-application/IngestionService` construction | dependency/boundary, cohesion | Low | The fully wired service takes ten collaborators spanning source, ledgers, projection, events, diagnostics and keyed execution | `debt`: review collaborator grouping without weakening shared-guard ownership | `R030-QUAL` / `R030-ARCH` | Ingestion construction review |
| `QUAL-PMD-05` | `ioc-application/IngestionService.recoverIncomplete` | state/invariant, error handling | Medium | Ledger recovery and orphan processing share nested per-key failure translation in one recovery scan | `debt`: characterize recovery ordering and failure identity first | `R030-QUAL` / `R030-TEST` | Ingestion recovery flow review |
| `QUAL-PMD-06` | extraction service construction and pipeline factory | dependency/boundary, cohesion | Medium | Service constructor, static pipeline assembly and source-scoped factory expose one broad extraction wiring surface across three PMD occurrences | `debt`: seek one inward construction model without moving adapter concerns into core | `R030-QUAL` / `R030-ARCH` | Extraction construction model review |
| `QUAL-PMD-07` | `ioc-application/ArtifactPublishService` | responsibility/cohesion, state/invariant | Medium | Reconcile, discovery and execution remain cohesive but class-total complexity identifies a credible future saga-decomposition seam | `debt`: decompose only with ledger/idempotency characterization | `R030-QUAL` / `R030-TEST` | Artifact publish saga review |
| `QUAL-PMD-08` | `adapter-cli-picocli/EarlyCliLauncher.commandFactory` | responsibility/cohesion | Low | The explicit command-constructor registry has cognitive complexity 20 but keeps command availability and arguments locally auditable | `accepted`: retain the explicit registry | `R030-QUAL` | Revisit if command registration gains conditional policy or another registry copy |
| `QUAL-PMD-09` | `adapter-csv/CsvProcessedImportRowPreparer.indicators` | state/invariant, error handling | Low | A linear validation chain reports independent row-contract failures and reaches the configured mapper only after all guards | `accepted`: retain auditable guards | `R030-QUAL` / `R030-TEST` | Revisit when validation branches or failure aggregation semantics change |
| `QUAL-PMD-10` | `adapter-ingest/LocalImportTerminalStore.archive` | state/invariant, error handling | Medium | Four source/target terminal-state combinations implement idempotency and ambiguity rejection, producing high NPath | `debt`: characterize the state table before decomposition | `R030-QUAL` / `R030-TEST` | Import terminal-state transition review |
| `QUAL-PMD-11` | `adapter-store-jdbc/JdbcCanonicalImportWriter.planBranch` | responsibility/cohesion, state/invariant | High | Identity matching, merge resolution and lifecycle mutation planning share one transactional branch planner (cognitive 23, NPath 1574) | `debt`: characterize branch outcomes and transaction invariants first | `R030-QUAL` / `R030-TEST` | Canonical import branch-planning review |
| `QUAL-PMD-12` | `adapter-transport-smb/SmbExceptionMapper.classify` | error handling, security | Low | Ordered cause-chain and status-token classification is intentionally centralized and exhaustive, producing cognitive 18 | `accepted`: retain ordered classifier | `R030-QUAL` / `R030-SEC` | Revisit on new SMB error kinds or escaped misclassification |
| `QUAL-PMD-13` | `bootstrap/AppConfig` composition wiring | dependency/boundary | Low | A bean factory exposes 15 explicit dependencies at the composition root | `accepted`: explicit ports are clearer than an untyped dependency bag | `R030-ARCH` | Revisit when a cohesive typed construction boundary emerges |
| `QUAL-PMD-14` | `bootstrap/ManagedDataframeImportRuntime` construction | dependency/boundary, cohesion | Medium | The lifecycle coordinator receives 15 collaborators spanning detection, recovery, retention and observability | `debt`: seek cohesive typed runtime components without hiding lifecycle owners | `R030-QUAL` / `R030-ARCH` | Managed-import runtime construction review |
| `QUAL-PMD-15` | canonical lifecycle activation/reconciliation services | state/invariant, error handling | Medium | Explicit resumable phase and bounded-reconcile state machines reach the cognitive threshold | `debt`: preserve durable checkpoint and retry semantics before extracting phases | `R030-QUAL` / `R030-TEST` | Lifecycle state-machine characterization |
| `QUAL-PMD-16` | `ioc-application/IngestionService.processClaimed` | state/invariant, error handling | Medium | The guarded ingestion saga retains claim/run/failure/finalization precedence in one NPath-256 method | `debt`: characterize every primary/secondary failure path before decomposition | `R030-QUAL` / `R030-TEST` | Claimed-ingestion saga review |

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
| CPD-retained clusters | Accepted | Diagnostic enums, legacy migration parsing and independent maintenance schedulers have different owners/reasons to change; token equality alone does not justify abstraction | `R030-QUAL` / `R030-RETIRE` | Revisit on the finding-specific conditions above |
| CPD debt clusters | Debt intake | Three original candidates remain deferred (`QUAL-CPD-02`, `03`, `08`); `01`, `04`, `06`, `07` are fixed and retained clusters remain intentional | `R030-QUAL` / related goals | Schedule only with the finding-specific legal boundary and characterization |
| PMD complexity hotspots | Debt intake | The seven P2 hotspots remain historical intake; post-import requalification added characterized import/lifecycle seams and explicitly accepted auditable registry/guard/classifier/composition shapes. Metrics authorize review, not mechanical extraction | `R030-QUAL` / `R030-ARCH` / `R030-TEST` | Schedule only the finding-specific debt entries with characterization; accepted shapes reopen on their stated trigger |
