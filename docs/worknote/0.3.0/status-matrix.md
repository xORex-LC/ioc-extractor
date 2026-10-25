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
| `R030-DATA` | `verified` | [TTL evidence](data-ttl-01/evidence.md), [export-slot correction](data-ttl-01/export-slot-correction.md), [TTL release contract](data-ttl-01/release-contract.md), [import architecture](dataframe-import/architecture-project.md), [import P0 evidence](dataframe-import/p0-evidence.md), [import P1 evidence](dataframe-import/p1-evidence.md), [import P2 evidence](dataframe-import/p2-evidence.md), [import P3 evidence](dataframe-import/p3-evidence.md), [import P4 evidence](dataframe-import/p4-evidence.md), [import P5 evidence](dataframe-import/p5-evidence.md), [import P6 evidence](dataframe-import/p6-evidence.md), [import P7 evidence](dataframe-import/p7-evidence.md), [import P8 evidence](dataframe-import/p8-evidence.md), [import P9 evidence](dataframe-import/p9-evidence.md), [aggregate evidence](ioc-aggregate/evidence.md), [ADR-0024](../../ADR/0024-managed-dataframe-import.md), [ADR-0030](../../ADR/0030-registered-observation-order-for-artifact-fields.md) | TTL, managed import and `DATA-AGGREGATE-01` are implemented and qualified. Aggregate P0-P7 closed on `71296e2e`; live activation and systemd acceptance are recorded on `130d0898`. External ADR-0025 H5 breadth remains explicitly deferred to `OPS-8`, is not reported as a pass, and does not extend support to unqualified SMB families |
| `R030-BUILD` | `verified` | [build-quality ledger](evidence/build-quality-ledger.md), [closure PR #10](https://github.com/xORex-LC/ioc-extractor/pull/10) | Required project-owned statuses are active on `main` and `release-0.3.0`; all five release-branch checks passed under the active policy in run `34679847573` |
| `R030-TEST` global controls | `verified` | [test-quality ledger](evidence/test-quality-ledger.md) | All global work items, including separate out-of-reactor Central and GitHub Packages consumers in run `34233605995`, are verified. Applicable per-module hardening cells remain independently tracked below |
| `R030-SEC` | `in-progress` | Dependency remediation is qualified on exact commit `3307d1e2`: CI and Dependency Security runs passed with 0 unsuppressed findings | [Dependency security evidence](evidence/dependency-security.md); remaining repository, workflow and publication-security gap analysis |
| `R030-LIB` | `verified` | [shared-code inventory](evidence/shared-code-inventory.md), [LIB-1 hardening](lib-1-concurrency-worknote.md) | `ioc-platform-concurrency:0.3.0-rc.1` was published from the immutable validated bundle and separately consumed from Central and GitHub Packages in protected run `34233605995`; logging `0.3.0-rc.1` is separately published/qualified in run `39455105300` ([release record](libs/lib-3-logging-design.md#protected-publication-record--2026-10-21)); events core/adapter `0.3.0-rc.1` are separately published and qualified in runs `40160627532` / `40162675262` ([record](libs/lib-5-events-design.md#protected-publication-record--2026-10-25)) |
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
| `platform/platform-concurrency` | `in-progress` | `planned` | `in-progress` | `in-progress` | `in-progress` |
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
| `DATA-AGGREGATE-01` | `R030-DATA` | Новый самостоятельный IOC aggregate artifact; обновление name при совпадении остальных public fields | P0-P7 реализованы и квалифицированы: самостоятельный артефакт и export profile, managed import, единый dataframe-owned порядок наблюдений для file/JDBC ledger, конфигурируемые occurrence/write policies, schema v11, переходные операции и диагностика. Проверены fresh/upgrade/restart/restore, оба daemon ledger, import, 100k/250k duplicate-heavy load и реальная systemd-активация; артефакт стартует пустым без backfill | AI agent + user review | `verified` | [рабочий комплект](ioc-aggregate/README.md), [evidence](ioc-aggregate/evidence.md), [release contract](ioc-aggregate/release-contract.md), [implementation plan](ioc-aggregate/implementation-plan.md), [verification matrix](ioc-aggregate/verification-matrix.md), [ADR-0030](../../ADR/0030-registered-observation-order-for-artifact-fields.md) |
| `DATA-TTL-01` | `R030-DATA` | Canonical record expiry, stable sparse reusable export slots, revision-significant identical delivery and bounded idle lifecycle runtime | P0–P9 implementation, automated evidence and packaged qualification complete; lifecycle-owned dataframe schemas through v6 and service foundation v8; combined repository schemas are v9/v9 after DATA-IMPORT-01 migrations. Final committed-HEAD gate passed on `b3aee0a3` with `verify.fresh=true` | AI agent + user review | `verified` | [evidence](data-ttl-01/evidence.md), [export-slot correction](data-ttl-01/export-slot-correction.md), [implementation plan](data-ttl-01/implementation-plan.md), [ADR-0020](../../ADR/0020-canonical-record-expiration-lifecycle.md), [ADR-0021](../../ADR/0021-stable-reusable-export-slots.md), [ADR-0022](../../ADR/0022-revision-significant-identical-export.md), [ADR-0023](../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md) |
| `DATA-IMPORT-01` | `R030-DATA` | Managed local/SMB structured dataframe deliveries into SQLite truth; versioned recognition/mapping, active-only patch, atomic promotion and recovery | P0-P9 implementation, 100k/1M load, fairness, local/live SMB baseline, exact-v0.2/fresh packaged qualification and final committed-HEAD gate complete. ADR-0025 H1-H4 are implemented; unavailable H5 two-identity live breadth is explicitly deferred to `OPS-8`, not reported as a pass, and managed SMB import stays disabled pending deployment qualification | AI agent + user review | `verified` (`H5 breadth deferred`) | [P0 evidence](dataframe-import/p0-evidence.md), [P1 evidence](dataframe-import/p1-evidence.md), [P2 evidence](dataframe-import/p2-evidence.md), [P3 evidence](dataframe-import/p3-evidence.md), [P4 evidence](dataframe-import/p4-evidence.md), [P5 evidence](dataframe-import/p5-evidence.md), [P6 evidence](dataframe-import/p6-evidence.md), [P7 evidence](dataframe-import/p7-evidence.md), [P8 evidence](dataframe-import/p8-evidence.md), [P9 evidence](dataframe-import/p9-evidence.md), [architecture](dataframe-import/architecture-project.md), [release contract](dataframe-import/release-contract.md), [implementation plan](dataframe-import/implementation-plan.md), [verification matrix](dataframe-import/verification-matrix.md), [ADR-0024](../../ADR/0024-managed-dataframe-import.md), [ADR-0025](../../ADR/0025-managed-import-smb-service-namespace-and-terminal-retention.md) |
| `BUILD-SPOTBUGS-01` | `R030-BUILD` | 19 production runtime JAR modules; fail-closed 25-project disposition registry keeps root/build-support POMs and reusable test-contract `ioc-application-tck` explicitly outside bytecode scope | Report-only tool introduction, per-module reports, reactor aggregate and 118-finding baseline; root `validate` checks scope before child builds, late report integrity checks outputs, and a synthetic-reactor matrix protects both contracts | AI agent | `verified` | [SpotBugs rollout](evidence/build-quality-ledger.md#spotbugs-rollout) |
| `BUILD-CPD-02` | `R030-BUILD` | Fail-closed 25-project disposition registry; one repository-wide report over all 851 current Java source paths in 19 production modules; TCK, tests, generated/vendor sources and build-support explicitly excluded | PMD CPD `minimumTokens=75`; the adoption snapshot had 11 raw matches / 10 semantic findings. Post-import requalification reviewed all 26 then-current groups and removed five bounded knowledge duplicates, leaving 21 visible groups with explicit retained/deferred rationale. Follow-up exact group-count ratchet blocks any increase or decrease pending semantic review and same-change snapshot update | AI agent | `verified` | [CPD findings and calibration](evidence/build-quality-ledger.md#pmd-cpd-findings) |
| `BUILD-DEPS-03` | `R030-BUILD` | Maven bytecode dependency analysis over all 20 functional JAR modules, including reusable TCK; root and four build-only POMs explicitly observed as plugin-skipped | Plugin `3.11.0` pinned; 14 direct source/POM mismatches corrected; residual `56 / 34 / 12` findings semantically classified; blocking lifecycle adoption deferred and an opt-in report profile retained without suppressions | AI agent | `verified` | [Dependency-analysis disposition](evidence/build-quality-ledger.md#maven-dependency-analysis-findings) |
| `BUILD-SPOTBUGS-04` | `R030-BUILD` | C4 closure baseline: 77 reviewed findings in the same 19-module production-bytecode scope; one inherited filter had 71 exact selectors for 59 false positives + 18 policy-noise findings and no accepted legacy | Exception boundaries, 11 small local findings and all 44 mutable aliases were hardened with focused regressions. C4 also replaced compiler-generated lambda names with a stable exact class/field selector. Final clean + immediate repeat both passed 24/24 with 836 tests, 2 external SMB skips, 19 module report pairs + aggregate, 0 visible findings and 0 analyzer errors/missing classes. Later concurrency, Tika, nullable-path and SMB upload-plan follow-ups removed `SB04-016`, `SB04-018..019`, `SB04-029..030` and `SB04-033..039`, leaving 65 accepted identities / 61 selectors; focused and full-reactor evidence confirm the removals | AI agent | `verified` | [execution worknote](build-spotbugs-04-worknote.md) |
| `BUILD-SPOTBUGS-05` | `R030-BUILD` | Same 19 production JAR modules; current baseline has 116 exact accepted identities (85 false positives + 31 policy noise), 112 generated narrow selectors, test bytecode still excluded | One raw analysis per module; root validates the structured baseline and concrete review-trigger catalog, then materializes the filter; late gate blocks new/stale/moved/metadata drift and analyzer/report/aggregate failures. Proposal-only tooling emits a non-accepting delta under `target/` and cannot populate acceptance metadata or edit the tracked baseline. Post-import requalification reviewed every current category and location; cleanup-precedence fixes were pinned by tests and the aggregate remains 0 visible findings with no analyzer errors/missing classes | AI agent | `verified` | [blocking-ratchet worknote](build-spotbugs-05-worknote.md) |
| `BUILD-PMD-06` | `R030-BUILD` | Same fail-closed 25-project disposition and 19 production `src/main/java` roots as CPD; root, TCK, tests, generated/vendor sources and build-only POMs excluded | P3 adopted a reduced 22-rule policy with calibrated `CognitiveComplexity=16` and `ExcessiveParameterList.minimum=13`; a separate executable 3-rule watchlist preserves ownership/size signal without regular-CI adoption. Post-import requalification removed bounded defects; 21 policy findings in 16 files and 29 watchlist findings in 19 files remain visible. Follow-up enforcement gives 17 omitted-from-snapshot rules zero tolerance and requires exact counts for five reviewed advisory rules; no finding suppression/identity baseline. Harness: 7 happy / 56 negative scenarios | AI agent | `verified` (`P0/P1/P2/P3` completed; requalified 2026-08-31; ratcheted 2026-09-01) | [PMD adoption worknote](build-pmd-06-worknote.md) |
| `ING-10 / SB04-116` | `R030-QUAL` | Single-daemon ingestion startup, recovery/poller ordering, per-source-key execution and file/JDBC ledger transitions | Five checkpoints implemented the startup barrier, shared keyed guard, fresh-state recovery, monotonic CAS transitions, lifecycle health and exact-1 configuration guard; restart/watched-inbox and observability regressions passed, and `SB04-116` is absent from current reports | AI agent | `verified` | [ING-10 execution worknote](ing-10-ingestion-lifecycle-hardening.md) |

`LIB-1-HARDEN-01`: [ограниченный hardening concurrency](lib-1-concurrency-worknote.md)
проверен по подтверждённым текущим consumers (23 module tests, полный verify,
PMD policy/watchlist и docs). Это не полное закрытие
module cells и не publication admission.

## 4. Deferred, blocked и queue decisions

| Work item/scope | Goal | State | Reason | Missing evidence/exit condition |
|---|---|---|---|---|
| `DATA-IMPORT-01 / ADR-0025 H5 / OPS-8` | `R030-DATA` | `deferred` (`external qualification`) | Для 0.3.0 явно принят ограниченный support claim: недоступный live fixture с раздельными producer/service identities не считается pass и не блокирует закрытие repository/package scope; managed SMB import по умолчанию выключен | Перед заявлением поддержки конкретного SMB family и включением source проходит opt-in `SmbManagedImportHardeningContractTest`: producer denial, capability/missing-namespace, no-replace claim, reconnect, disposition и exact remote purge; недоступные дополнительные families остаются named skips |
| Multi-daemon ingestion lease/fencing | `R030-QUAL` | `deferred` | Поддерживаемый deployment 0.3.0 остаётся single-daemon; process-local guard ING-10 не является cross-process ownership protocol | Concrete multi-daemon deployment requirement plus an explicit durable lease/fencing ownership decision |

## 5. Update rule

Matrix обновляется после каждого analysis или implementation work item.
`verified` требует goal-specific DoD; наличие merged PR само по себе
недостаточно.

### LIB-1 publication preparation — 2026-09-07

Implemented ADR 0028, the GitHub namespace coordinate, standalone POM and
source/Javadoc packaging, publication recovery tooling and protected workflow.
The separate consumer passed against the local bundle. At this preparation
checkpoint, `R030-LIB` and `TEST-PUBLICATION-08` remained open pending real
signing/repository validation and cold consumption from Central and GitHub
Packages.

### LIB-1 protected validation — 2026-09-08

Tag `ioc-platform-concurrency-v0.3.0-rc.1` fixes commit `c0f0f12f`. Protected
run `34222437897` passed admission, full build, local standalone consumption,
owner-key signing and Central validation for deployment
`84d86a68-9402-4181-a587-772d004ad8c6`. The signed bundle is retained and
GitHub Packages was not contacted in validation mode. The next gate is an
explicitly approved recovery publication from that exact run/deployment,
followed by independent cold resolution and execution against Central and
GitHub Packages.

### LIB-1 protected publication — 2026-09-08

Partial run `34229094758` published Central but exposed an HTTP redirect defect
in GitHub Packages verification. Recovery fixes passed complete CI, and final
protected run
[`34233605995`](https://github.com/xORex-LC/ioc-extractor/actions/runs/34233605995)
reused the original signed bundle, confirmed exact bytes at both repositories
and passed two isolated cold consumers. This closes `R030-LIB` and the global
`TEST-PUBLICATION-08` control; it does not close the separate module-hardening
cells.

### Configuration library implementation admission — 2026-10-22

Configuration C1–C3 implements record-schema name/declaration inspection,
explicit migration observations, IOC integration and the executable closed-input
context recipe. [ADR 0046](../../ADR/0046-reusable-spring-boot-configuration-inspection.md)
accepts the ownership boundary. Maven, analyzer and coverage registries include
the adapter as a normal production module; their topology and fixed floors remain
unchanged. The [C3 checkpoint](libs/configuration-library-design.md#c3-build-and-documentation-checkpoint--2026-10-22)
records final local gates. C4 standalone POMs, dependency mediation, cold consumers
and separately authorized publication remain open for this component; the earlier
`R030-LIB` pilot closure does not qualify it automatically.


### Configuration C4 local publication qualification — 2026-10-24

The [C4 checkpoint](libs/configuration-library-design.md#c4-standalone-packaging-and-consumer-checkpoint--2026-10-24)
records the standalone POM, full default/application-BOM dependency graphs,
packaged inspection/migration and exact restricted-context guide, empty-cache
repository-routing negatives/control, and older component regressions. The CI
matrix and protected workflow accept the explicit `configuration` selector.
Existing signed releases and tags are unchanged. Protected validation/publication,
release-repository consumers and a deployed second service remain separate gates.

### Configuration protected publication and local recovery — 2026-10-24

PR 18 integrated configuration; protected validation `40087127800` and publication
`40088095516` reused one signed bundle and Central deployment. Version
`io.github.xorex-lc:ioc-config-spring-boot:0.3.0-rc.1` is published in both registries.
GitHub's cold consumer passed. The original Central consumer failed because of
same-URL repository filtering; `tee` concealed that failure from the job result.
A local cold Central consumer passed after correcting routing, with unchanged
release bytes. Two regressions cover routing and pipeline-failure propagation;
121 publication tests pass. Tooling integration and a protected CI recovery run
remain open, alongside the separate second-service deployment qualification.
The [release record](libs/configuration-library-design.md#protected-publication-record--2026-10-24)
retains identities and distinguishes the original failure from local recovery.

### LIB-5 implementation resumed and E1 qualification — 2026-10-24

The owner selected events extraction for future services, superseding its earlier
deferral. The [E1 checkpoint](libs/lib-5-events-design.md#e1-jdk-contract-qualification--2026-10-24)
retains the existing seven-type JDK API, precise metadata/publication/observation
contracts, recording limitations, 43 focused tests and strict Javadoc. The exact
consumer-guide example compiles/runs locally. No coordinate or dependency changes
in E1; E2 extracts the Spring bridge and qualifies the three known corrections.
E3–E5, standalone repository consumers and publication admission remain pending.
The configuration workflow-recovery changes are recorded separately and
need reviewed integration before extending publication tooling. ETL stays deferred.


### LIB-5 E2 explicit Spring adapter — 2026-10-24

The [E2 checkpoint](libs/lib-5-events-design.md#e2-explicit-spring-adapter--2026-10-24)
extracts reactor-local `ioc-events-spring`, migrates IOC composition and corrects
ARCH-4/OBS-7/OBS-8. Tests cover partial synchronous dispatch, real annotated-listener
Error propagation, observer/suppression boundaries and throwing fallback logging.
Service multicaster/error-handler/executor ownership is preserved. All quality
registries include the new JAR: 31 projects, 25 production groups, 24 local
coverage reports; one integration suite is added, with existing floors intact.
E3–E5 and any event registry publication remain pending.


### LIB-5 E3 standalone publication packaging — 2026-10-24

Both events modules now use `io.github.xorex-lc` in the reactor and distribution
POMs. Explicit `events`/`events-spring` selectors validate separate immutable
bundles; core remains dependency-free, and the adapter requires core at exactly
the same version plus Context 7.0.9 and SLF4J API 2.0.18. The report verifiers
admit both new groups and reject the old coordinates. Consumer-less components
cannot sign, publish or pass an empty consumer run; protected workflow choices
remain limited to the four previously qualified components. E4 owns cold
two-artifact consumers and repository routing; E5 and publication remain pending.
See the [E3 checkpoint](libs/lib-5-events-design.md#e3-standalone-publication-packaging--2026-10-24).

### LIB-5 E4 isolated consumers — 2026-10-25

Independent JDK/Spring projects now consume packaged artifacts from one selected
local repository with empty caches, exact guide sources and four/eight-file
hash/origin checks. The Spring fixture covers partial real listener delivery,
Error/observer boundaries and service-owned dispatch. Whole-graph checks,
application-BOM mediation, excluded/unavailable APIs, incompatible Spring and
missing-target/missing-core routing have explicit positive and negative oracles.
Regular CI requires both consumers; E5 still blocks signing/remote writes and
protected-workflow admission. No scope/floor or production-code change is made.
See the [E4 checkpoint](libs/lib-5-events-design.md#e4-isolated-packaged-consumers--2026-10-25).

### LIB-5 E5 release admission — 2026-10-25

The protected workflow admits both events selectors with separate tags/bundles.
Adapter admission requires a successful same-version core publication and both
repository-consumer receipts; exact core bytes are checked before privileged
review and retained for build, signing, remote consumption and partial recovery.
Recovery reuses both original bundles and rejects a replacement core-run input.
API/version ownership and Java 21 / Context 7.0.9 / SLF4J 2.0.18 support are
documented. No registry release occurs in this checkpoint; committed-candidate
CI and current security evidence precede a separately authorized publication.
See the [E5 checkpoint](libs/lib-5-events-design.md#e5-protected-release-admission--2026-10-25).

### LIB-5 protected publication — 2026-10-25

После слияния PR 19 опубликованы `ioc-platform-events:0.3.0-rc.1` и
`ioc-events-spring:0.3.0-rc.1` из исходного коммита `46c91441`. Оба артефакта
доступны из Central и GitHub Packages; отдельные consumer-проверки прошли в
запусках `40160627532` и `40162675262`. Публикация использовала исходные
подписанные файлы без пересборки и повторной подписи; адаптер сохранил тот же core.
Проверены фактические логи, manifests, хеши четырёх/восьми артефактов, источники
загрузки, plain/Boot BOM, отрицательные проверки зависимостей и маршрутов.
[Запись выпуска](libs/lib-5-events-design.md#protected-publication-record--2026-10-25)
содержит теги, deployment ID, хеши и сохранённые подтверждения. ETL остаётся
отложенным. Выпуск сервиса и проверка второго развёрнутого сервиса в эту работу
не входили; protected CI recovery конфигурационной библиотеки остаётся отдельно.
