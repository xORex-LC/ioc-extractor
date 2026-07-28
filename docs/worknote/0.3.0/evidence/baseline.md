---
title: "0.3.0 baseline evidence"
version: "0.3.0"
goal_id: "R030-BASE"
status: "In progress"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-BASE — Baseline evidence

Contract: [R030-BASE](../goals/R030-BASE-baseline.md).

## Work items

| Work item | State | Result/next gate |
|---|---|---|
| `BASE-SNAPSHOT-00` | `verified` | Clean starting revision selected |
| `BASE-ENV-01` | `verified` | Revision and execution environment captured |
| `BASE-REACTOR-02` | `verified` | Module graph and dependency inventory captured |
| `BASE-VERIFY-03` | `verified` | Fresh clean reactor verification captured |
| `BASE-TESTS-04` | `verified` | Six analysis gates captured; findings handed to `R030-TEST`; proceed to `BASE-COVERAGE-05` |
| `BASE-COVERAGE-05` | `verified` | JaCoCo report-only baseline captured; branch gaps handed to `R030-TEST`; proceed to `BASE-QUALITY-06` |
| `BASE-QUALITY-06` | `verified` | Warning, dependency and existing-control inventory captured; actionable signals handed to `R030-BUILD` and `R030-TEST`; proceed to `BASE-RUNTIME-07` |
| `BASE-RUNTIME-07` | `planned` | Representative performance/resource measurements |
| `BASE-CONTRACTS-08` | `planned` | Compatibility and consumer obligations |
| `BASE-INVENTORIES-09` | `planned` | Initial hardening inventories |
| `BASE-CLOSE-10` | `planned` | Evidence consolidation and goal closure |

## Revision и environment

| Field | Value |
|---|---|
| Branch | `release-0.3.0` |
| Commit | `fc4bcddf44dd6ed3d2d57f3a1167ec1e18db9fef` |
| Maven revision | `0.3.0-SNAPSHOT` |
| Released comparison tag | `v0.2.0` (`ad255040e73f589cb0b1fcab3581d836699e1888`) |
| JDK | Eclipse Adoptium `21.0.11` |
| Maven | Maven Wrapper, Apache Maven `3.9.9` |
| OS | Ubuntu `24.04.3 LTS`, Linux `6.6.87.2-microsoft-standard-WSL2`, `x86_64`/`amd64` |
| Locale/encoding | `en` / UTF-8 |
| Captured at | `2026-07-27T21:11:13+08:00` |

## Commands

| Command | Exit | Evidence/artifact | Notes |
|---|---:|---|---|
| `make context` | 0 | Inline `BASE-ENV-01` capture | Clean `fc4bcdd`; branch is one commit ahead of upstream; prior successful verification is stale |
| `make doctor-core` | 0 | Inline `BASE-ENV-01` capture | Bash, Java, Git, Make and Maven Wrapper checks passed |
| `./mvnw --version` | 0 | Inline `BASE-ENV-01` capture | Maven `3.9.9`; Eclipse Adoptium Java `21.0.11`; UTF-8 |
| `git rev-parse v0.2.0` | 0 | Inline `BASE-ENV-01` capture | Resolves released comparison tag to `ad255040e73f589cb0b1fcab3581d836699e1888` |
| `./mvnw -B -ntp dependency:tree -Dincludes=com.iocextractor -DoutputType=text` | 0 | Module table below | Reactor resolved successfully; no Maven dependency cycle |
| `git ls-files <module>/src/main <module>/src/test` | 0 | File counts below | Counts tracked files only; `target/` and untracked local files are excluded |
| `make clean` | 0 | Maven reactor summary | Removed Maven build outputs; wall-clock `0.494 s` |
| `make verify` | 0 | `.dev/state/last-verify.env`; `*/target/surefire-reports/TEST-*.xml` | Official `./mvnw -B -ntp -T 1C verify`; wall-clock `39.210 s` |
| `/usr/bin/time make verify` | 0 | `.dev/state/last-verify.env`; current-reactor Surefire XML | Warm-output full test run on `f4cdd06`; Maven wall `34.758 s`, process real `35.74 s` |
| `make verify` | 0 | `.dev/state/last-verify.env`; current-reactor Surefire XML | `BASE-TESTS-04` consolidation verification on `5a746bb`; Maven wall `33.305 s` |
| `make clean && /usr/bin/time make verify` | 0 | `build-support/coverage-report/target/site/jacoco-aggregate/`; module-local `target/site/jacoco/` | Clean report-only JaCoCo run on the instrumentation worktree based on `9aa8d1f`; Maven wall `57.585 s`, process real `58.74 s` |
| `make clean && /usr/bin/time make verify` | 0 | Console capture; `.dev/state/last-verify.env`; reactor reports | Clean quality-signal run on `a0f2235`; Maven wall `57.263 s`, process real `58.39 s`, max RSS `1784708 KiB` |
| `./mvnw -B -ntp -T 1C verify dependency:analyze-only` | 0 | Console capture; [build-quality ledger](build-quality-ledger.md#baseline-maven-dependency-analysis) | Maven Dependency Plugin `3.9.0`; full lifecycle is required so internal reactor artifacts are available; Maven wall `47.745 s` |
| `./mvnw -B -ntp -T 1 -DskipTests verify dependency:analyze-only` | 0 | Non-interleaved module mapping in the [build-quality ledger](build-quality-ledger.md#baseline-maven-dependency-analysis) | 68 used-undeclared, 36 declared-unused and 12 non-test-scoped/test-only coordinate occurrences; Maven wall `4.671 s` |
| `./mvnw -B -ntp -T 1C dependency:tree -Dverbose` | 0 | Console capture; quality report below | One mediated version conflict; dependency convergence is not currently enforced |

### Fresh verification summary

The clean verification ran on commit
`30b50e260432e966bf2f669dd7c0d1524717cbed` and finished at
`2026-07-27T14:34:03Z`. `make context` immediately after the run reported
`verify.result=passed`, the same `verify.commit`, and `verify.fresh=true`.

This evidence commit differs from the baseline subject `fc4bcdd` only through
the non-runtime release-planning changes already enumerated in the module
inventory. No POM, Java source, test source or runtime configuration changed.

Results:

- all 21 reactor projects completed with `SUCCESS`;
- Maven Enforcer, ArchUnit/boundary tests, documentation/catalog consistency
  tests and the synthetic golden/E2E tests passed;
- 171 Surefire suites in the declared reactor modules reported 781 tests:
  0 failures, 0 errors and 2 skipped;
- both skipped tests belong to `SmbChangeNotifyContractTest` and require the
  absent external-fixture property `ioc.smb.contract`; they are recorded as
  unavailable evidence, not as passed tests;
- the Spring Boot-repackaged `ioc-app-0.3.0-SNAPSHOT.jar` was produced
  (`104632574` bytes in this environment).

Non-failing warning signals observed in the console are retained for
`BASE-QUALITY-06`: an SLF4J no-provider warning on isolated module test
classpaths, Mockito inline-mock-maker self-attachment, dynamic Byte Buddy agent
loading and the related JVM class-sharing warning. No warning was suppressed or
fixed in this work item.

The earlier repository-wide report count (`172` suites / `782` cases) included
one ignored, stale Surefire XML file under the removed, non-reactor
`adapters/adapter-lookup-csv/target/` directory. `make clean` cleans the current
reactor, not build output left behind by a module that no longer participates in
it. Test evidence is therefore scoped to modules declared by the root POM; raw
`target/` discovery across arbitrary repository paths is not an accepted
measurement method.

### `BASE-TESTS-04` closure

All six planned analysis gates are captured:

1. tracked-source and Surefire discovery reconciliation;
2. observed unit/component, integration, contract, architecture, publication
   and E2E lifecycle classification;
3. tag, condition, skip, retry and CI-selection inventory;
4. full-reactor and per-suite duration baseline;
5. bounded-wait, isolation and selected-suite repeat analysis;
6. coarse behavioral-effectiveness and consumer-gap review.

The consolidation verification on evidence commit `5a746bb` passed all 21
reactor projects. A declared-reactor recount confirmed 171 suites and 781 cases,
with 0 failures, 0 errors and the same 2 unavailable external SMB cases.

The gate records dispositions rather than remediating findings. In particular,
the JDK regex-engine contract gap, unbounded failure-path waits, lifecycle/tag
separation, scheduled stability pilot and external SMB evidence remain
`R030-TEST` work. Coverage is intentionally not inferred from suite or assertion
counts and is the next independent baseline gate, `BASE-COVERAGE-05`.

## Module/dependency inventory

Captured at `2026-07-27T21:21:22+08:00`. The common group is
`com.iocextractor`; every child inherits version `0.3.0-SNAPSHOT`. The reactor
contains one parent/aggregator and 20 child modules, all with `jar` packaging.
Only `bootstrap/ioc-app` is runnable and Spring Boot-repackaged.

The dependency command was run on evidence commit `9fefa5a`. Its diff from the
subject revision `fc4bcdd` contains only `.gitignore`, `docs/README.md` and the
tracked release worknotes; no POM, production source, test source or runtime
configuration changed. The graph and counts therefore describe the selected
baseline subject.

| Artifact/module | Packaging | Direct project dependencies | Production files | Test files | Notes |
|---|---|---|---:|---:|---|
| `ioc-extractor-parent` (root) | `pom` | — | 0 | 0 | Aggregator, dependency/plugin management and build policy |
| `ioc-platform-errors` (`platform/platform-errors`) | `jar` | — | 2 (1 Java) | 0 | Root unchecked error abstraction |
| `ioc-platform-diagnostics` (`platform/platform-diagnostics`) | `jar` | `ioc-platform-errors` | 46 (40 Java) | 10 (10 Java) | Diagnostic contracts and catalogs |
| `ioc-platform-etl` (`platform/platform-etl`) | `jar` | `ioc-platform-diagnostics`, `ioc-platform-errors` | 12 (11 Java) | 2 (2 Java) | Framework-free ETL kernel |
| `ioc-platform-events` (`platform/platform-events`) | `jar` | — | 8 (8 Java) | 3 (3 Java) | Thin local control-event contracts |
| `ioc-platform-concurrency` (`platform/platform-concurrency`) | `jar` | — | 10 (10 Java) | 2 (2 Java) | Keyed concurrency primitives |
| `ioc-platform-observability` (`platform/platform-observability`) | `jar` | `ioc-platform-etl` | 15 (13 Java) | 6 (6 Java) | Logging and pipeline observation |
| `ioc-platform-diagnostics-logging` (`platform/platform-diagnostics-logging`) | `jar` | `ioc-platform-diagnostics`, `ioc-platform-observability` | 5 (4 Java) | 4 (4 Java) | Diagnostics-to-logging bridge |
| `ioc-domain` (`core/ioc-domain`) | `jar` | — | 44 (37 Java) | 5 (5 Java) | Pure IOC domain |
| `ioc-application` (`core/ioc-application`) | `jar` | `ioc-domain`, `ioc-platform-etl`, `ioc-platform-diagnostics`, `ioc-platform-errors`, `ioc-platform-events` | 210 (192 Java) | 38 (38 Java) | Use cases, inward ports and pipeline stages |
| `ioc-application-tck` (`core/ioc-application-tck`) | `jar` | `ioc-application` | 4 (2 Java) | 0 | Reusable adapter contract-test kit |
| `ioc-adapter-regex-re2j` (`adapters/adapter-regex-re2j`) | `jar` | `ioc-domain` | 3 (2 Java) | 0 | Regex-engine adapter |
| `ioc-adapter-psl` (`adapters/adapter-psl`) | `jar` | `ioc-domain` | 2 (1 Java) | 1 (1 Java) | Public Suffix List adapter |
| `ioc-adapter-source-tika` (`adapters/adapter-source-tika`) | `jar` | `ioc-application`, `ioc-platform-diagnostics`, `ioc-platform-observability` | 2 (1 Java) | 3 (3 Java) | Source-reader adapter |
| `ioc-adapter-sink-csv` (`adapters/adapter-sink-csv`) | `jar` | `ioc-application`, `ioc-domain`, `ioc-platform-errors`, `ioc-platform-diagnostics`, `ioc-platform-observability` | 39 (38 Java) | 10 (10 Java) | CSV preparation/projection adapter |
| `ioc-adapter-manifest-json-jackson` (`adapters/adapter-manifest-json-jackson`) | `jar` | `ioc-application`, `ioc-platform-errors` | 3 (2 Java) | 1 (1 Java) | JSON manifest adapter |
| `ioc-adapter-store-jdbc` (`adapters/adapter-store-jdbc`) | `jar` | `ioc-application`, `ioc-platform-errors`, `ioc-platform-diagnostics`, `ioc-platform-observability`; `ioc-application-tck` (test) | 42 (31 Java) | 15 (15 Java) | SQLite/JDBC storage adapter |
| `ioc-adapter-transport-smb` (`adapters/adapter-transport-smb`) | `jar` | `ioc-application` | 17 (16 Java) | 8 (8 Java) | SMB transport adapter |
| `ioc-adapter-ingest` (`adapters/adapter-ingest`) | `jar` | `ioc-application`, `ioc-platform-errors`, `ioc-platform-diagnostics`, `ioc-platform-observability`; `ioc-application-tck` (test) | 11 (10 Java) | 6 (6 Java) | File-ingest driving adapter |
| `ioc-adapter-cli-picocli` (`adapters/adapter-cli-picocli`) | `jar` | `ioc-application`, `ioc-platform-observability` | 15 (14 Java) | 8 (7 Java) | CLI driving adapter |
| `ioc-app` (`bootstrap/ioc-app`) | `jar` | `ioc-domain`, `ioc-application`; all 9 `ioc-adapter-*`; `ioc-platform-errors`, `ioc-platform-diagnostics`, `ioc-platform-events`, `ioc-platform-concurrency`, `ioc-platform-observability`, `ioc-platform-diagnostics-logging` | 74 (68 Java) | 60 (52 Java) | Composition root and sole runnable artifact |

Totals across child modules:

- 564 tracked files under `src/main`, including 501 Java files;
- 182 tracked files under `src/test`, including 173 Java files;
- no additional non-`target` POM exists outside the root and its 20 declared
  child modules.

## Tests и coverage

| Module/scope | Test classes | Unit/component | Integration | Contract/architecture/publication/E2E | Line | Branch | Missed branches | Duration | Flake status |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Reactor aggregate | 173 Java files / 171 suites / 781 cases | 114 | 38 | 6 / 4 / 7 / 2 | `87.34%` | `69.38%` | `1224` | `58.74 s` clean coverage verify real | No reproduced failure; coverage gaps handed to `R030-TEST` |
| 19 production module groups | See detailed ledger | See detailed ledger | See detailed ledger | See detailed ledger | `44.44–100%` | `50.00–97.10%` or `N/A` | See detailed ledger | Included above | Aggregate retains downstream execution |

Detailed per-module ratchets и dispositions ведутся в
[test-quality ledger](test-quality-ledger.md).

Coarse effectiveness review found meaningful positive, negative, contract,
recovery and concurrency coverage signals without treating assertion counts as
a quality score. The principal concrete supported-path gap is the configurable
JDK regex engine: RE2/J is exercised downstream, but the JDK implementation and
`ioc.engine=jdk` composition do not have a shared behavioral contract. Detailed
findings and dispositions are in the
[test-quality ledger](test-quality-ledger.md#coarse-effectiveness-baseline).

### `BASE-COVERAGE-05` closure

All six coverage-baseline gates are complete:

1. latest stable JaCoCo `0.8.15` and the 19-module production universe selected;
2. inherited agent/module reports and a dependency-ordered aggregate module
   added without checks or thresholds;
3. clean 22-project reactor verification and report-integrity reconciliation
   completed without losing tests;
4. aggregate and per-module line/branch/absolute-miss counters captured;
5. downstream-execution effects, zero-line classes and initial hotspots
   dispositioned without inferring test quality or dead code from coverage;
6. coverage universe accepted with no class/package exclusions, and remaining
   enforcement/remediation work handed to `R030-TEST`.

The accepted report-only baseline is `87.34%` line and `69.38%` branch coverage.
The aggregate, domain and application line floors are already met; their future
branch floors are not. The gap remains visible and is not hidden by a lower
threshold or exclusion.

## Test lifecycle baseline

| Signal | Current state | Command/evidence | Target/disposition |
|---|---|---|---|
| Surefire-discovered tests | 171 suites / 781 cases; 0 failures, 0 errors, 2 skipped | Declared-reactor report reconciliation; [test-quality ledger](test-quality-ledger.md#baseline-discovery-inventory) | Source/discovery reconciliation complete |
| Failsafe-discovered tests | 0; plugin is managed but has no project execution and no `*IT` source names | POM/effective-POM/source/report inventory; [test-quality ledger](test-quality-ledger.md#observed-lifecycle-classification) | Missing lifecycle separation; implement under `R030-TEST` |
| JUnit tags | No `@Tag` or composed test annotation; no tag filters | Source/POM/CI inventory; [test-quality ledger](test-quality-ledger.md#tags-conditions-and-skips) | Missing controlled selection; implement accepted vocabulary under `R030-TEST` |
| Fixed sleeps/bounded waits | 6 sleeps in 5 files are locally bounded/semantic; 10 unbounded `await()` calls in 9 files; no JUnit/fork timeout | Static source/POM inventory; [test-quality ledger](test-quality-ledger.md#wait-isolation-and-flake-baseline) | Remediate unbounded failure paths and add diagnostic upper bounds under `R030-TEST` |
| Disabled/quarantined tests | No `@Disabled`, assumptions, quarantine or retry; 2 opt-in external cases skipped by one class-level condition | Surefire XML and source inventory; [test-quality ledger](test-quality-ledger.md#tags-conditions-and-skips) | Live SMB evidence remains unavailable; no hidden offline quarantine found |
| Slowest tests/suites | Slowest suite `4.429 s`; slowest case `1.930 s`; bootstrap + JDBC are 62.0% of suite-seconds | Timed full verify and Surefire XML; [test-quality ledger](test-quality-ledger.md#duration-baseline) | Duration baseline captured; lifecycle split and ongoing trend control belong to `R030-TEST` |

## Quality reports

| Signal | Tool/version | Result | Artifact | Disposition |
|---|---|---|---|---|
| Compiler warnings | Maven Compiler Plugin `3.14.1`, JDK `21.0.11` | No ordinary javac source warning; two annotation-processing advisories in `ioc-app` main/test compilation | Clean verification console; [build-quality ledger](build-quality-ledger.md#baseline-compiler-и-test-runtime-warnings) | Keep visible; explicit processor configuration is an `R030-BUILD` candidate, not a BASE fix |
| Test-runtime warnings | Surefire `3.5.6`, Mockito/Byte Buddy, SLF4J `2.0.17` | Five no-provider warning groups on isolated module test classpaths; one Mockito self-attachment/dynamic-agent warning group | Clean verification console; [build-quality ledger](build-quality-ledger.md#baseline-compiler-и-test-runtime-warnings) | Test logging signal belongs to `R030-TEST`; future-JDK agent configuration belongs to `R030-BUILD`/`R030-TEST` |
| Static analysis | Existing Maven lifecycle | Enforcer and ArchUnit pass; no general bytecode analyzer is currently configured | POM/test/workflow inventory | Preserve existing controls; introduce SpotBugs separately |
| SpotBugs | Not configured | Missing by design at baseline | No report | `Introduce` report-first under `R030-BUILD`, then accepted blocking ratchet |
| PMD CPD aggregate | Not configured | Missing by design at baseline | No report | `Introduce` as report-only diagnostic control under `R030-BUILD` |
| Dependency convergence | Maven verbose dependency tree; no convergence rule | Not enforced; one `org.ow2.asm:asm` `9.7.1` path is mediated to `9.10.1` in the `ioc-app` test graph | Dependency-tree console | Do not claim convergence; separate bounded decision is required before adding a rule |
| Maven dependency analysis | Maven Dependency Plugin `3.9.0` | Reproducible only with lifecycle; 68 / 36 / 12 coordinate occurrences with substantial framework, aggregate-test and SPI noise | [build-quality ledger](build-quality-ledger.md#baseline-maven-dependency-analysis) | `Evaluate` under `R030-BUILD`; no deletion or blocking gate from raw output |
| Security | OWASP Dependency-Check Maven `12.2.2` + scheduled/manual workflow | Existing separate control; deliberately not run in this code-quality gate | `.github/workflows/dependency-security.yml`, `tools/ci/security-*.sh` | Preserve; vulnerability/SAST analysis remains outside `BASE-QUALITY-06` |

### Закрытие `BASE-QUALITY-06`

Baseline качества разделяет четыре типа evidence вместо объединения всех строк
консоли в один счётчик предупреждений:

1. чистый reactor не выдаёт обычных javac-предупреждений уровня исходного кода;
2. остаются предупреждения о будущей совместимости toolchain: неявном поиске
   annotation processors и динамической загрузке Mockito/Byte Buddy agent;
3. изолированные test JVM выдают пять групп SLF4J no-provider warnings, которые
   могут скрывать log assertions или diagnostics, но не доказывают дефект
   production logging;
4. bytecode-анализ Maven dependencies выдаёт широкий набор кандидатов, включая
   как реальные вопросы к direct dependencies, так и известные слепые зоны
   dynamic/framework анализа.

`dependency:analyze-only` нельзя воспроизводимо вызвать отдельной reactor goal:
downstream modules не могут разрешить ещё не предоставленные reactor-local
artifacts. Поэтому evaluation-команда включает `verify`, а последовательный
запуск с `-DskipTests` использован только для привязки не перемешанных findings
к модулям. Отчёт является evidence для semantic review, а не разрешением удалять
dependencies.

Verbose dependency tree содержит один путь с Maven mediation для ASM.
Существующая конфигурация Enforcer проверяет версии toolchain и дублирующиеся
объявления, но не выполняет `dependencyConvergence`. Поэтому baseline сохраняет
сигнал, не объявляет reactor convergence-clean и не расширяет принятый scope
0.3.0.

Существующие controls прослежены от исполняемой конфигурации, а не только от
planning prose. Maven Wrapper, Enforcer, ArchUnit, reactor CI, documentation
checks, dependency-security workflow и release workflow присутствуют и остаются
обязательными. JaCoCo действует в report-only mode и усиливается в `R030-TEST`;
SpotBugs и CPD остаются отдельными introductions в `R030-BUILD`. Исправление
исходного кода, dependencies, POM или workflows в этот baseline gate не входит.

## Runtime/performance

| Scenario | Input/profile | Metric | Baseline | Environment | Command |
|---|---|---|---:|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

## Compatibility baseline

| Surface | Supported source/version | Known consumer | Upgrade/rollback path | Owner |
|---|---|---|---|---|
| CLI/config | TBD | TBD | TBD | TBD |
| SQLite/durable state | TBD | TBD | TBD | TBD |
| CSV/export/manifest | TBD | TBD | TBD | TBD |
| Maven API | TBD | TBD | TBD | TBD |
| Packaging/deployment | TBD | TBD | TBD | TBD |

## Controls inventory

| Control | State | Evidence | Owner | Follow-up |
|---|---|---|---|---|
| Maven Wrapper | `Existing` | Wrapper `3.3.4`, Maven distribution `3.9.9`; used by Make and CI | Root build | Preserve as the only supported Maven entry point |
| Maven Enforcer | `Existing` | Plugin `3.6.3`; Java `[21,)`, Maven `[3.9,)`, duplicate-POM-dependency checks plus domain/application boundary bans and release commit validation | `R030-BUILD` | Preserve; any convergence or compiler-policy expansion needs a separate decision |
| ArchUnit | `Existing` | `ArchitectureTest`, `DomainBoundaryTest`, `PipelineModelArchitectureTest`; version `1.3.0`; all pass in `verify` | Architecture owners | Preserve and extend only with changed boundaries |
| Reactor CI | `Existing` | Pinned-action workflow delegates to `tools/ci/build.sh` and runs the canonical Maven verify | Build/CI | Preserve transparent leaf-script contract |
| Documentation checks | `Existing` | `DocumentationConventionTest`; offline Lychee in `tools/ci/docs.sh`; dedicated `doc-links` job | Documentation owners | Preserve and update with documentation topology |
| Dependency security workflow | `Existing` | Scheduled/manual NVD refresh plus offline Dependency-Check scan and report upload | Security/release | Preserve; not a substitute for code-quality analysis |
| Release workflow | `Existing` | Exact annotated-tag validation, single clean build, source-tree immutability check, packaging/docs gates and draft release | Release owner | Preserve |
| JaCoCo report/check | `Strengthen` | JaCoCo `0.8.15` agent, per-module and aggregate HTML/XML reports in `verify`; no checks yet | `R030-TEST` | Add accepted local ratchets/checks; Codecov remains secondary signal |
| Failsafe lifecycle | `Introduce` | Plugin is only managed; no execution or `*IT` discovery exists | `R030-TEST` | Separate integration lifecycle |
| JUnit tag convention | `Introduce` | No `@Tag`, composed tag or Maven/CI filter exists | `R030-TEST` | Add accepted vocabulary and convention check |
| Codecov | `Introduce` | No workflow/upload exists | `R030-TEST` | Best-effort upload and non-required signal only |
| Scheduled stability run | `Introduce` | No scheduled repeat/stability workflow exists | `R030-TEST` | Add report-only pilot after lifecycle/tag controls |
| SpotBugs | `Introduce` | No plugin or report exists | `R030-BUILD` | Report-first rollout, triage, then blocking accepted signal |
| PMD CPD aggregate | `Introduce` | No plugin or report exists | `R030-BUILD` | Calibrated repository-wide report-only diagnostic control |
| Maven dependency analysis | `Evaluate` | Bounded Plugin `3.9.0` report captured; high framework/dynamic noise | `R030-BUILD` | Decide `Adopt`, `Adopt with exclusions` or `Defer` |
| Deferred analyzers/formatters/SAST | `Deferred` | No Spotless/Checkstyle, japicmp/Revapi, Error Prone/NullAway, Sonar/Qodana, CodeQL or full PMD ruleset | Outside 0.3.0 | Absence is not missing evidence for this release |

## Missing evidence

| Item | Reason | Impact | Owner | Exit condition |
|---|---|---|---|---|
| Live SMB `CHANGE_NOTIFY` contract | `ioc.smb.contract` external-fixture property is absent in the baseline environment | Two `SmbChangeNotifyContractTest` cases are skipped; live-share behavior is not covered by this verification | `R030-TEST` / `R030-REL` | Execute against an approved live SMB fixture or record an explicit environment-dependent release disposition |

## Completion

- [x] Revision/environment fixed
- [x] Clean verification captured
- [x] Module/dependency inventory complete
- [x] Tests/coverage captured
- [x] Test lifecycle/tags/waits captured
- [x] Quality reports captured
- [ ] Runtime/performance captured
- [ ] Compatibility obligations captured
- [x] Controls classified
- [x] Status matrix initialized
