---
title: "0.3.0 test-quality evidence"
version: "0.3.0"
goal_id: "R030-TEST"
status: "In progress"
document_type: "Evidence ledger"
source_of_truth: false
language: "ru"
---

# R030-TEST — Test-quality evidence

Contract: [R030-TEST](../goals/R030-TEST-test-quality.md).

Этот ledger хранит изменяемые измерения и dispositions. Нормативные thresholds,
taxonomy и scope находятся только в goal contract.

`BASE-TESTS-04` inventory status: **verified** on evidence commit `5a746bb`.
`BASE-COVERAGE-05` report-only baseline status: **verified**; detailed metrics
are captured below. Its successor `TEST-COVERAGE-02` is also **verified** with
fail-closed report integrity, ratchets and fixed floors.
`TEST-REGEX-03` is **verified** with a shared two-engine contract, live
configuration-corpus compatibility checks and bootstrap selection evidence.
`TEST-WAITS-04` is **verified** with bounded coordination and failure-safe
worker cleanup. `TEST-EXTERNAL-05` is **verified** by the provisioned live SMB
`CHANGE_NOTIFY` execution recorded below.

`BASE-INVENTORIES-09` intake status: **verified**. The initial work queue below
maps every baseline test/coverage/consumer gap to an owner without implementing
it during Wave 0.

The Wave 0 measurements remain historical evidence. The clean `cd120a66`
refresh is the current inventory starting point; the lifecycle and accepted
coverage-ratchet evidence below supersede its execution/coverage values. Do not
use the older 171-suite or pre-DATA coverage values as lifecycle or ratchet
inputs.

## Initial work-item queue

| Work item | Scope/outcome | Evidence | Dependency | State |
|---|---|---|---|---|
| `TEST-LIFECYCLE-01` | Introduce accepted tags/composed annotations and Surefire/Failsafe selection without losing the refreshed accepted universe | Verified implementation evidence below | Global `R030-TEST` Wave 1 | `verified` |
| `TEST-COVERAGE-02` | Add aggregate/per-module no-regression ratchets, then close accepted aggregate/domain/application branch floors | Coverage baseline, policy and implementation evidence below | Stable lifecycle/reporting | `verified` |
| `TEST-REGEX-03` | Common RE2/J + JDK engine contract and bootstrap `ioc.engine=jdk` selection test | Verified implementation evidence below | Regex/bootstrap module wave | `verified` |
| `TEST-WAITS-04` | Bound async waits, release workers in `finally`, assert termination and add diagnosable safety timeout | Verified implementation evidence below | Module test hardening | `verified` |
| `TEST-EXTERNAL-05` | Execute live SMB `CHANGE_NOTIFY` contract or record explicit external-evidence release disposition | Verified live Windows-host contract evidence below | Provisioned fixture / `R030-REL` | `verified` |
| `TEST-PILOTS-06` | Run PIT/domain, invariant and seeded repeat pilots; triage signal/noise/cost | Verified diagnostic pilot tables below | Wave 1 profiles/artifacts | `verified` |
| `TEST-CODECOV-07` | Best-effort non-required upload plus project/patch signals | Codecov table below | Stable JaCoCo XML + CI | `planned` |
| `TEST-PUBLICATION-08` | Out-of-reactor compile/runtime contract for an admitted published library | Compatibility/shared-code ledgers | Blocked until `R030-LIB` admission | `waiting-on-library-contract` |
| `TEST-CONSUMERS-09` | Add exact golden CSV/manifest/log/CLI consumer payload/query fixtures for accepted external surfaces | Compatibility consumer gaps | Per-surface owner decision | `planned` |

## Current-HEAD inventory refresh — 2026-09-01

The refresh was captured on clean `cd120a66` before lifecycle edits with
`make clean && make verify`. All 25 reactor projects passed; Maven reported
`02:28` for `verify`. SpotBugs accepted all 116 reviewed findings with 0
visible, and the CPD ratchet matched 21/21 groups. `make context` recorded
`verify.finished_at=2026-09-01T14:18:09Z` and `verify.fresh=true`.

Only modules declared by the root POM are counted. The ignored report under
the retired, non-reactor `adapters/adapter-lookup-csv/target/` remains excluded;
this is exactly the stale-output hazard that a project-owned inventory check
must avoid.

| Module | Source Java | Support Java | Suites | Cases | Skipped | Suite-seconds |
|---|---:|---:|---:|---:|---:|---:|
| `platform/platform-errors` | 0 | 0 | 0 | 0 | 0 | 0.000 |
| `platform/platform-diagnostics` | 11 | 0 | 11 | 34 | 0 | 0.708 |
| `platform/platform-etl` | 2 | 0 | 2 | 16 | 0 | 0.249 |
| `platform/platform-events` | 3 | 0 | 3 | 11 | 0 | 0.531 |
| `platform/platform-concurrency` | 3 | 0 | 3 | 16 | 0 | 0.658 |
| `platform/platform-observability` | 6 | 0 | 6 | 22 | 0 | 0.565 |
| `platform/platform-diagnostics-logging` | 4 | 0 | 4 | 37 | 0 | 0.649 |
| `core/ioc-domain` | 5 | 0 | 5 | 27 | 0 | 2.126 |
| `core/ioc-application` | 61 | 2 | 59 | 250 | 0 | 1.598 |
| `core/ioc-application-tck` | 0 | 0 | 0 | 0 | 0 | 0.000 |
| `adapters/adapter-regex-re2j` | 0 | 0 | 0 | 0 | 0 | 0.000 |
| `adapters/adapter-psl` | 1 | 0 | 1 | 11 | 0 | 0.726 |
| `adapters/adapter-source-tika` | 3 | 0 | 3 | 8 | 0 | 9.839 |
| `adapters/adapter-csv` | 14 | 0 | 14 | 72 | 0 | 5.784 |
| `adapters/adapter-manifest-json-jackson` | 1 | 0 | 1 | 6 | 0 | 2.710 |
| `adapters/adapter-store-jdbc` | 27 | 0 | 27 | 187 | 1 | 43.031 |
| `adapters/adapter-transport-smb` | 17 | 2 | 15 | 79 | 7 | 3.679 |
| `adapters/adapter-ingest` | 11 | 0 | 11 | 55 | 0 | 3.452 |
| `adapters/adapter-cli-picocli` | 8 | 0 | 8 | 36 | 0 | 6.791 |
| `bootstrap/ioc-app` | 75 | 0 | 75 | 319 | 0 | 24.865 |
| **Reactor total** | **252** | **4** | **248** | **1186** | **8** | **107.961** |

The four support sources are the two previously recorded application helpers
plus `SmbContractTestSupport` and `TestImportSnapshotStore`. Every other
top-level test source has a matching Surefire XML suite, and no declared-reactor
report lacks a matching source. The refresh adds 77 suites and 405 cases over
the Wave 0 snapshot; the increase is concentrated in DATA-TTL-01 and
DATA-IMPORT-01 application, JDBC, SMB, ingest and bootstrap coverage.

Current lifecycle and selection signals:

| Signal | Current evidence | Disposition for `TEST-LIFECYCLE-01` |
|---|---|---|
| Surefire | `3.5.6`; owns all 248 discovered suites in Maven `test` | Split must preserve coverage data, reports and failure propagation |
| Failsafe | Version managed, no execution and no reports | Introduce only after lossless selection is executable |
| Tags/composed annotations | 0 | Accepted vocabulary exists only in the goal contract |
| Integration naming | No `IT*`, `*IT` or `*ITCase` sources | Rename/move only from the reviewed migration inventory |
| Conditional suites | 5 suites / 8 cases | Keep provisioned load/SMB evidence outside the deterministic offline union |
| Regular result | 1178 passed, 8 skipped, 0 failures/errors | The eight skips are unavailable opt-in evidence, not passes |
| Retry | None | Do not introduce retries as lifecycle migration |
| CI ownership | One full `tools/ci/build.sh` verify | Test/coverage artifact retention remains missing |

The conditional cases are one managed-import load profile, three managed SMB
delivery cases, two live SMB `CHANGE_NOTIFY` cases, one SMB encryption case and
one two-identity SMB hardening case. They are controlled by explicit system
properties; there are no `@Disabled`, assumption-driven skips or tag filters.
The accepted deterministic offline universe must therefore be defined
separately from the five provisioned-only suite shells before proving the fast
and integration union.

The suite-duration sum is not wall-clock because Maven runs modules in
parallel. `adapter-store-jdbc` contributes 39.9% and `bootstrap/ioc-app` 23.0%;
together they account for 62.9%. The slowest suites are
`JdbcImportWorkspaceTest` (15.913 s), `JdbcLedgerDaemonRuntimeModeTest`
(5.924 s), `JdbcCanonicalImportWriterContractTest` (5.351 s),
`TikaSourceReaderDiagnosticTest` (4.704 s) and `JdbcSnapshotSliceReaderTest`
(4.382 s). The 100k import-workspace case alone contributes 10.862 s and must
be classified by semantics, not moved merely because it is expensive.

The refreshed static wait inventory finds 6 `Thread.sleep` calls in 5 files,
60 timed `await` calls, 25 timed `Future.get` calls, 11 bare `await()` calls in
10 files, 9 timed thread joins and no JUnit `@Timeout` or Surefire fork timeout.
The additional bare wait is the lifecycle-deadline scheduler worker gate.
`TEST-WAITS-04` remains separate from lifecycle migration.

## `TEST-LIFECYCLE-01` implementation evidence — 2026-09-02

The reviewed behavior-based inventory was migrated to Maven naming ownership.
After `TEST-REGEX-03`, Surefire owns 193 fast
`*Test` suites and Failsafe owns 66 `*IT` suites. Five of the Failsafe suites
are explicitly conditioned external shells, so the deterministic offline
universe is `193 + (66 - 5) = 254` suites. The two source sets are disjoint and
their complete union is the current 259-suite reactor universe; the added local
WatchService integration suite is recorded in checkpoint 26 below and the two
regex suites are recorded in the `TEST-REGEX-03` evidence below.

| Cohort | Suites | Cases | Passed | Skipped | Failures/errors | Suite-seconds |
|---|---:|---:|---:|---:|---:|---:|
| Surefire fast | 193 | 1002 | 1002 | 0 | 0 | 34.247 |
| Failsafe integration, including external shells | 66 | 462 | 454 | 8 | 0 | 91.625 |
| **Full reactor union** | **259** | **1464** | **1456** | **8** | **0** | **125.872** |

The five external shells and their eight skipped cases are unchanged: one
managed-import load profile and four SMB suites. Their `@ExternalTest`
classification composes `integration`, and the lifecycle verifier requires an
explicit `@EnabledIfSystemProperty` on each shell. They remain discoverable and
reported but are not counted as successful provisioned evidence.

The shared TCK exports runtime-retained `@IntegrationTest`, `@ContractTest`,
`@EndToEndTest`, `@ExternalTest` and `@SlowTest` annotations. Repeated contract
semantics are inherited from the abstract TCK classes; architecture-only core
tests use direct `@Tag("architecture")` so framework-free modules do not acquire
an outward test-support dependency. No lifecycle selection uses tag filters.

The JDK-only `build-support/test-quality` verifier runs in root `validate` and
has 2 happy paths plus 8 negative contract scenarios. It rejects unknown tags,
invalid composition, executable tests outside both naming conventions,
wrong-engine ownership, unguarded external suites, lifecycle filters and exact
source-count drift. The late coverage-module execution additionally compares
the source sets with generated Surefire/Failsafe XML, rejecting missing,
unexpected, duplicate or cross-engine suites.

The lifecycle migration's initial qualifying `make verify` passed all 25
projects in `02:27`. The late verifier reported
`fast=183`, `integration=65`, `external=5` and
`deterministic-offline=243`; SpotBugs remained `116 accepted / 0 visible` and
CPD remained `21/21`. A first isolated integration run exposed that the
bootstrap project-version system property was Surefire-only;
`LogbackConfigurationIT` failed rather than being masked. The property is now
supplied to Failsafe as well, and a focused rerun passed 2/2 before the full
gate.

JaCoCo now starts from one cleaned module execution-data file and appends both
test JVM cohorts before module and aggregate reporting. The post-split
aggregate is 19162/22389 lines (85.59%) and 5434/8119 branches (66.93%). This is
9 fewer covered lines and 12 fewer covered branches than the pre-split run even
though the exact 248-suite/1186-case universe is retained; no coverage ratchet
exists yet, so the small execution-path variance is recorded rather than
silently treated as a regression decision. `TEST-COVERAGE-02` must use fresh
post-split repetitions when accepting numeric ratchets.

CI now retains Surefire XML, Failsafe XML, module JaCoCo and aggregate JaCoCo
directories for 30 days with `if: always()`. The durable English lifecycle,
taxonomy, command, external-evidence and flake policy is published in
[`docs/TESTING.md`](../../../TESTING.md).

### Accepted aggregate-group coverage ratchets

The 19 groups below come from the reactor aggregate so downstream execution is
credited. They are the accepted conservative no-regression boundaries after the
remediation repetitions described below. Fixed release floors are enforced
separately against the actual report and therefore do not permit these ratchets
to regress.

| Module/scope | Lines covered/total | Line | Branches covered/total | Branch | Missed branches | Release floor/state |
|---|---:|---:|---:|---:|---:|---|
| **Reactor aggregate** | **19860/22390** | **88.70%** | **6480/8125** | **79.75%** | **1861** | ratcheted; fixed `75% / 80%` gate enabled against actual report |
| `platform/platform-errors` | 4/4 | 100.00% | 0/0 | N/A | 0 | ratcheted |
| `platform/platform-diagnostics` | 481/491 | 97.96% | 56/76 | 73.68% | 20 | ratcheted |
| `platform/platform-etl` | 165/181 | 91.16% | 16/24 | 66.67% | 8 | ratcheted |
| `platform/platform-events` | 41/41 | 100.00% | 12/14 | 85.71% | 2 | ratcheted |
| `platform/platform-concurrency` | 197/218 | 90.37% | 48/66 | 72.73% | 18 | ratcheted |
| `platform/platform-observability` | 327/341 | 95.89% | 69/71 | 97.18% | 2 | ratcheted |
| `platform/platform-diagnostics-logging` | 56/60 | 93.33% | 20/21 | 95.24% | 1 | ratcheted |
| `core/ioc-domain` | 239/243 | 98.35% | 108/110 | 98.18% | 2 | ratcheted; fixed `85% / 90%` floors reached |
| `core/ioc-application` | 5107/5412 | 94.36% | 2067/2292 | 90.18% | 241 | ratcheted; fixed `85% / 90%` floors enabled |
| `core/ioc-application-tck` | — | N/A | — | N/A | — | outside production universe |
| `adapters/adapter-regex-re2j` | 18/18 | 100.00% | 4/4 | 100.00% | 0 | ratcheted; local shared-engine contract |
| `adapters/adapter-psl` | 15/17 | 88.24% | 11/12 | 91.67% | 1 | ratcheted |
| `adapters/adapter-source-tika` | 57/58 | 98.28% | 7/10 | 70.00% | 3 | ratcheted |
| `adapters/adapter-csv` | 1122/1294 | 86.71% | 424/560 | 75.71% | 145 | ratcheted |
| `adapters/adapter-manifest-json-jackson` | 82/84 | 97.62% | 5/6 | 83.33% | 1 | ratcheted |
| `adapters/adapter-store-jdbc` | 5346/6013 | 88.91% | 1461/1858 | 78.63% | 468 | ratcheted |
| `adapters/adapter-transport-smb` | 813/1096 | 74.18% | 334/510 | 65.49% | 201 | ratcheted; external-path concentration |
| `adapters/adapter-ingest` | 929/1189 | 78.13% | 328/422 | 77.73% | 135 | ratcheted |
| `adapters/adapter-cli-picocli` | 415/564 | 73.58% | 140/227 | 61.67% | 113 | ratcheted |
| `bootstrap/ioc-app` | 4443/5066 | 87.70% | 1372/1842 | 74.48% | 496 | ratcheted; JDK engine selection covered |

Compared with Wave 0, the production denominator grew from 10962 to 22390
lines and from 3998 to 8125 branches. The accepted aggregate ratchet is 88.70%
lines and 79.75% branches. `TEST-COVERAGE-02` blocks regression from this
universe while the independent fixed gate enforces aggregate `75% / 80%` and
domain/application `85% / 90%` floors against the actual report.

The largest current missed-branch concentrations are `AppConfig` (49),
`JdbcCanonicalImportWriter` (44), the unexecuted live `SmbjShareClient` seam
(38), `IocConfigPreflight` (32), `SmbFileTransport` (31) and
`SmbManagedImportSourceLifecycle` (30). These are triage inputs, not an instruction to add
percentage-only tests.

### `TEST-COVERAGE-02` phases 1-3 implementation evidence — 2026-09-02

До установки gate post-lifecycle baseline был повторён на неизменённом
`dd364a0f`. Серия aggregate результатов составила `19162/22389 + 5434/8119`,
затем после clean build `19169/22389 + 5444/8119` и ещё два независимых
`19163/22389 + 5436/8119`. Denominators и 19 groups оставались неизменными;
вариативность локализована в условных recovery/scheduler paths. Поэтому ratchet
берёт нижнюю наблюдавшуюся границу, а не удачный максимум или округлённый
percentage. Per-module minima также выбирались независимо между прогонами и не
должны арифметически суммироваться в один выдуманный aggregate run.

`coverage-scope.tsv` теперь даёт disposition всем 25 reactor projects:
19 production JARs входят в aggregate, root, TCK и три соседних report POM
исключены, а `coverage-report` является единственным aggregate owner. Для 18
production modules обязательны non-empty local execution data + XML/HTML;
`platform-errors` явно отмечен `aggregate-only`, потому что его bytecode
исполняется downstream tests. Class/package exclusions не приняты.

JDK-only `CoverageVerifier` в root `validate` сверяет registry с root reactor,
POM packaging, aggregate dependencies, ratchet scopes, отсутствие JaCoCo
filters/skip и точное Maven wiring. Synthetic harness содержит 2 happy paths и
22 negative scenarios. Late `verify` удаляет старые module/aggregate report
directories, требует exact 19-group aggregate, проверяет group sums, ожидаемые
18 local reports и отсутствие output у excluded/downstream-only owners.

`coverage-ratchets.tsv` блокирует снижение line/branch ratio точным integer
cross-multiplication без decimal rounding и рост absolute missed branches.
Для small-denominator modules дополнительно блокируется рост missed
instructions; для остальных это значение сохраняется как review context.
Первый implementation `make verify` прошёл 25/25 за `02:32`: late gate принял
`19164/22389` lines и `5436/8119` branches. Повторный final-worktree run также
прошёл 25/25 за `02:32` с `19162/22389` и `5435/8119`; SpotBugs остался
`116 accepted / 0 visible`, CPD — `21/21`. Это закрывает universe, report
integrity и no-regression этапы, но не fixed floors: их remediation и включение
отложены в отдельную оставшуюся часть `TEST-COVERAGE-02`.

### `TEST-COVERAGE-02` remediation checkpoint 1 — domain contracts

Первый remediation slice закрыл доменные ветви через проверку поведения, а не
через вызов строк ради процента. Новые тесты фиксируют vocabulary и truth tables
classification predicates, first-match/catch-all semantics, validation rule и
decision indices, пустые extraction/refang outcomes, punctuation-only
normalization и неоднозначные host/port boundaries. Production code и
архитектурные зависимости не менялись.

`core/ioc-domain` вырос с `229/243` до `239/243` lines и с `86/110` до
`108/110` branches. Результат повторился во всех трёх aggregate runs и
превышает release floors `85% / 90%`. Ratchet поднят до этого точного
domain-group результата; явный fixed-floor check будет включён вместе с
aggregate и application floors в финальном slice, чтобы политика оставалась
одной атомарной build-схемой.

Два прогона также обнаружили ранее скрытую условность покрытия вне домена.
SMB error mapper зависел от downstream-пути для одного варианта, а shutdown
ветвь keyed executor зависела от того, успеет ли worker завершиться раньше
теста. Локальные tests теперь явно покрывают все unreachable exception types,
preserved mapped failures, message fallback и управляемый latch shutdown. После
этого canonical `make verify` прошёл 25/25: lifecycle union составил
`186 fast + 65 integration`, aggregate — `19172/22389` lines и `5459/8119`
branches, SpotBugs — `116 accepted / 0 visible`, CPD — `21/21`. Для reactor
ratchet принята независимая консервативная нижняя граница `19172/22389 +
5457/8119`, а SMB ratchet поднят до повторившихся `309/510` branches.

### `TEST-COVERAGE-02` remediation checkpoint 2 — import catalog validation

Второй remediation slice фиксирует fail-closed semantics конфигурационного
compiler managed import. Восемь поведенческих tests проверяют collect-all
валидацию отсутствующего catalog/environment, authority ceilings и allowlists,
contract/dialect/recognition shape, alias ambiguity, artifact identity и
mapping references, requested export slots, source ownership и превышение
authority при routing/formula/merge overrides. Production code и
архитектурные зависимости не менялись.

Покрытие `DataframeImportCatalogCompiler` выросло с `298/398` до `388/398`
lines и с `144/276` до `253/276` branches. Полный canonical `make verify`
прошёл 25/25 за `03:16`; lifecycle verifier подтвердил
`187 fast + 65 integration`, external shells остались `5`, deterministic
offline universe — `247`. Фактический aggregate составил `19262/22389` lines и
`5578/8119` branches, application group — `4666/5412` и `1594/2292`.

Ratchet принимает независимую консервативную границу: прежние minima плюс
детерминированный вклад compiler tests, то есть aggregate
`19262/22389 + 5566/8119`, application `4664/5412 + 1584/2292`. Более высокий
условный branch result одного полного прогона не превращается в случайный
baseline. Application line floor `85%` достигнут; aggregate `80%` и application
`90%` branch floors остаются открытыми и пока не включаются как fixed gates.

### `TEST-COVERAGE-02` remediation checkpoint 3 — import state machines

Третий remediation slice закрывает переходы admission и processing state
machines managed import. Поведенческие tests фиксируют reserve/claim/pin и
replay, due-only recovery, повторный staging и adoption sealed workspace,
полную трансляцию recognition/workspace failure codes, retry и CAS conflicts,
promotion response loss, canonical receipt recovery, post-commit finalization
и terminal-report failure. В тестах нет ожиданий по времени или внешнего I/O;
production code и архитектурные зависимости не менялись.

Покрытие `DataframeImportAdmissionService` выросло с `59/99` до `96/99` lines
и с `11/42` до `36/42` branches; `DataframeImportProcessingService` — с
`93/166` до `161/166` lines и с `16/49` до `45/49` branches. Полный canonical
`make verify` прошёл 25/25; lifecycle verifier подтвердил
`188 fast + 65 integration`, external shells остались `5`, deterministic
offline universe — `248`. Фактический aggregate составил `19371/22389` lines и
`5634/8119` branches, application group — `4775/5412` и `1648/2292`.

Ratchet принимает прежние minima плюс только детерминированную дельту двух
сервисных классов: aggregate `19367/22389 + 5620/8119`, application
`4769/5412 + 1638/2292`. Условный запас полного прогона не принят как baseline.
После этого checkpoint до aggregate `80%` не хватает `876` covered branches,
до application `90%` — `425`; fixed-floor gates остаются финальным отдельным
slice после дальнейшего behavior-first remediation.

### `TEST-COVERAGE-02` remediation checkpoint 4 — import mapping and promotion

Четвёртый remediation slice фиксирует семантику tri-state mapping и границу
canonical promotion. Mapping tests покрывают precedence column/artifact/contract
merge policy, отсутствующие, пустые и null cells, requested-slot edge cases,
все spreadsheet-formula prefixes и явный machine-only preserve, а также
передачу полного logical row в processed strategy. Promotion tests проверяют
STAGED→PROMOTING→CANONICAL_COMMITTED, idempotent `ALREADY_APPLIED`, CAS conflict,
исчезновение delivery после durable transition, обязательность snapshot,
contract и stage evidence, receipt/ledger contradiction и failure-safe observer.

`DataframeImportRowMapper` вырос с `85/91` до `91/91` lines и с `42/56` до
`56/56` branches; `ImportMergeResolver` — с `27/33` до `33/33` и с `23/33`
до `33/33`; `DataframeImportPromotionService` — с `34/44` до `44/44` и с
`7/16` до `16/16`. Полный `make verify` прошёл 25/25 за `04:46`; lifecycle
union составил `189 fast + 65 integration`, external shells — `5`,
deterministic offline universe — `249`. Фактический aggregate равен
`19393/22389` lines и `5666/8119` branches, application group — `4797/5412`
и `1682/2292`.

Ratchet принимает прежние minima плюс точную дельту трёх классов по
line/branch, но оставляет один instruction условного запаса полного reactor:
aggregate `19389/22389 + 5653/8119`, application
`4791/5412 + 1671/2292`. До aggregate `80%` теперь не хватает `843` covered
branches, до application `90%` — `392`.

### `TEST-COVERAGE-02` remediation checkpoint 5 — import value contracts

Пятый remediation slice фиксирует fail-closed invariants managed-import data
boundary. Единая contract suite проверяет все short-circuit границы workspace
и parser limits, полноту status head, retention age/count/archive policy,
terminal outcome derivation, точное множество projection generations,
primary/related slot и match-key ограничения, stage counts, delivery timeline,
evidence order, terminal/retry CAS metadata, readiness dispositions и
нормализацию внешних policy tokens и SHA-256 identities.

Новый набор из 13 поведенческих tests поднял application group с `4797/5412`
до `4871/5412` lines и с `1682/2292` до `1780/2292` branches. Полный
`make verify` прошёл 25/25 за `03:37`; lifecycle union составил
`190 fast + 65 integration`, external shells — `5`, deterministic offline
universe — `250`. Фактический aggregate равен `19468/22389` lines и
`5765/8119` branches.

Ratchet принимает точную application дельту (`+74` lines, `+98` branches,
`-368` missed instructions) поверх прежних независимых minima: aggregate
`19463/22389 + 5751/8119`, application `4865/5412 + 1769/2292`. До aggregate
`80%` теперь не хватает `745` covered branches, до application `90%` — `294`.

### `TEST-COVERAGE-02` remediation checkpoint 6 — artifact publish recovery

Шестой remediation slice фиксирует fail-closed семантику publish orchestration:
валидацию profile/target selection и identity события завершённого slice,
полное отсутствие remote и retryable-ledger I/O в dry-run, подсчёт всех
существующих ledger states без повторного discovery, изоляцию повреждённых и
исчезнувших catalog entries, запрет missing/rebound local slice и перевод
ошибок чтения remote marker в ограниченную failed attempt с диагностикой.

Семь новых поведенческих tests подняли `ArtifactPublishService` с `191/233` до
`229/233` lines и с `58/82` до `73/82` branches. Полный `make verify` прошёл
25/25 за `03:36`; lifecycle union остался `190 fast + 65 integration`,
external shells — `5`, deterministic offline universe — `250`. Фактический
aggregate равен `19506/22389` lines и `5780/8119` branches, application group —
`4910/5412` и `1796/2292`.

Ratchet принимает только точную дельту целевого service (`+38` lines,
`+15` branches, `-153` missed instructions) поверх прежних независимых minima:
aggregate `19501/22389 + 5766/8119`, application
`4903/5412 + 1784/2292`. До aggregate `80%` теперь не хватает `730` covered
branches, до application `90%` — `279`.

### `TEST-COVERAGE-02` remediation checkpoint 7 — ingestion recovery

Седьмой remediation slice фиксирует crash/recovery границы whole-file ingest:
конфликт первичного `markClaimed` с сохранением обоих suppressed cleanup
failures, исчезновение observation между incomplete scan и guarded reload,
возобновление существующего `CLAIMED` без повторного physical claim,
переклассификацию обычных и pipeline failures на ingest recovery boundary без
double reporting, сохранение уже типизированного ingest conflict, orphan
ownership/failure, ledger failure нового rejection и отказ terminalization.

Десять новых поведенческих tests подняли `IngestionService` с `207/231` до
`230/231` lines и с `48/64` до `62/64` branches. Две оставшиеся ветви требуют
`null` processing path или inserted count, запрещённых публичными model
contracts, поэтому тесты не обходят эти инварианты. Полный `make verify` после
flake-hardening диагностического assertion прошёл 25/25 за `02:34`; lifecycle
union остался `190 fast + 65 integration`, external shells — `5`,
deterministic offline universe — `250`. Фактический aggregate равен
`19530/22389` lines и `5794/8119` branches, application group — `4934/5412` и
`1810/2292`.

Ratchet принимает только точную дельту целевого service (`+23` lines,
`+14` branches, `-87` missed instructions) поверх прежних независимых minima:
aggregate `19524/22389 + 5780/8119`, application
`4926/5412 + 1798/2292`. До aggregate `80%` теперь не хватает `716` covered
branches, до application `90%` — `265`.

### `TEST-COVERAGE-02` remediation checkpoint 8 — lifecycle runtime and export schema

Восьмой remediation slice фиксирует one-way lifecycle activation при
конкурентных begin/completion, идемпотентность disabled/active policy,
fail-closed потерю completion CAS, exact generation acknowledgement mutable
projection, сохранение primary/suppressed projection и journal failures,
независимую bounded retention lifecycle history и confirmation receipts, а
также leaf-name, column uniqueness и versioned lower-case SHA-256 contracts
immutable export schema.

Четырнадцать новых поведенческих tests полностью закрыли
`ArtifactProjectionConvergenceService` (`42/42` lines, `18/18` branches),
`LifecycleHistoryRetentionService` (`42/42`, `20/20`) и
`ExportArtifactSpec` (`27/27`, `23/24` достижимых branches). Для
`LifecycleControlState` результат равен `38/38 + 26/28`, для
`LifecycleActivationService` — `58/59 + 39/40`; оставшиеся пути требуют
состояний, отвергаемых более ранними публичными constructors. Полный
`make verify` прошёл 25/25 за `02:16`; lifecycle union остался
`190 fast + 65 integration`, external shells — `5`, deterministic offline
universe — `250`. Фактический aggregate равен `19572/22389` lines и
`5838/8119` branches, application group — `4976/5412` и `1854/2292`.

Ratchet принимает только точную сумму дельт пяти целевых классов (`+41` lines,
`+44` branches, `-172` missed instructions) поверх прежних независимых minima:
aggregate `19565/22389 + 5824/8119`, application
`4967/5412 + 1842/2292`. До aggregate `80%` теперь не хватает `672` covered
branches, до application `90%` — `221`.

### `TEST-COVERAGE-02` remediation checkpoint 9 — sync boundary contracts

Девятый remediation slice фиксирует fail-closed контракты sync boundary:
безопасную сборку remote slice paths и atomic marker, ограниченный retry
backoff, нормализацию optional operator selections, routing и causation
completed-slice команды, стабильную remote-object identity, обязательные
local evidence для завершённого fetch, непустые change batches, SHA-256 и
verification publish ledger, неотрицательные result counters, а также
leaf-name include/exclude selection.

Одиннадцать новых поведенческих tests подняли application group с
`4976/5412` до `5001/5412` lines и с `1854/2292` до `1904/2292` branches;
фактический aggregate вырос с `19572/22389` до `19597/22389` lines и с
`5838/8119` до `5888/8119` branches. Lifecycle union остался
`190 fast + 65 integration`, external shells — `5`, deterministic offline
universe — `250`; выполнено `1315` cases (`1307` passed, `8` provisioned
external skips).

Ratchet принимает консервативную повторяемую group-дельту с сохранением
прежнего execution headroom: aggregate `19591/22389 + 5874/8119`, application
`4992/5412 + 1892/2292`. Улучшение missed instructions пока оставлено
непринятым, чтобы независимый minimum не зависел от вариативности downstream
execution. Первый полный прогон выполнил все tests и analyzers, но поздний
coverage gate обнаружил одноветочное отклонение существующего SMB ratchet
(`308/510` при принятом `309/510`). Порог не снижался: повторный полный
`make verify` воспроизвёл `309/510`, прошёл 25/25 за `05:10` и квалифицировал
checkpoint. До aggregate `80%` не хватает `622` covered branches, до
application `90%` — `171`.

### `TEST-COVERAGE-02` remediation checkpoint 10 — canonical lifecycle/export models

Десятый remediation slice фиксирует пограничные инварианты canonical identity,
lifecycle admission и immutable export: полное JSON escaping key material,
уникальность versioned record/match formulas, match cardinality и ID ranges,
retryable admission callbacks, непротиворечивые retention/reconciliation/write
counters, active-only snapshots, безопасные slice names, упорядоченные export
profiles и state-specific manifest/inspection evidence.

Двадцать два новых поведенческих tests подняли application group с
`5001/5412` до `5057/5412` lines и с `1904/2292` до `1982/2292` branches;
фактический aggregate равен `19653/22389` lines и `5966/8119` branches.
Целевые classes дали точные `+56` lines, `+78` branches и `-299` missed
instructions. Полный `make verify` прошёл 25/25 за `04:12`; lifecycle union
остался `190 fast + 65 integration`, выполнено `1337` cases (`1329` passed,
`8` provisioned external skips).

Ratchet принимает эту точную дельту поверх прежних независимых minima:
aggregate `19647/22389 + 5952/8119`, application
`5048/5412 + 1970/2292`; instruction minima снижены до `12617` и `2035`
соответственно. До aggregate `80%` не хватает `544` covered branches, до
application `90%` — `93`.

### `TEST-COVERAGE-02` remediation checkpoint 11 — application evidence contracts

Одиннадцатый remediation slice закрывает fail-closed контракты, которыми
application layer переносит operator и recovery evidence между lifecycle,
managed import, export, pipeline и sync границами. Двадцать семь новых tests
проверяют полноту и непротиворечивость lifecycle receipts/status, import
validation/recovery/report counters, replay identity, immutable-slice и publish
evidence, export change/progress, schema fingerprints, pipeline item decisions,
retained counts и lifecycle-aware canonical write routing. Проверки остаются на
публичных конструкторах и service boundaries; production-код ради покрытия не
изменялся.

Свежий полный `make verify` после Maven `javac` rebuild прошёл 25/25 за `04:23`.
Lifecycle union остался `190 fast + 65 integration`; выполнено `1365` cases
(`1357` passed, `8` provisioned external skips). Фактический aggregate равен
`19712/22389` lines и `6047/8119` branches, application group — `5116/5412`
lines и `2063/2292` branches. Таким образом application впервые фактически
достиг обоих release floors: `94.53%` lines и `90.01%` branches.

Ratchet принимает точную повторяемую application дельту `+59` lines и `+81`
branches поверх прежних независимых minima: aggregate `19706/22389 +
6033/8119`, application `5107/5412 + 2051/2292`. Улучшение missed instructions
оставлено непринятым, чтобы не связывать независимый minimum с вариативностью
downstream execution. Первый полный прогон после test additions также обнаружил
загрязнённые IDE bytecode-имена SpotBugs и одноветочное отклонение существующего
SMB coverage ratchet (`308/510` вместо `309/510`). Baseline и threshold не
менялись: `make clean` восстановил Maven `javac` bytecode, а последующий полный
прогон подтвердил `116 accepted / 0 visible` SpotBugs и повторяемые `309/510`
SMB branches. До aggregate `80%` по фактическому отчёту остаётся `449` covered
branches; fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 12 — sync health evidence

Двенадцатый remediation slice проверяет накопление и отображение operational
evidence в `SyncHealthState` и `SyncHealthIndicator`: coalesced detection,
watch reconnect/re-arm history, admission и dispatch rejection, terminal work
failure, endpoint-level detection/publish degradation и fail-closed поведение
при недоступной durable read-model границе. Пять новых tests используют
публичные state transitions и health response; production-код ради покрытия не
изменялся.

Свежий полный `make verify` прошёл 25/25 за `04:57`. Lifecycle union остался
`190 fast + 65 integration`; выполнено `1370` cases (`1362` passed, `8`
provisioned external skips). Фактический aggregate равен `19721/22389` lines и
`6085/8119` branches, а `ioc-app` group — `4405/5066` lines и `1308/1838`
branches.

Два целевых production classes дали точную дельту `+8` lines и `+37` branches:
`SyncHealthState` теперь имеет `114/114` lines и `81/88` branches,
`SyncHealthIndicator` — `221/222` lines и `126/157` branches. Ratchet принимает
только эту причинно связанную дельту поверх прежних независимых minima:
aggregate `19714/22389 + 6070/8119`, `ioc-app`
`4405/5066 + 1308/1838`. Дополнительный межпрогонный прирост остаётся запасом,
а missed-instruction context не ужесточается. До aggregate `80%` по
фактическому отчёту остаётся `411` covered branches; fixed-floor gate остаётся
отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 13 — semantic configuration preflight

Тринадцатый remediation slice закрепляет collect-all семантику startup
configuration preflight. Девять новых tests проверяют lifecycle durations,
single-thread ingestion, fixed-lifecycle prerequisite managed import, retry и
SMB invariants, fetch/publish references, sink paths/columns, artifact identity
и match-key contracts. Отдельный defensive сценарий подтверждает, что
structural `null` collections остаются ответственностью Bean Validation, но не
могут аварийно оборвать semantic preflight.

Последний сценарий обнаружил production regression: после регистрации ошибки
для `null` sink column `DataframeImportPropertyMapper` повторно вычислял public
ID schema через `IocProperties.Sink.Artifact.hasPublicIdColumn()` и получал NPE.
Метод теперь пропускает structurally-invalid `null` elements и возвращает
обычный semantic/Bean Validation evidence вместо необработанного исключения.

Свежий полный `make verify` прошёл 25/25 за `04:15`. Lifecycle union теперь
`191 fast + 65 integration`; выполнено `1379` cases (`1371` passed, `8`
provisioned external skips). Фактический aggregate равен `19758/22389` lines и
`6123/8123` branches, а `ioc-app` group — `4442/5066` lines и `1346/1842`
branches. Четыре новые ветви относятся к явной null-safe проверке; весь прирост
`ioc-app` относительно checkpoint 12 составляет `+37` covered lines и `+38`
covered branches.

Ratchet принимает только эту `ioc-app` дельту поверх прежних независимых
aggregate minima: aggregate `19751/22389 + 6108/8123`, `ioc-app`
`4442/5066 + 1346/1842`. Первый полный прогон после финального test addition
дошёл до late gate с известной вариативностью SMB `308/510`; baseline не
снижался, повторный полный прогон подтвердил принятые `309/510`. До aggregate
`80%` по фактическому отчёту остаётся `376` covered branches; fixed-floor gate
остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 14 — CSV slice materialization

Четырнадцатый remediation slice закрепляет fail-closed callback и durability
контракт materialization immutable CSV slice. Пять новых integration tests
проверяют snapshot/run/plan identity, порядок и `null` callback'и, каждое поле
ordered artifact metadata, неподдерживаемые format/delimiter/quote, unmappable
charset data, сбой durable file force и ошибку manifest codec. Проверки идут
через реальный `CsvSliceMaterialization` и файловую границу без внешней
инфраструктуры.

Null-сценарий обнаружил production regression: `beginArtifact(null)` проходил
sequence guard и завершался NPE на сравнении metadata. Guard теперь отклоняет
`null` тем же контролируемым `IllegalStateException`, что и остальные
нарушения callback protocol; архитектурная ответственность компонента не
изменилась.

Свежий полный `make verify` прошёл 25/25 за `02:27`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1383` cases (`1375` passed, `8`
provisioned external skips). Фактический aggregate равен `19779/22390` lines и
`6148/8125` branches, а `ioc-adapter-csv` group — `1122/1294` lines и
`415/560` branches. Целевой класс теперь имеет `131/140` lines и `58/70`
branches против прежних `110/139` и `34/68`: добавленный defensive condition
расширил denominator на одну line и две branches.

Ratchet принимает консервативный причинный прирост поверх прежних независимых
minima: aggregate `19771/22390 + 6132/8125`, `ioc-adapter-csv`
`1122/1294 + 415/560`; missed-instruction context не ужесточается. Запас
остального полного прогона не принят. До aggregate `80%` по фактическому
отчёту остаётся `352` covered branches; fixed-floor gate остаётся отдельным
финальным slice.

При последующем inventory refresh из числового итога checkpoint 14 исключён
один stale Surefire XML из удалённого и не входящего в reactor
`adapter-lookup-csv/target`: authoritative source/report union всегда содержал
и продолжает содержать только 191 fast suite. Это исправляет cases count, но
не меняет ни coverage report, ни lifecycle selection evidence.

### `TEST-COVERAGE-02` remediation checkpoint 15 — export-run ledger evidence

Пятнадцатый remediation slice усиливает reusable `ExportRunLedger` TCK на
границе durable service DB. Четыре новых tests и расширенный replay scenario
проверяют аргументы start/transition/finish, пустой и duplicate progress,
transactional rollback при снижении revision, точное совпадение terminal replay
по всем полям progress, полную identity duplicate start и reason совместимость
FAILED replay. Later-checkpoint replay теперь явно проверен как для `STAGED`,
так и для `AVAILABLE` после durable `COMPLETED`.

Свежий полный `make verify` прошёл 25/25 за `02:37`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1387` reactor cases (`1379` passed,
`8` provisioned external skips). Фактический aggregate равен `19792/22390`
lines и `6178/8125` branches, а `ioc-adapter-store-jdbc` group — `5282/6013`
lines и `1308/1858` branches. `JdbcExportRunLedger` вырос с `165/182` до
`179/182` lines и с `69/106` до `100/106` branches без production-изменений.

Ratchet принимает только точную дельту целевого класса `+14` lines и `+31`
branches поверх прежних независимых minima: aggregate
`19785/22390 + 6163/8125`, `ioc-adapter-store-jdbc`
`5282/6013 + 1308/1858`.
Missed-instruction context оставлен прежним. До aggregate `80%` по
фактическому отчёту остаётся `322` covered branches; fixed-floor gate остаётся
отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 16 — import-delivery ledger contracts

Шестнадцатый remediation slice расширяет reusable `ImportDeliveryLedger` TCK
на границе durable service DB. Пять новых contract tests проверяют явные
`MISSING`/`CONFLICT` результаты CAS, точное совпадение snapshot/contract/stage
evidence при replay, неизменность reservation identity, допустимые terminal
outcomes, versioned purge и count-only retention. Adapter-level test отдельно
фиксирует нормализацию отключённых retention deadlines и отклонение
отрицательных durations.

Свежий полный `make verify` прошёл 25/25 за `02:44`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1393` reactor cases (`1385` passed,
`8` provisioned external skips). Фактический aggregate равен `19808/22390`
lines и `6209/8125` branches, а `ioc-adapter-store-jdbc` group — `5298/6013`
lines и `1338/1858` branches. `JdbcImportDeliveryLedger` вырос до `231/239`
lines и `90/96` branches; добавленные сценарии закрыли 16 lines и 30 branches
без production-изменений.

Ratchet принимает только точную дельту целевого класса поверх прежних
независимых minima: aggregate `19801/22390 + 6193/8125`,
`ioc-adapter-store-jdbc` `5298/6013 + 1338/1858`. Missed-instruction context
оставлен прежним, а межпрогонный запас не принят. До aggregate `80%` по
фактическому отчёту остаётся `291` covered branch; fixed-floor gate остаётся
отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 17 — canonical-import evidence integrity

Семнадцатый remediation slice усиливает SQLite canonical-import writer на
границах sealed stage и durable receipt. Пять новых integration tests проверяют
отсутствующий и изменённый stage-файл, каждое поле pinned snapshot/contract/stage
metadata, каждое поле canonical receipt identity, deadline-only renewal с
`projection_generation = NULL`, duplicate schema/allocator definitions и
неположительный receipt retention. Проверки используют реальные private stage
и dataframe SQLite tables; внешняя инфраструктура не требуется.

Свежий полный `make verify` прошёл 25/25 за `02:23`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1398` reactor cases (`1390` passed,
`8` provisioned external skips). Фактический aggregate равен `19819/22390`
lines и `6239/8125` branches, а `ioc-adapter-store-jdbc` group — `5308/6013`
lines и `1367/1858` branches. `JdbcCanonicalImportWriter` вырос с `556/602`
до `566/602` lines и с `179/252` до `208/252` branches без production-изменений.

Ratchet принимает только точную дельту целевого класса `+10` lines и `+29`
branches поверх прежних независимых minima: aggregate
`19811/22390 + 6222/8125`, `ioc-adapter-store-jdbc`
`5308/6013 + 1367/1858`. Missed-instruction context оставлен прежним, а
межпрогонный запас не принят. До aggregate `80%` по фактическому отчёту
остаётся `261` covered branch; fixed-floor gate остаётся отдельным финальным
slice.

### `TEST-COVERAGE-02` remediation checkpoint 18 — import-workspace recovery

Восемнадцатый remediation slice закрывает recovery-контракт private SQLite
workspace. Четыре новых integration tests проверяют отсутствие recoverable
stage, успешный `adoptSealed` при точном pinned evidence, отказ для каждого
несовпадающего snapshot/contract поля и non-regular sealed path, idempotent
discard scratch/sealed state с повторным созданием, а также переход shared
capacity в `EXHAUSTED` и возврат в `ACCEPTING` после освобождения байтов.

Свежий полный `make verify` прошёл 25/25 за `02:22`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1402` reactor cases (`1394` passed,
`8` provisioned external skips). Фактический aggregate равен `19856/22390`
lines и `6261/8125` branches, а `ioc-adapter-store-jdbc` group — `5346/6013`
lines и `1390/1858` branches. `JdbcImportWorkspace` вырос с `142/216` до
`180/216` lines и с `43/92` до `66/92` branches без production-изменений.

Ratchet принимает только точную дельту целевого класса `+38` lines и `+23`
branches поверх прежних независимых minima: aggregate
`19849/22390 + 6245/8125`, `ioc-adapter-store-jdbc`
`5346/6013 + 1390/1858`. Missed-instruction context оставлен прежним, а
межпрогонный запас не принят. До aggregate `80%` по фактическому отчёту
остаётся `239` covered branches; fixed-floor gate остаётся отдельным финальным
slice.

### `TEST-COVERAGE-02` remediation checkpoint 19 — local import source ownership

Девятнадцатый remediation slice усиливает локальную transport-ownership
границу managed import. Пять новых integration tests проверяют replay уже
захваченного source, изменение и превышение размера кандидата, обе terminal
disposition ветви только после полной публикации protected unit, capability и
purge для известного source, malformed candidate tokens, duplicate source IDs,
пересечение trust roots и symbolic-link root. Все сценарии остаются offline и
не подменяют отдельное live SMB evidence.

Свежий полный `make verify` прошёл 25/25 за `02:37`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1407` reactor cases (`1399` passed,
`8` provisioned external skips). Фактический aggregate равен `19887/22390`
lines и `6286/8125` branches, а `ioc-adapter-ingest` group — `960/1189` lines
и `293/422` branches. `LocalManagedImportSourceLifecycle` вырос с `57/96` до
`76/96` branches без production-изменений.

Ratchet принимает только точно известную дельту целевого класса `+19` branches:
aggregate `19849/22390 + 6264/8125`, `ioc-adapter-ingest`
`929/1189 + 287/422`. Line minima и missed-instruction context намеренно не
ужесточены, а дополнительное исполнение snapshot-store и межпрогонный запас не
приняты. До aggregate `80%` по фактическому отчёту остаётся `214` covered
branches; fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 20 — local terminal evidence

Двадцатый remediation slice усиливает файловую границу terminal evidence для
managed import. Семь новых integration tests проверяют outcome-specific
публикацию полного JSON report с contract pin, детерминированной сортировкой и
экранированием управляющих символов, отсутствие и неоднозначность retained
source, ограничение размера replay, recovery после незавершённого `.part`,
missing/ambiguous/stale-target archive conflicts, пересечение archive path с
managed roots и idempotent удаление обоих terminal outcomes.

Свежий полный `make verify` прошёл 25/25 за `03:16`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1414` reactor cases (`1406` passed,
`8` provisioned external skips). Фактический aggregate равен `19910/22390`
lines и `6313/8125` branches, а `ioc-adapter-ingest` group — `983/1189` lines
и `319/422` branches. `LocalImportTerminalStore` вырос с `47/82` до `72/82`
branches; целевой класс теперь имеет `174/188` lines без production-изменений.

Ratchet принимает только точную дельту целевого класса `+25` branches:
aggregate `19849/22390 + 6289/8125`, `ioc-adapter-ingest`
`929/1189 + 312/422`. Line minima и missed-instruction context намеренно не
ужесточены, а дополнительный `+2` aggregate execution headroom не принят. До
aggregate `80%` по фактическому отчёту остаётся `187` covered branches;
fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 21 — export-slot registry integrity

Двадцать первый remediation slice усиливает целостность реестра стабильных
export slots. Шесть новых integration tests проверяют exact survivor и
unassigned-survivor rejection, duplicate lifecycle и inactive preferred
requests до мутации, exact high-water allocation, occupied fallback без free
ranges, повреждённый untracked low slot, исчерпание `Long.MAX_VALUE`, а также
инициализированность и полноту current snapshot. Все проверки выполняются через
реальный SQLite adapter и дополнительно подтверждают rollback без частичного
state/assignment commit.

Свежий полный `make verify` прошёл 25/25 за `04:57`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1420` reactor cases (`1412` passed,
`8` provisioned external skips). Фактический aggregate равен `19925/22390`
lines и `6328/8125` branches, а `ioc-adapter-store-jdbc` group — `5361/6013`
lines и `1406/1858` branches. `JdbcExportSlotRegistry` вырос с `95/128` до
`111/128` branches и теперь имеет `483/507` lines без production-изменений.

Ratchet принимает только точную дельту целевого класса `+16` branches:
aggregate `19849/22390 + 6305/8125`, `ioc-adapter-store-jdbc`
`5346/6013 + 1406/1858`. Line minima и missed-instruction context намеренно не
ужесточены; межпрогонный aggregate execution headroom также не принят. До
aggregate `80%` по фактическому отчёту остаётся `172` covered branches;
fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 22 — health CLI contract

Двадцать второй remediation slice закрывает пользовательский контракт
`health` CLI без обращения к реальному daemon. Четыре новых fast tests через
локальный JDK HTTP server проверяют default и явно переопределённые host/port,
blank URL/component fallback, malformed и statusless actuator responses,
generic invalid-URI failure, а также rendering пустых и вложенных map/list
details и неизвестного component shape. Проверяются observable exit codes,
запрошенный endpoint и stdout; приватные методы для обхода transport boundary
не вызываются.

Свежий полный `make verify` прошёл 25/25 за `04:29`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1424` reactor cases (`1416` passed,
`8` provisioned external skips). Фактический aggregate равен `19955/22390`
lines и `6356/8125` branches, а `ioc-adapter-cli-picocli` group — `443/564`
lines и `140/227` branches. `HealthCommand` вырос с `22/50` до `48/50`
branches и теперь имеет `106/107` lines без production-изменений.

Ratchet принимает только точную дельту целевого класса `+26` branches:
aggregate `19849/22390 + 6331/8125`, `ioc-adapter-cli-picocli`
`415/564 + 140/227`. Line minima и missed-instruction context намеренно не
ужесточены, а дополнительный `+2` aggregate execution headroom не принят. До
aggregate `80%` по фактическому отчёту остаётся `144` covered branches;
fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 23 — artifact-identity integrity

Двадцать третий remediation slice усиливает guardrail миграции canonical
identity. Шесть новых SQLite integration tests проверяют обязательный database
role, пустой и duplicate artifact batch, запрещённый downgrade epoch,
неизменяемость сохранённых record/match fingerprints, reserved migration-key
prefix, отсутствие alias для строк без lifecycle/match material и восстановление
удалённой match definition. Отказы дополнительно проверяют diagnostic reason и
сохранение прежнего epoch/row key после rollback.

Свежий полный `make verify` прошёл 25/25 за `04:45`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1430` reactor cases (`1422` passed,
`8` provisioned external skips). Фактический aggregate равен `19966/22390`
lines и `6368/8125` branches, а `ioc-adapter-store-jdbc` group — `5374/6013`
lines и `1420/1858` branches. `JdbcArtifactIdentityStore` вырос с `62/90` до
`76/90` branches и теперь имеет `269/277` lines без production-изменений.

Ratchet принимает только точную дельту целевого класса `+14` branches:
aggregate `19849/22390 + 6345/8125`, `ioc-adapter-store-jdbc`
`5346/6013 + 1420/1858`. Line minima и missed-instruction context намеренно не
ужесточены; два потерянных в этом прогоне нецелевых branch executions не
снижают причинную дельту, поскольку новый aggregate minimum остаётся на 23
ветви ниже фактического результата. До aggregate `80%` по фактическому отчёту
остаётся `132` covered branches; fixed-floor gate остаётся отдельным финальным
slice.

### `TEST-COVERAGE-02` remediation checkpoint 24 — publish-ledger recovery

Двадцать четвёртый remediation slice усиливает durable publish coordination.
Четыре новых SQLite integration tests проверяют отказ для non-pending record и
каждого изменяемого поля существующего slice/target binding, идемпотентный CAS
replay только при совместимом error/remote verification evidence, конфликт
stale writer, различение отсутствующей ledger pair, retry claim после FAILED,
IN_PROGRESS abandonment, пустой health target selection и blank lookup filters.

Свежий полный `make verify` прошёл 25/25 за `03:37`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1434` reactor cases (`1426` passed,
`8` provisioned external skips). Фактический aggregate равен `19980/22390`
lines и `6392/8125` branches, а `ioc-adapter-store-jdbc` group — `5385/6013`
lines и `1443/1858` branches. `JdbcPublishLedger` вырос с `25/49` до `48/49`
branches и теперь имеет `144/148` lines без production-изменений.

Ratchet принимает только точную дельту целевого класса `+23` branches:
aggregate `19849/22390 + 6368/8125`, `ioc-adapter-store-jdbc`
`5346/6013 + 1443/1858`. Line minima и missed-instruction context намеренно не
ужесточены, дополнительный `+1` aggregate execution headroom не принят. До
aggregate `80%` по фактическому отчёту остаётся `108` covered branches;
fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 25 — sync-health precedence

Двадцать пятый remediation slice закрывает независимую матрицу operational
health каналов. Четыре новых fast tests доказывают, что hard fetch, detection и
publish failures каждый самостоятельно дают `DOWN`, transient detection и
publish failures самостоятельно дают `DEGRADED`, accepted-work и dispatch
executor failures самостоятельно дают `DOWN`, а смешанные ACTIVE, DISABLED,
выполненные и NEVER_RUN endpoints остаются различимыми. Это устраняет прежнее
short-circuit маскирование между одновременно заполненными каналами.

Свежий полный `make verify` прошёл 25/25 за `04:11`. Lifecycle union остался
`191 fast + 65 integration`; выполнено `1438` reactor cases (`1430` passed,
`8` provisioned external skips). Фактический aggregate равен `19980/22390`
lines и `6419/8125` branches, а `ioc-app` group — `4443/5066` lines и
`1374/1842` branches. `SyncHealthIndicator` вырос с `126/157` до `151/157`
branches и теперь имеет `222/222` lines без production-изменений.

Ratchet принимает только точную дельту целевого класса `+25` branches:
aggregate `19849/22390 + 6393/8125`, `ioc-app`
`4442/5066 + 1371/1842`. Line minima и missed-instruction context намеренно не
ужесточены, дополнительный aggregate execution headroom не принят. До
aggregate `80%` по фактическому отчёту остаётся `81` covered branch;
fixed-floor gate остаётся отдельным финальным slice.

### `TEST-COVERAGE-02` remediation checkpoint 26 — aggregate floor boundary

Финальный remediation slice перед установкой fixed floors закрывает оставшиеся
ветви на шести реальных границах. CSV integration tests проверяют полную
идентичность slice plan, duplicate/reserved artifact names, запрет перезаписи
staging/published identity и незавершённый callback protocol. SQLite tests
проверяют limits и close-state import workspace, неоднозначные schema/allocator
catalogs, несовместимые public-ID команды и observation ownership/terminal
state. SMB fake-client integration покрывает path normalization, regular-object
listing/stat, replacement uncommitted directory и post-rename marker
verification. Новый local WatchService integration suite проверяет реальный
doorbell signal, idempotent start, isolation consumer failure, duplicate roots,
missing inbox и failure-safe close. Application compiler дополнительно фиксирует
collect-all malformed-reference validation и разрешённый authority contract для
related routing/formula preservation. Все сценарии deterministic offline; live
SMB evidence не подменялось.

Два последовательных полных `make verify` прошли 25/25. Финальный прогон занял
`02:38`; lifecycle union равен `191 fast + 66 integration`, выполнено `1456`
reactor cases (`1448` passed, `8` provisioned external skips). Фактический
aggregate равен `20084/22390` lines и `6503/8125` branches (`80.04%`). Domain
остался `239/243 + 108/110`, application достиг
`5124/5412 + 2080/2292` (`90.75%` branches). Целевые adapter groups:
CSV `1127/1294 + 424/560`, store JDBC `5405/6013 + 1461/1858`, SMB
`828/1096 + 334/510`, ingest `1041/1189 + 335/422`.

Ratchet принимает только причинные branch deltas: compiler `+16`, CSV `+9`,
JDBC workspace/writer `+18`, SMB transport `+25` и local watch source `+16`.
Итого aggregate minimum повышен с `6393` до `6477` covered branches;
application — до `2067`, CSV — до `424`, store JDBC — до `1461`, SMB — до
`334`, ingest — до `328`. Консервативные line minima, missed-branch и
instruction context сохранены; оставшиеся `26` фактически исполненных aggregate
ветвей не приняты как случайный baseline. Fixed floors теперь можно включить
отдельным атомарным build-policy slice.

### `TEST-COVERAGE-02` fixed-floor enforcement — 2026-09-04

Project-owned `CoverageVerifier` теперь независимо от ratchet применяет fixed
release floors к actual aggregate XML. Новый `coverage-floors.tsv` обязан иметь
явную запись для aggregate и всех 19 production groups: `none/none` означает
ratchet-only module, `reactor` фиксирует `75%` lines / `80%` branches, а
`ioc-domain` и `ioc-application` — `85% / 90%`. Сравнение выполняется точными
целочисленными дробями без округления отображаемого процента. Scope drift,
неполное включение пары floors, проценты вне `1..100` и отдельные aggregate/
module line/branch regressions покрыты synthetic harness.

Root `validate` прошёл: coverage harness содержит `2` happy paths и `28`
negative scenarios, policy reconciliation видит 25 reactor projects, 19
production groups, 18 required local reports, 1 downstream-only report и 3
active fixed-floor scopes. Прямой late-gate check над свежим полным report
принял `20084/22390` lines (`89.70%`) и `6503/8125` branches (`80.04%`);
domain равен `239/243 + 108/110`, application —
`5124/5412 + 2080/2292`. Ratchet и fixed floor остаются независимыми: более
сильный accepted baseline нельзя снизить до общего floor, а консервативный
aggregate ratchet не заменяет абсолютный `80%` gate.

Canonical `make verify` с новым Maven wiring прошёл 25/25 за `02:32`; late gate
принял повторный результат `20085/22390` lines (`89.71%`) и `6504/8125`
branches (`80.05%`), SpotBugs — `116 accepted / 0 visible`, CPD — `21/21`.
Дополнительная условно исполненная ветвь не переносится в ratchet. После
committed-HEAD повтора work item получает состояние `verified`; остальные
`R030-TEST` items и Codecov/branch-policy зависимости `R030-BUILD` остаются
отдельными.

## `TEST-REGEX-03` implementation evidence — 2026-09-05

Коммит `5d477fb3` добавляет локальный `@ContractTest` в
`adapter-regex-re2j` и исполняет единый контракт против `Re2jPatternEngine` и
`JdkRegexPatternEngine`. Контракт фиксирует стабильные engine IDs, compile-once
reuse, поддержку `CharSequence`, упорядоченные exact half-open spans и пустой
результат без совпадения. Модуль теперь сам владеет JaCoCo evidence вместо
downstream-only disposition; его actual и ratchet равны `18/18` lines и `4/4`
branches, missed instructions — `0`.

Коммит `51180ff3` добавляет bootstrap contract. Он связывает живой classpath
`application.yml`, подтверждает default `re2j` и override `ioc.engine=jdk`, а
также прогоняет все шесть настроенных IOC patterns и оба Unicode section-marker
patterns через оба движка с положительными и отрицательными fixtures и exact
spans. Таким образом corpus остаётся RE2-compatible и не вводит JDK-only
синтаксис. Ветка `AppConfig.patternEngine` теперь покрыта полностью (`2/2`), а
консервативные minima подняты только на причинные `+1` line / `+1` branch для
aggregate и `ioc-app`.

Committed-HEAD `make verify` прошёл 25/25 за `02:26`. Lifecycle verifier
подтвердил `193 fast`, `66 integration`, `5 external` и `254 deterministic
offline` suites; выполнено `1464` cases (`1456` passed, `8` provisioned
external skips). Late coverage gate принял 18 local reports и aggregate
`20096/22390` lines (`89.75%`) плюс `6506/8125` branches (`80.07%`). `ioc-app`
равен `4444/5066 + 1375/1842`; SpotBugs остался `116 accepted / 0 visible`,
CPD — `21/21`. Production Java не менялся. Оба compiled implementations
переиспользуемы, а matcher state создаётся внутри каждого `findAll`; новый
shared mutable state, worker lifecycle или performance-sensitive loop не
появились, поэтому отдельные concurrency/timing tests не обоснованы.

## Historical Wave 0 baseline discovery inventory

The remainder of this section preserves the accepted pre-DATA snapshot and its
original classifications. Use the current-HEAD refresh above for new work.

Captured for `BASE-TESTS-04` from tracked `src/test/**/*.java` files and
Surefire XML reports produced by the fresh `make verify` baseline. Modules are
enumerated from the root POM; ignored `target/` directories outside that reactor
are excluded.

`Source Java` counts all tracked Java files under a module's `src/test`.
`Support Java` counts non-suite helpers. `Suites` and case results come from the
module's `target/surefire-reports/TEST-*.xml`.

| Module | Source Java | Support Java | Suites | Cases | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|---:|---:|
| `platform/platform-errors` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `platform/platform-diagnostics` | 10 | 0 | 10 | 33 | 0 | 0 | 0 |
| `platform/platform-etl` | 2 | 0 | 2 | 15 | 0 | 0 | 0 |
| `platform/platform-events` | 3 | 0 | 3 | 11 | 0 | 0 | 0 |
| `platform/platform-concurrency` | 2 | 0 | 2 | 11 | 0 | 0 | 0 |
| `platform/platform-observability` | 6 | 0 | 6 | 21 | 0 | 0 | 0 |
| `platform/platform-diagnostics-logging` | 4 | 0 | 4 | 37 | 0 | 0 | 0 |
| `core/ioc-domain` | 5 | 0 | 5 | 27 | 0 | 0 | 0 |
| `core/ioc-application` | 38 | 2 | 36 | 157 | 0 | 0 | 0 |
| `core/ioc-application-tck` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `adapters/adapter-regex-re2j` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `adapters/adapter-psl` | 1 | 0 | 1 | 11 | 0 | 0 | 0 |
| `adapters/adapter-source-tika` | 3 | 0 | 3 | 7 | 0 | 0 | 0 |
| `adapters/adapter-sink-csv` | 10 | 0 | 10 | 47 | 0 | 0 | 0 |
| `adapters/adapter-manifest-json-jackson` | 1 | 0 | 1 | 6 | 0 | 0 | 0 |
| `adapters/adapter-store-jdbc` | 15 | 0 | 15 | 75 | 0 | 0 | 0 |
| `adapters/adapter-transport-smb` | 8 | 0 | 8 | 34 | 0 | 0 | 2 |
| `adapters/adapter-ingest` | 6 | 0 | 6 | 29 | 0 | 0 | 0 |
| `adapters/adapter-cli-picocli` | 7 | 0 | 7 | 34 | 0 | 0 | 0 |
| `bootstrap/ioc-app` | 52 | 0 | 52 | 226 | 0 | 0 | 0 |
| **Reactor total** | **173** | **2** | **171** | **781** | **0** | **0** | **2** |

The two support files are
`core/ioc-application/.../export/ExportFixtures.java` and
`core/ioc-application/.../pipeline/stage/StageTestSupport.java`. Every tracked
top-level `*Test.java` in the declared reactor has a matching Surefire suite,
and no current-reactor suite lacks a matching source file.

One ignored report under the removed, non-reactor
`adapters/adapter-lookup-csv/target/` directory explains the previously recorded
repository-wide overcount of one suite and one case. It is excluded rather than
treated as baseline evidence.

## Observed lifecycle classification

This is a primary, disjoint classification of the 171 discovered suites by the
behavior they exercise, not by their current file names. The baseline uses the
following tests:

- `unit/component`: no real database, parser/content fixture, framework
  application context, network fixture or production file persistence; fakes
  and in-memory collaborators remain in this cohort;
- `integration`: real embedded SQLite/Hikari, filesystem persistence, Tika
  parsing, Jackson serialization, Spring context/HTTP management surface or
  Logback configuration;
- `contract/TCK`: a reusable port contract or an explicit format/transport/stage
  contract is the suite's primary purpose;
- `architecture`: ArchUnit, bytecode/reference ratchet or structural
  architecture invariant;
- `publication/consumer`: generated build metadata, published catalog/docs,
  release-note or Maven/documentation convention contract;
- `E2E/golden`: a complete supported workflow crossing composition, storage and
  projection boundaries.

| Module | Unit/component | Integration | Contract/TCK | Architecture | Publication/consumer | E2E/golden | Total |
|---|---:|---:|---:|---:|---:|---:|---:|
| `platform/platform-errors` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `platform/platform-diagnostics` | 9 | 0 | 0 | 0 | 1 | 0 | 10 |
| `platform/platform-etl` | 2 | 0 | 0 | 0 | 0 | 0 | 2 |
| `platform/platform-events` | 3 | 0 | 0 | 0 | 0 | 0 | 3 |
| `platform/platform-concurrency` | 2 | 0 | 0 | 0 | 0 | 0 | 2 |
| `platform/platform-observability` | 5 | 0 | 0 | 0 | 1 | 0 | 6 |
| `platform/platform-diagnostics-logging` | 4 | 0 | 0 | 0 | 0 | 0 | 4 |
| `core/ioc-domain` | 4 | 0 | 0 | 1 | 0 | 0 | 5 |
| `core/ioc-application` | 34 | 0 | 1 | 1 | 0 | 0 | 36 |
| `core/ioc-application-tck` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `adapters/adapter-regex-re2j` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `adapters/adapter-psl` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `adapters/adapter-source-tika` | 0 | 2 | 1 | 0 | 0 | 0 | 3 |
| `adapters/adapter-sink-csv` | 5 | 5 | 0 | 0 | 0 | 0 | 10 |
| `adapters/adapter-manifest-json-jackson` | 0 | 1 | 0 | 0 | 0 | 0 | 1 |
| `adapters/adapter-store-jdbc` | 1 | 12 | 2 | 0 | 0 | 0 | 15 |
| `adapters/adapter-transport-smb` | 6 | 1 | 1 | 0 | 0 | 0 | 8 |
| `adapters/adapter-ingest` | 0 | 5 | 1 | 0 | 0 | 0 | 6 |
| `adapters/adapter-cli-picocli` | 7 | 0 | 0 | 0 | 0 | 0 | 7 |
| `bootstrap/ioc-app` | 31 | 12 | 0 | 2 | 5 | 2 | 52 |
| **Reactor total** | **114** | **38** | **6** | **4** | **7** | **2** | **171** |

The integration cohort is deliberately behavior-based:

- five file-ingest adapter suites, five filesystem CSV adapter suites, two Tika
  suites, one Jackson suite, twelve SQLite/JDBC suites and one
  `SmbFileTransportTest`;
- twelve bootstrap suites exercising Spring contexts, management HTTP,
  SQLite/recovery or Logback configuration.

The six contract/TCK suites are `FileIngestionLedgerTest`,
`TikaSourceReaderFormatContractTest`, `JdbcExportRunLedgerContractTest`,
`JdbcIngestionLedgerTest`, `SmbChangeNotifyContractTest` and
`StageContractTest`. Architecture, publication and E2E cohorts are kept
separate because their runtime cost and failure meaning differ from ordinary
unit and integration failures.

### Current Maven execution

| Signal | Baseline |
|---|---|
| Test stack | JUnit Jupiter `6.0.3`; AssertJ `3.27.7` |
| Surefire | `3.5.6`; default discovery; all 171 suites run in Maven `test` |
| Module override | `bootstrap/ioc-app` only supplies `test.project.version` to Surefire |
| Failsafe | Version `3.5.6` is managed by the Spring Boot parent, but the project declares no execution |
| Integration naming | No tracked `IT*`, `*IT` or `*ITCase` Java source |
| Failsafe reports | None |

Consequently, the current `test` phase is a mixed full offline suite rather than
a fast unit lifecycle. The 38 integration suites and the E2E/contract cohorts
cannot yet be selected reliably by Maven lifecycle. Naming/tag migration and
Failsafe binding belong to `R030-TEST`; this baseline does not rename or move
tests.

## Tags, conditions and skips

| Signal | Baseline | Evidence/disposition |
|---|---|---|
| `@Tag` / `@Tags` | 0 sources | Accepted vocabulary exists only in the goal contract; selection is not implemented |
| Composed test annotations | 0 sources | No `@IntegrationTest`, `@ContractTest` or equivalent |
| Maven/JUnit tag filters | None | No groups, excluded groups or include/exclude tag configuration in POM, Makefile or CI |
| `@Disabled` | 0 sources | No disabled suite or case |
| JUnit assumptions | 0 sources | No assumption-driven hidden skip |
| Conditional execution | 1 suite / 2 cases | `SmbChangeNotifyContractTest` uses class-level `@EnabledIfSystemProperty` |
| Baseline result | 779 passed / 2 skipped | Both skips report missing `ioc.smb.contract`; no other skip |
| Automatic test retry | None | No Surefire rerun count or retry wrapper found |
| Regular CI | Full offline `./mvnw -B -ntp -T 1C verify` | No test skip/filter flag; external SMB properties are not provisioned |

The live SMB suite requires `ioc.smb.contract=true` plus host, share,
credentials and remote path system properties. Its module README provides a
manual command, but there is no project-owned SMB test environment or dedicated
CI job. The suite creates and deletes a uniquely named remote file, exercises
`CHANGE_NOTIFY`, and includes an idle-period scenario. Its two baseline skips
are therefore intentional external unavailability, not quarantine and not
passing evidence.

The absence of tags means the accepted taxonomy cannot yet select the 38
integration suites, contract/TCK, architecture, E2E or external cohorts.
Introducing the controlled tags, composed annotations and convention check is
an `R030-TEST` implementation item; this gate only records the current state.

## Duration baseline

A complete warm-output `make verify` on commit `f4cdd06` passed at
`2026-07-27T14:56:21Z`. Maven reported `34.758 s` wall-clock and
`/usr/bin/time` reported `35.74 s` real, `132.85 s` user and `8.00 s` system.
This is a full test execution with already compiled outputs, not a replacement
for the clean-build `39.210 s` baseline.

Surefire suite durations are summed only within the declared reactor. Their
`39.815` suite-seconds are useful for locating test cost, but are not build
wall-clock: Maven runs modules concurrently with `-T 1C`.

| Module | Suites | Suite-seconds |
|---|---:|---:|
| `platform/platform-errors` | 0 | 0.000 |
| `platform/platform-diagnostics` | 10 | 0.278 |
| `platform/platform-etl` | 2 | 0.184 |
| `platform/platform-events` | 3 | 0.257 |
| `platform/platform-concurrency` | 2 | 0.281 |
| `platform/platform-observability` | 6 | 0.339 |
| `platform/platform-diagnostics-logging` | 4 | 0.340 |
| `core/ioc-domain` | 5 | 1.076 |
| `core/ioc-application` | 36 | 1.005 |
| `core/ioc-application-tck` | 0 | 0.000 |
| `adapters/adapter-regex-re2j` | 0 | 0.000 |
| `adapters/adapter-psl` | 1 | 0.383 |
| `adapters/adapter-source-tika` | 3 | 2.940 |
| `adapters/adapter-sink-csv` | 10 | 2.494 |
| `adapters/adapter-manifest-json-jackson` | 1 | 1.216 |
| `adapters/adapter-store-jdbc` | 15 | 8.233 |
| `adapters/adapter-transport-smb` | 8 | 1.767 |
| `adapters/adapter-ingest` | 6 | 1.145 |
| `adapters/adapter-cli-picocli` | 7 | 1.423 |
| `bootstrap/ioc-app` | 52 | 16.454 |
| **Reactor suite sum** | **171** | **39.815** |

`bootstrap/ioc-app` accounts for 41.3% and `adapter-store-jdbc` for 20.7% of
suite-seconds; together they account for 62.0%.

### Slowest suites

| Suite | Module | Seconds |
|---|---|---:|
| `JdbcLedgerDaemonRuntimeModeTest` | `bootstrap/ioc-app` | 4.429 |
| `ArchitectureTest` | `bootstrap/ioc-app` | 2.180 |
| `IocPropertiesBindingTest` | `bootstrap/ioc-app` | 2.115 |
| `JdbcPublishLedgerTest` | `adapters/adapter-store-jdbc` | 1.906 |
| `CsvArtifactSliceWriterTest` | `adapters/adapter-sink-csv` | 1.562 |
| `TikaSourceReaderDiagnosticTest` | `adapters/adapter-source-tika` | 1.475 |
| `DataframeSchemaReconcilerTest` | `adapters/adapter-store-jdbc` | 1.219 |
| `JacksonSliceManifestCodecTest` | `adapters/adapter-manifest-json-jackson` | 1.216 |
| `TikaSourceReaderFormatContractTest` | `adapters/adapter-source-tika` | 1.192 |
| `DaemonManagementEndpointTest` | `bootstrap/ioc-app` | 1.159 |

### Slowest cases

| Test case | Module | Seconds |
|---|---|---:|
| `ArchitectureTest#layers_point_inward` | `bootstrap/ioc-app` | 1.930 |
| `TikaSourceReaderDiagnosticTest#preserves_io_failure_as_typed_read_failure` | `adapters/adapter-source-tika` | 1.244 |
| `JdbcPublishLedgerTest#countsStatusesWithSelectionFilters` | `adapters/adapter-store-jdbc` | 0.944 |
| `DomainBoundaryTest#capabilities_are_acyclic` | `core/ioc-domain` | 0.917 |
| `ConnectTimeoutSocketFactoryTest#appliesConfiguredTimeoutToTcpConnect` | `adapters/adapter-transport-smb` | 0.899 |
| `CatalogReferenceRatchetTest#every_catalog_constant_is_referenced_or_explicitly_allowlisted` | `bootstrap/ioc-app` | 0.711 |
| `JdbcLedgerDaemonRuntimeModeTest#daemon_context_can_select_jdbc_ingestion_ledger` | `bootstrap/ioc-app` | 0.555 |
| `TikaSourceReaderFormatContractTest#reads_text_from_docx` | `adapters/adapter-source-tika` | 0.541 |
| `TikaSourceReaderFormatContractTest#reads_text_from_pdf` | `adapters/adapter-source-tika` | 0.525 |
| `ExportCommandTest#delegatesCompleteRecoveryAndExportUseCase` | `adapters/adapter-cli-picocli` | 0.430 |

Suite time includes class/framework lifecycle that is not necessarily attributed
to an individual case. For example, `JdbcLedgerDaemonRuntimeModeTest` reports
`4.429 s`, while its single case reports `0.555 s`; the remaining `3.874 s` is
context lifecycle overhead. `JacksonSliceManifestCodecTest` and
`DaemonManagementEndpointTest` similarly show about `0.9 s` of non-case
overhead. These are lifecycle-separation and trend signals, not standalone
performance defects.

## Wait, isolation and flake baseline

### Pre-remediation static wait inventory

| Signal | Count/scope | Assessment |
|---|---|---|
| `Thread.sleep` | 6 calls in 5 files | All are inside a bounded polling/semantic scenario; no unbounded fixed sleep |
| Timed await calls | 60 calls | Positive bounded coordination |
| Timed `Future.get` | 25 calls | Positive bounded result wait |
| Unbounded latch/barrier awaits | 11 calls in 10 files | Failure-path hang/poor-diagnostics risk |
| Timed thread joins | 9 calls in 5 files | Only the interruption-specific case explicitly asserts termination |
| JUnit `@Timeout` / timeout assertions | 0 | No test-level safety net |
| Surefire fork timeout | Not configured | No project-owned process-level safety net |

The fixed sleeps have bounded context:

- `SmbChangeNotifyWatcherTest`, `RemoteFetchDetectionCoordinatorTest` and
  `RemoteChangeWatchLifecycleTest` poll an observable condition every `10 ms`
  with a `2 s` deadline;
- `IngestFileListFilterTest` polls WatchService delivery at `10 ms`, at most
  100 attempts;
- the external `SmbChangeNotifyContractTest` uses a semantic `3 s` idle interval
  to exceed a `2 s` SMB request timeout, then condition polling at `50 ms` with
  a `10 s` deadline.

The local bounds make these sleeps reviewable; they do not provide a whole-test
upper bound. The external three-second wait is part of the behavior under test.
The four offline polling loops remain candidates for deterministic
coordination where a directly observable latch/future can replace polling.

Ten bare `await()` calls occur in:

- `PipelineDiagnosticMdcTest`;
- `JdbcExportRunLedgerTest` and `JdbcSnapshotSliceReaderTest`;
- `DaemonExportSchedulerTest`, `DaemonFetchSchedulerTest`,
  `DaemonPublishSchedulerTest`, `DaemonSliceRetentionSchedulerTest`,
  `PeriodicDaemonCycleTest` and `RemoteFetchDetectionCoordinatorTest`.

Most are worker-side gates controlled by the test thread, but
`JdbcExportRunLedgerTest` also waits indefinitely for both workers to become
ready. Several scheduler tests release a worker only after an assertion and
then call `join(1000)` without asserting that the thread terminated. If an
early assertion fails, cleanup may leave a non-daemon worker blocked; if a
worker never reaches the gate, the controller may hang. The remediation must
combine bounded coordination, `finally` release/cancellation, explicit
termination assertions and a test-level timeout safety net. A retry is not an
acceptable fix.

### `TEST-WAITS-04` implementation evidence — 2026-09-05

The remediation preserves the six reviewed sleeps in five files: four offline
polling loops remain bounded by one- or two-second deadlines, while the live SMB
contract retains its semantic three-second idle interval and ten-second polling
deadline. Each polling failure now identifies the condition that did not become
observable. No retry or lifecycle reclassification was introduced.

The current source inventory has:

- 68 `.await(` call sites, all bounded directly or through the bootstrap test
  helper;
- 29 timed `Future.get` calls and no unbounded future result wait; the four
  unbounded calls in `PipelineDiagnosticMdcTest` and `JdbcExportRunLedgerIT`
  were an additional live-code gap beyond the original ledger inventory;
- zero bare latch/barrier `await()` calls;
- two bounded `Thread.join` operations, both isolated in `AsyncTestSupport` and
  followed by an explicit worker-liveness assertion;
- 14 wait-bearing suites with class-level JUnit safety boundaries: 30 seconds
  for offline suites and 60 seconds for the provisioned SMB contract.

Bootstrap scheduler/coordinator tests use `AsyncTestSupport.Worker` so closing
the fixture releases its worker gate, performs a bounded join and propagates a
captured worker failure. JDBC and MDC concurrency tests release gates from
`finally`, use timed future results and assert executor termination. SMB watcher
tests close their `RemoteChangeWatch` from try-with-resources even when a polling
assertion fails; the production watch close contract cancels pending I/O and
fails if its worker does not terminate within the configured close timeout.

No fixed Surefire/Failsafe fork timeout is adopted for 0.3.0. The fork may own
multiple suites and explicitly provisioned external work, so a single deadline
would obscure the responsible test and can kill valid evidence. The accepted
upper-bound contract is instead local timed coordination plus per-suite JUnit
timeouts and transport-owned I/O timeouts. A reproducible hang that ignores
these interruption boundaries is the exit condition for reconsidering a process
watchdog; this disposition is not permission for an unbounded primitive.

Focused qualification passed 50 bootstrap scheduler/coordinator cases, 4 MDC
cases, 22 JDBC cases, 11 SMB watcher cases, 3 ingest WatchService cases and the
10-case bootstrap polling selection. `SmbChangeNotifyContractIT` compiled but
was not executed without a provisioned live share; that evidence remains owned
by `TEST-EXTERNAL-05` and is not claimed as a pass here.

### Isolation inventory

- 36 suites use JUnit `@TempDir`; the management endpoint uses a random port.
- Fixed `target/` directories used by golden/daemon/logback suites are cleaned
  before use; `OnDemandExportIntegrationTest` uses a UUID-qualified root.
- `ExtractCommandTest` and `HealthCommandTest` temporarily replace
  `System.out` and restore it in `finally`. JUnit parallel execution is
  currently disabled, so no current collision was reproduced. Before enabling
  in-fork parallel execution, these tests need a resource lock or removal of
  global stream mutation.
- No JUnit `@ResourceLock`/`@Isolated`, static system-property mutation or
  fixed application port was found.

### Bounded repeat

The following 15 suites were run in five independent fixed-order Maven
invocations across seven selected modules:

`PipelineDiagnosticMdcTest`, `BoundedKeyedSerialExecutorTest`,
`IngestFileListFilterTest`, `NioExportOperationGuardTest`,
`JdbcExportRunLedgerTest`, `JdbcSnapshotSliceReaderTest`,
`SmbChangeNotifyWatcherTest`, `OnDemandExportIntegrationTest`,
`DaemonExportSchedulerTest`, `DaemonFetchSchedulerTest`,
`DaemonPublishSchedulerTest`, `DaemonSliceRetentionSchedulerTest`,
`PeriodicDaemonCycleTest`, `RemoteFetchDetectionCoordinatorTest` and
`RemoteChangeWatchLifecycleTest`.

Each invocation executed 85 cases. Result: **5/5 invocations passed**, 425 case
executions, 0 failures, 0 errors and 0 skips. No retry, random order or external
fixture was used. This is a limited non-reproduction signal only; it does not
close the static bounded-wait findings and is not the scheduled random-order
pilot required by `R030-TEST`.

### `TEST-EXTERNAL-05` live SMB evidence — 2026-09-05

The focused Failsafe execution ran `SmbChangeNotifyContractIT` from commit
`404ab5f35fd39c6d515c6de0b0cff830a61094d1` against the provisioned Windows-host
SMB service reached from WSL through `127.0.0.1:445`, share `test-share`, root
`import`. The server reported OS version `10.0`; the client used
`encryption=required`. This identifies the tested stand but does not qualify a
production Windows Server or NAS family and does not change the separate
ADR-0025 H5/`OPS-8` disposition.

The exact selection was:

```text
./mvnw -pl adapters/adapter-transport-smb -am \
  -Dskip.unit.tests=true \
  -Dit.test=SmbChangeNotifyContractIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dioc.smb.contract=true \
  -Dioc.smb.host=127.0.0.1 \
  -Dioc.smb.port=445 \
  -Dioc.smb.share=test-share \
  -Dioc.smb.encryption=required \
  -Dioc.smb.remotePath=import \
  verify
```

Credentials were supplied only through the process environment and are absent
from the command and reports. The result was:

| Case | Result | Duration | Contract evidence |
|---|---|---:|---|
| `watcherSurvivesIdleLongerThanRequestTimeoutBeforeSignal` | passed | 4.432 s | One established watch survived the 3 s idle interval beyond its 2 s request timeout, emitted no premature signal/failure and signalled after upload |
| `watcherSignalsWhenFileIsCreatedOnLiveShare` | passed | 1.045 s | An established live directory watch signalled after the UUID-qualified file upload and reported no failure |
| **Suite** | **2 passed, 0 failed/errors/skips** | **5.498 s** | 60 s suite boundary and 10 s diagnosable polling deadlines remained in force |

The generated Failsafe XML SHA-256 captured before the next offline run was
`6101d11eb6178f554b307c842ac3e7bacc03372d8f686c0325a9207123842f23`.
It contained no `ioc.smb.username`, `ioc.smb.password` or SMB credential
environment-variable markers. A post-run encrypted listing found no
`codex-change-notify-*` leftovers under the remote root.

Before the qualifying run, the external test fixture was hardened so credentials
are resolved from complete environment pairs and never forwarded through Maven
`-D` properties, because Failsafe serializes JVM properties into retained XML.
Three focused resolver cases and all 58 fast SMB-module cases passed. The durable
SMB runbook now uses the Failsafe `verify` lifecycle and exact `-Dit.test`
selectors; its former `test` phase could not execute `*IT` suites after
`TEST-LIFECYCLE-01`.

The five external suite shells remain explicitly property-conditioned and
outside the deterministic offline cohort. A normal release `verify` is therefore
expected to report their eight skips; that remains lifecycle evidence, not a
contradiction of this separately provisioned result. This work item claims only
the two `CHANGE_NOTIFY` cases. Encryption-only, two-identity hardening and load
profile evidence keep their independent owners and dispositions.

This target-scoped result closes `TEST-EXTERNAL-05`; it does not claim that the
current bring-your-own SMB fixture is a general external-environment platform.
The capability-driven suite registry, provisioning/lease lifecycle, exact
executed-count gate, failure classification, evidence bundle and verified
cleanup needed before adding feed, Prometheus, Elasticsearch or other external
families are tracked separately as
[`TOOL-4`](../../../KNOWN-ISSUES.md#9-developer-tooling--build-tool). That
post-release seam neither reopens this evidence nor changes the next accepted
`R030-TEST` queue item.

## Coarse effectiveness baseline

This gate is a navigation/risk review, not coverage or mutation measurement.
Static assertion counts cannot prove that an oracle is correct, and broad
suite counts cannot prove that critical behavior is complete.

Positive signals:

- every one of the 171 suite source files contains an explicit AssertJ/JUnit
  assertion or delegates to an assertion-bearing helper; no assertion-free
  suite source was found;
- 61 suite sources use exception assertion APIs, including 140
  `assertThatThrownBy` calls and specialized exception assertions;
- tests use real value objects, explicit fakes and embedded/filesystem
  integrations; no direct Mockito API usage was found;
- the two reusable TCK classes are executed by three consumers:
  `JdbcExportRunLedgerContractTest`, `JdbcIngestionLedgerTest` and
  `FileIngestionLedgerTest`;
- recovery, retry, partial-failure, concurrency, idempotency, migration,
  architecture, publication and two E2E/golden workflows all have identifiable
  suites;
- existing conventional JUnit tests provide seeds for invariant-oriented work
  around refang, normalization, classification, deduplication and artifact
  identity.

One individual method,
`CollectingDiagnosticSinkTest#noop_sink_discards_diagnostics`, uses successful
return as an implicit no-throw oracle rather than an explicit assertion. Its
contract is narrow and adjacent null rejection is asserted; making the
no-throw intent explicit is a readability improvement, not a release blocker.

### Module/test-consumer interpretation

Three production modules have no local `src/test` suite:

- `core/ioc-application-tck` is intentionally test-support; its contracts are
  executed by the three adapter consumers above;
- `platform/platform-errors` contains only the root exception type. Dedicated
  constructor tests would add little behavioral evidence; coverage and
  downstream use should determine whether any real gap exists;
- `adapters/adapter-regex-re2j` contains the RE2/J and JDK pattern-engine
  implementations. RE2/J is exercised downstream by
  `RegexIndicatorExtractorTest` and `MarkerSourceAttributorTest`, but the JDK
  engine is not referenced by any behavioral test.

Downstream execution means a zero local-suite count is not automatically zero
coverage. `BASE-COVERAGE-05` must measure cross-module execution before any
coverage disposition.

### Effectiveness gaps

The supported `ioc.engine` selector exposes both `re2j` and `jdk`, and
`AppConfig.patternEngine` selects between the two. Current binding tests accept
the selector, but no test:

- executes a common `PatternEngine` contract against both implementations;
- proves compatible patterns produce equivalent matches/spans on the accepted
  corpus;
- proves `ioc.engine=jdk` wires `JdkRegexPatternEngine` in the composition root.

This is a real contract/compatibility gap because JDK is a documented runtime
option. `R030-TEST` should add an adapter-level shared contract for both engines
and a focused bootstrap selection test. It should not introduce
JDK-only patterns: the project contract remains RE2-compatible patterns for
either engine.

The repository also has internal publication/convention tests but no
standalone published-artifact consumer outside reactor resolution. That is
expected before the library extraction goal, but `R030-LIB`/`R030-TEST` must
provide the standalone consumer described by the release contract once public
library coordinates are finalized.

`TEST-PILOTS-06` подтвердил invariant-oriented tests отдельным domain PIT и
seeded random-order/repeat прогоном. Итоговые signal/noise/cost и adoption
решения находятся в разделе Diagnostic pilots; `BASE-TESTS-04` по-прежнему не
выводит mutation effectiveness из количества assertions.

## Instrumentation

| Control | Version/config | Local command | CI evidence | State |
|---|---|---|---|---|
| JaCoCo agent/report | `0.8.15`; inherited `prepare-agent` + module `report` | `make verify` | 17 local report pairs + 19-group aggregate | `blocking-integrity` |
| JaCoCo per-module check | Exact 19-module line/branch ratio ratchets; absolute missed-branch and small-module instruction context | `make verify` | Project-owned snapshot and late gate | `blocking-ratchet` |
| JaCoCo aggregate check | Exact ratio/missed-branch ratchet plus fixed `75% / 80%` floor | `make verify` | Project-owned aggregate XML/integrity/floor gate | `blocking` |
| Surefire fast lifecycle | `3.5.6`; default naming; isolated via `skip.unit.tests` | `make test` / `make test-fast` / `make verify` | 195 suites / 1022 cases | `verified` |
| Failsafe integration lifecycle | `3.5.6`; `integration-test` + `verify`; default IT naming | `make test-integration` / `make verify` | 66 suites / 462 cases / 8 external skips | `verified` |
| JUnit tag convention | Six accepted tags; five shared composed annotations; no regular filters | root `validate` / `make verify` | Source-count and exact report-union verifier | `verified` |
| PIT domain pilot | `pitest-maven 1.30.0`; JUnit 5 plugin `1.2.3`; `DEFAULTS`, one thread, zero score thresholds | `make mutation-pilot` | Stable domain HTML/XML + machine-readable summary; weekly/manual workflow | `verified-diagnostic` |
| Seeded stability pilot | Surefire/Failsafe/JUnit random class/method order; base seed `42`, three sequential functional-reactor repeats | `make stability-pilot` | Per-seed XML + exact report-union result; weekly/manual workflow | `verified-diagnostic` |
| Codecov best-effort upload | TBD | N/A | TBD | `planned` |
| Codecov project/patch signals | TBD | N/A | TBD | `planned` |
| Coverage/test artifacts | Surefire/Failsafe XML + module and aggregate JaCoCo HTML/XML | `make verify` | Always-upload artifact, 30-day retention | `verified-retention` |

### `BASE-COVERAGE-05` tooling and universe decision

Captured at `2026-07-28` on baseline head `1a453ed`.

- The [official JaCoCo release index](https://www.jacoco.org/jacoco/) lists
  `0.8.15` (released `2026-06-04`) as the latest stable release. The newer
  `0.8.16-SNAPSHOT` development build is not a release and is not selected.
- The production universe contains 19 modules: all seven `platform/*` modules,
  `core/ioc-domain`, `core/ioc-application`, all nine `adapters/*` modules and
  `bootstrap/ioc-app`.
- `core/ioc-application-tck` is outside the production denominator because it
  is a test-support publication artifact whose contracts execute in adapter
  consumers.
- No class/package exclusion is accepted at bootstrap. Any generated or
  technical-bytecode candidate must be observed in the first report and receive
  a selector, rationale, owner, denominator impact and review condition before
  exclusion.
- A dedicated non-production report module will depend explicitly on every
  production module and execute last in the dependency-ordered reactor. This is
  required because the root Maven aggregator does not make its listed modules
  dependencies and therefore cannot produce a complete `report-aggregate` by
  aggregation alone.
- Module-local reports will describe execution by that module's own test JVMs.
  Per-module release measurements will be read from groups in the reactor
  aggregate so that downstream integration/E2E execution of upstream code is
  retained.

This work item adds reports only. JaCoCo `check`, thresholds, ratchets, Codecov,
test additions and coverage-gap remediation remain outside
`BASE-COVERAGE-05`.

### Instrumentation verification

A clean full-reactor run on the instrumentation worktree based on `9aa8d1f`
completed successfully:

| Signal | Result |
|---|---|
| Command | `make clean && /usr/bin/time make verify` |
| Reactor | 22/22 projects `SUCCESS` (21 previous projects + build-only `ioc-coverage-report`) |
| Tests | 171 suites / 781 cases; 0 failures, 0 errors, 2 external SMB skips |
| Maven wall-clock | `57.585 s` |
| Process timing | `58.74 s` real, `282.35 s` user, `16.60 s` system |
| Local execution/report files | 17 `target/jacoco.exec` and 17 module-local HTML/XML reports |
| Aggregate | 19 production groups, 506 analyzed classes |
| Aggregate artifacts | HTML `index.html` (`15962` bytes), XML `jacoco.xml` (`1671017` bytes) |

The 17 local reports are expected: `platform-errors` and
`adapter-regex-re2j` have no local test JVM, while `ioc-application-tck` is
test support. The aggregate nevertheless contains both production modules and
credits execution from downstream test JVMs. It contains no
`com/iocextractor/application/tck/*` class and no `*Test` class.

Compared with the earlier clean no-instrumentation Maven wall-clock of
`39.210 s`, this run adds `18.375 s` (`46.9%`). This is one environment-local
observation rather than an accepted performance budget; subsequent warm and CI
measurements may separate agent/report cost from ordinary run variance.

## Coverage baseline и ratchet

Authoritative module measurements below are groups from the reactor aggregate,
not module-local reports. Therefore they retain execution of upstream code by
downstream integration and E2E tests.

| Module/scope | Classes | Missed instructions | Lines covered/total | Line | Branches covered/total | Branch | Missed branches | Release floor | State |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| **Reactor aggregate** | **506** | **6416** | **9574/10962** | **87.34%** | **2774/3998** | **69.38%** | **1224** | `75% / 80%` | `measured; branch gap` |
| `platform/platform-errors` | 1 | 0 | 4/4 | 100.00% | 0/0 | N/A | 0 | baseline | `measured` |
| `platform/platform-diagnostics` | 28 | 61 | 427/436 | 97.94% | 54/72 | 75.00% | 18 | baseline | `measured` |
| `platform/platform-etl` | 10 | 75 | 159/175 | 90.86% | 15/22 | 68.18% | 7 | baseline | `measured` |
| `platform/platform-events` | 4 | 0 | 41/41 | 100.00% | 12/14 | 85.71% | 2 | baseline | `measured` |
| `platform/platform-concurrency` | 11 | 59 | 153/172 | 88.95% | 33/46 | 71.74% | 13 | baseline | `measured` |
| `platform/platform-observability` | 12 | 42 | 274/288 | 95.14% | 67/69 | 97.10% | 2 | baseline | `measured` |
| `platform/platform-diagnostics-logging` | 3 | 9 | 56/60 | 93.33% | 20/21 | 95.24% | 1 | baseline | `measured` |
| `core/ioc-domain` | 29 | 82 | 229/243 | 94.24% | 86/110 | 78.18% | 24 | `85% / 90%` | `measured; branch gap` |
| `core/ioc-application` | 144 | 1407 | 2056/2340 | 87.86% | 643/906 | 70.97% | 263 | `85% / 90%` | `measured; branch gap` |
| `core/ioc-application-tck` | — | — | — | N/A | — | N/A | — | contract execution | `outside production universe` |
| `adapters/adapter-regex-re2j` | 2 | 39 | 8/18 | 44.44% | 2/4 | 50.00% | 2 | baseline | `measured; supported-path gap` |
| `adapters/adapter-psl` | 1 | 3 | 15/17 | 88.24% | 11/12 | 91.67% | 1 | baseline | `measured` |
| `adapters/adapter-source-tika` | 1 | 6 | 49/50 | 98.00% | 5/8 | 62.50% | 3 | baseline | `measured` |
| `adapters/adapter-sink-csv` | 37 | 610 | 792/929 | 85.25% | 266/398 | 66.83% | 132 | baseline | `measured` |
| `adapters/adapter-manifest-json-jackson` | 5 | 9 | 82/84 | 97.62% | 5/6 | 83.33% | 1 | baseline | `measured` |
| `adapters/adapter-store-jdbc` | 36 | 754 | 1479/1636 | 90.40% | 343/510 | 67.25% | 167 | baseline | `measured` |
| `adapters/adapter-transport-smb` | 14 | 922 | 367/571 | 64.27% | 157/274 | 57.30% | 117 | baseline | `measured; external-path concentration` |
| `adapters/adapter-ingest` | 17 | 266 | 335/388 | 86.34% | 89/114 | 78.07% | 25 | baseline | `measured` |
| `adapters/adapter-cli-picocli` | 16 | 553 | 394/490 | 80.41% | 108/195 | 55.38% | 87 | baseline | `measured` |
| `bootstrap/ioc-app` | 135 | 1519 | 2654/3020 | 87.88% | 858/1217 | 70.50% | 359 | baseline | `measured` |

Для small-denominator modules в evidence добавляются absolute missed
instructions/branches.

No JaCoCo threshold is active in this baseline. Line coverage already exceeds
the future fixed floors for the aggregate, domain and application scopes.
Branch coverage is below those floors by `10.62`, `11.82` and `19.03`
percentage points respectively. The accepted targets are not lowered:
`R030-TEST` must triage meaningful branches, establish no-regression ratchets
and close the release gaps before enabling the fixed checks.

### Downstream execution effect

The aggregate is materially different from module-local reports:

| Module | Local line | Aggregate line | Delta | Local branch | Aggregate branch | Delta |
|---|---:|---:|---:|---:|---:|---:|
| `core/ioc-domain` | 41.98% | 94.24% | +52.26 pp | 40.00% | 78.18% | +38.18 pp |
| `platform/platform-observability` | 79.51% | 95.14% | +15.62 pp | 82.61% | 97.10% | +14.49 pp |
| `adapters/adapter-ingest` | 75.77% | 86.34% | +10.57 pp | 75.44% | 78.07% | +2.63 pp |
| `adapters/adapter-regex-re2j` | no local report | 44.44% | downstream only | no local report | 50.00% | downstream only |

This confirms that module-local ratios cannot be used as release ratchets for
this reactor. The aggregate group values are the accepted baseline source.

### Initial coverage hotspots

The largest missed-branch concentrations are navigation signals, not automatic
test requirements:

| Class | Module | Missed branches | Missed instructions | Initial disposition |
|---|---|---:|---:|---|
| `SyncHealthIndicator` | `bootstrap/ioc-app` | 53 | 96 | Risk review: health degradation/status combinations |
| `AppConfig` | `bootstrap/ioc-app` | 45 | 192 | Composition/config selection review |
| `SmbFileTransport` | `adapter-transport-smb` | 40 | 240 | Offline vs external transport-path review |
| `IocConfigPreflight` | `bootstrap/ioc-app` | 40 | 206 | Strict-config negative/boundary review |
| `JdbcExportRunLedger` | `adapter-store-jdbc` | 39 | 114 | Recovery/CAS/terminal-state review |
| `CsvSliceMaterialization` | `adapter-sink-csv` | 34 | 97 | Failure/cleanup/integrity review |
| `JdbcPublishLedger` | `adapter-store-jdbc` | 31 | 105 | Selection/retry/idempotency review |
| `HealthCommand` | `adapter-cli-picocli` | 28 | 176 | HTTP/status/error mapping review |
| `SmbjShareClient` | `adapter-transport-smb` | 26 | 331 | Zero-line external client seam |
| `ArtifactPublishService` | `core/ioc-application` | 24 | 162 | Partial failure/retry/recovery review |

Twelve classes have zero covered lines. The largest are `SmbjShareClient`
(`88` missed lines), `DaemonMaintenanceScheduler` (`47`),
`PublishLedger` (`22`), the SMB change-notify session implementation (`9`) and
`JdkRegexPatternEngine` (`9`). The remaining seven contain 1–7 lines each.
Zero coverage is not classified as dead code by this measurement; each item
requires supported-path, external-fixture, interface/default-method or
retirement evidence in its owning goal.

## Coverage exclusions

| Selector | Scope/type | Rationale | Denominator impact | Owner | Review condition | State |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — |

Пустая таблица означает отсутствие принятых exclusions, а не отсутствие
проверки.

## Test taxonomy migration

| Current test/suite | Observed level | Target lifecycle/name | Tags | Action | Evidence | State |
|---|---|---|---|---|---|---|
| 190 fast suites | Unit/component, architecture, contract and publication | Surefire defaults / `*Test` | Untagged, `architecture` or `contract` as applicable | Retain fast ownership | Exact source/report set and 943 cases | `verified` |
| 58 ordinary integration suites | DB, filesystem, parsing, serialization, Spring and transport | Failsafe / `*IT` | `integration`, plus `contract` where applicable | Rename reviewed inventory | Exact source/report set | `verified` |
| 2 deterministic E2E suites | Daemon ingest and golden pipeline | Failsafe / `*IT` | `integration`, `e2e` | Use composed `@EndToEndTest` | Exact source/report set | `verified` |
| 5 provisioned external suites | SMB and import load evidence | Failsafe / `*IT` | `integration`, `external`; load also `slow` | Keep property-conditioned and outside offline union | 5 reported shells / 8 explicit offline skips; `CHANGE_NOTIFY` 2/2 passed separately on the provisioned stand | `verified-offline`; `TEST-EXTERNAL-05` verified, other provisioned evidence remains per owner |

## Risk и effectiveness findings

| Finding | Scope/behavior | Gap type | Risk | Required evidence | Disposition | Work item |
|---|---|---|---|---|---|---|
| JDK pattern engine lacked a behavioral consumer | Shared contract covers both implementations; live configuration corpus is equivalent and `ioc.engine=jdk` selects the JDK adapter; regex module is `18/18` lines and `4/4` branches | `contract`, `compatibility` | Supported alternate engine could drift or wire incorrectly without detection | Shared two-engine contract + bootstrap bean-selection test | Closed and verified 2026-09-05 | `TEST-REGEX-03` |
| Live SMB `CHANGE_NOTIFY` execution | External transport signal and idle-survival behavior | `contract`, `external` | Offline suite cannot prove live server semantics | Provisioned execution or explicit release disposition | Closed by 2/2 live cases on commit `404ab5f3`; target-specific boundary retained | `TEST-EXTERNAL-05` |
| No standalone published-library consumer yet | Future extracted library coordinates and public API | `contract`, `publication` | Reactor-relative resolution can hide publication/POM defects | Out-of-reactor compile + runtime contract using published coordinates | Required when library API is finalized | `R030-LIB` / `R030-TEST` |
| Invariant/PIT pilots | Refang, extraction/attribution, normalization, classification, deduplication, identity | `assertion-quality` | Initial domain-only PIT exposed downstream-dependent oracles and 41 undetected mutants | 16 focused invariant cases plus final PIT triage | Closed: final `NO_COVERAGE=0`, eight survived mutants classified | `TEST-PILOTS-06` |
| No-op sink test has implicit oracle | `NoopDiagnosticSink` valid emission | `assertion-quality` | Very low; intent is less explicit | Explicit no-throw assertion if touched | Opportunistic | `R030-TEST` module review |

Gap type examples: `negative`, `boundary`, `error`, `recovery`,
`concurrency`, `migration`, `contract`, `assertion-quality`.

## Flake, wait и duration findings

| Test/suite | Signal | Reproduction/seed | Duration | Owner | Disposition | Exit condition |
|---|---|---|---:|---|---|---|
| 10 wait-bearing files in the current refresh | 11 unbounded `await()` calls; no test/fork timeout | Static analysis | N/A | `R030-TEST` | Remediate | Every async path has bounded, diagnosable completion and failure-safe cleanup |
| 5 files with fixed sleeps | 6 calls, all locally bounded or semantic | Static analysis | up to 1–10 s by helper/scenario | `R030-TEST` | Review; do not blanket-replace | Each retained sleep has written rationale; observable conditions use deterministic coordination where practical |
| Scheduler thread tests | 9 timed joins; termination usually not asserted | Static analysis | join bound `1 s` | `R030-TEST` | Remediate with termination assertion/finally cleanup | No test can silently leave a live worker |
| Two CLI suites | Temporary `System.out` replacement | Current sequential JUnit execution | N/A | `R030-TEST` | Safe in current mode; guard before parallelism | Resource lock or no global mutation before parallel execution |
| Full functional-reactor repeat | No failure reproduced in three random-order runs; exact report union passed every time | Seeds `42`, `43`, `44`; no retry | `147 s`, `135 s`, `157 s`; `453 s` wrapper | `R030-TEST` | Adopt weekly/manual diagnostic | Reproduce a failure with its published seed; keep outside PR gate until new evidence justifies promotion |

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
| PIT | Complete `core/ioc-domain` production scope | `make mutation-pilot`; opt-in module profile, `DEFAULTS`, one thread, zero thresholds | `core/ioc-domain/target/pit-reports/{index.html,mutations.xml,linecoverage.xml}` + wrapper summary | Initial 74/115 detected and 28 no-coverage mutations exposed real local-oracle gaps; final 107/115 detected, eight low-signal survivors, `NO_COVERAGE=0`, test strength `93%` | `53 s`; peak RSS about `337 MiB` | `Adopt` as weekly/manual domain diagnostic; no PR mutation threshold | Final wrapper summary plus repeatable HTML/XML; scheduled/manual artifact workflow |
| Random order/repeat | All 20 functional JAR modules; exact 195 fast / 66 integration report union per run | `make stability-pilot`; seeds advance from explicit `SEED`, default `42/43/44`, no retry | Per-seed Surefire/Failsafe XML and summaries under `target/test-pilots/stability/` | Three green seeds, no order-dependent failure; each run retained the exact 261-suite XML union and excluded stale non-reactor targets | `453 s` total; runs `147/135/157 s`; peak RSS about `935 MiB` | `Adopt` as weekly/manual report-only diagnostic; cost does not justify per-PR use | Final scoped-archive worktree run based on `8df66cf5`; scheduled/manual artifact workflow |
| Invariant-oriented tests | Refang, extraction/attribution, normalization, classification, deduplication and identity | Existing JUnit 5 + AssertJ stack; no property framework | Ordinary Surefire XML and PIT response | 14 domain and 2 application cases close observable boundaries; existing classification contract already killed its relevant mutants | Domain suite `66/66`; focused application `11/11` | `Adopt` in deterministic offline suite | Root lifecycle verifier accepts 195 fast suites and 256 deterministic-offline suites |

### PIT survived-mutant triage

| Mutant/location | Critical rule | Classification | Test/work item | Rationale | State |
|---|---|---|---|---|---|
| `DefaultIndicatorFeatureExtractor:29,30,33,36,44,55` | Slash/query/port/scheme delimiters are interpreted after a supported host-bearing indicator | `non-critical` | None for 0.3.0 | All six boundary mutants differ only when the delimiter begins the whole normalized value (`/`, `?`, `:`, `://` at index zero). Such values are outside the configured IOC extractor corpus; locking malformed parser output would weaken the supported-input contract. | Reviewed |
| `RegexIndicatorExtractor:35` empty shortcut | Empty input yields no indicators or decisions | `equivalent/noise` | None | Removing only the `isEmpty()` shortcut produces the same empty outcome for every accepted configured pattern; null handling is independently killed. | Reviewed |
| `DefaultIndicatorNormalizer:14` empty shortcut | Empty input remains empty | `equivalent/noise` | None | Without the shortcut both trim loops are skipped and `substring(0, 0)` returns the same value; null handling is independently killed. | Reviewed |

Если полный domain pilot разделён на package/capability profiles, evidence MUST
показывать, что profiles непересекаются и вместе покрывают принятый mutation
universe.

Pilot не разделялся: измеренные ~53 секунды и читаемый единый HTML/XML report не
оправдывают package profiles. Два дополнительных detected mutants имеют
`TIMED_OUT`: удаление empty-token guard и замена положительного шага поиска на
вычитание создают non-terminating loop, который PIT корректно ограничивает.
Поэтому XML содержит `105 KILLED + 2 TIMED_OUT + 8 SURVIVED = 115`, а итоговый
PIT signal сообщает 107 detected mutants.

## Completion

- [x] Test inventory and lifecycle classification complete
- [x] Per-module and aggregate baseline captured
- [x] Coverage universe and exclusions accepted
- [x] Fixed floors and per-module ratchets enforced
- [ ] Codecov signal operational либо имеет external-unavailability disposition
- [ ] Codecov status подтверждён как non-required
- [x] Risk-based gaps have disposition
- [x] Flake/wait/duration findings have disposition
- [x] PIT command/profile и reports воспроизводимы
- [x] PIT survived mutants классифицированы
- [x] PIT runtime cost измерена
- [x] Diagnostic pilots have adoption decisions
- [x] Published testing documentation matches live build
- [x] Status matrix updated
