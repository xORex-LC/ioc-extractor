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
are captured below. Threshold enforcement and all remediation remain open under
their own `R030-TEST` work items.

`BASE-INVENTORIES-09` intake status: **verified**. The initial work queue below
maps every baseline test/coverage/consumer gap to an owner without implementing
it during Wave 0.

## Initial work-item queue

| Work item | Scope/outcome | Evidence | Dependency | State |
|---|---|---|---|---|
| `TEST-LIFECYCLE-01` | Introduce accepted tags/composed annotations and Surefire/Failsafe selection without losing any of 171 suites | Lifecycle classification and zero-tag inventory below | Global `R030-TEST` Wave 1 | `planned` |
| `TEST-COVERAGE-02` | Add aggregate/per-module no-regression ratchets, then close accepted aggregate/domain/application branch floors | Coverage baseline and hotspots below | Stable lifecycle/reporting | `planned` |
| `TEST-REGEX-03` | Common RE2/J + JDK engine contract and bootstrap `ioc.engine=jdk` selection test | Risk finding below; 0/9 JDK lines | Regex/bootstrap module wave | `planned` |
| `TEST-WAITS-04` | Bound async waits, release workers in `finally`, assert termination and add diagnosable safety timeout | Wait/flake inventory below | Module test hardening | `planned` |
| `TEST-EXTERNAL-05` | Execute live SMB `CHANGE_NOTIFY` contract or record explicit external-evidence release disposition | Two skipped external cases | Provisioned fixture / `R030-REL` | `external-evidence-required` |
| `TEST-PILOTS-06` | Run PIT/domain, invariant and seeded repeat pilots; triage signal/noise/cost | Diagnostic pilot tables below | Wave 1 profiles/artifacts | `planned` |
| `TEST-CODECOV-07` | Best-effort non-required upload plus project/patch signals | Codecov table below | Stable JaCoCo XML + CI | `planned` |
| `TEST-PUBLICATION-08` | Out-of-reactor compile/runtime contract for an admitted published library | Compatibility/shared-code ledgers | Blocked until `R030-LIB` admission | `waiting-on-library-contract` |
| `TEST-CONSUMERS-09` | Add exact golden CSV/manifest/log/CLI consumer payload/query fixtures for accepted external surfaces | Compatibility consumer gaps | Per-surface owner decision | `planned` |

## Baseline discovery inventory

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

### Static wait inventory

| Signal | Count/scope | Assessment |
|---|---|---|
| `Thread.sleep` | 6 calls in 5 files | All are inside a bounded polling/semantic scenario; no unbounded fixed sleep |
| Timed latch awaits | 34 calls | Positive bounded coordination |
| Timed `Future.get` | 2 calls | Positive bounded result wait |
| Unbounded latch/barrier awaits | 10 calls in 9 files | Failure-path hang/poor-diagnostics risk |
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

The accepted invariant-oriented and mutation pilots have not yet been run.
Existing example tests are useful seeds, not pilot evidence; `BASE-TESTS-04`
does not infer mutation effectiveness from assertion volume.

## Instrumentation

| Control | Version/config | Local command | CI evidence | State |
|---|---|---|---|---|
| JaCoCo agent/report | `0.8.15`; inherited `prepare-agent` + module `report` | `make verify` | Clean local reactor evidence below | `report-only` |
| JaCoCo per-module check | Not configured | N/A | N/A | `deferred-until-baseline` |
| JaCoCo aggregate check | Not configured | N/A | N/A | `deferred-until-baseline` |
| Surefire unit lifecycle | `3.5.6`; default includes; bootstrap injects project version | `make test` / `make verify` | 171 mixed-level suites in fresh verify | `existing-mixed` |
| Failsafe integration lifecycle | `3.5.6` managed only; no project execution | N/A | No `*IT` source or reports | `missing` |
| JUnit tag convention | No tags, composed annotations or filters | N/A | No selectable cohorts in current CI | `missing` |
| Codecov best-effort upload | TBD | N/A | TBD | `planned` |
| Codecov project/patch signals | TBD | N/A | TBD | `planned` |
| Coverage/test artifacts | Module HTML/XML + reactor aggregate HTML/XML | `make verify` | CI artifact retention not implemented in baseline | `local-existing` |

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
| TBD | TBD | TBD | TBD | TBD | TBD | `planned` |

## Risk и effectiveness findings

| Finding | Scope/behavior | Gap type | Risk | Required evidence | Disposition | Work item |
|---|---|---|---|---|---|---|
| JDK pattern engine lacks behavioral consumer | `JdkRegexPatternEngine` and `ioc.engine=jdk` selection; aggregate confirms 0/9 lines and 0/2 branches for JDK versus 8/9 and 2/2 for RE2/J | `contract`, `compatibility` | Supported alternate engine may drift or wire incorrectly without detection | Shared two-engine contract + bootstrap bean-selection test | Close in 0.3.0 | `R030-TEST` adapter/bootstrap hardening |
| Live SMB `CHANGE_NOTIFY` not executed | External transport signal and idle-survival behavior | `contract`, `external` | Offline suite cannot prove live server semantics | Provisioned execution or explicit release disposition | Open external evidence | `R030-TEST` / `R030-REL` |
| No standalone published-library consumer yet | Future extracted library coordinates and public API | `contract`, `publication` | Reactor-relative resolution can hide publication/POM defects | Out-of-reactor compile + runtime contract using published coordinates | Required when library API is finalized | `R030-LIB` / `R030-TEST` |
| Invariant/PIT pilots not executed | Refang, normalization, classification, deduplication, identity | `assertion-quality` | Example assertions may miss semantically important mutations/invariants | Reproducible pilots with triaged results | Planned, no baseline inference | `R030-TEST` |
| No-op sink test has implicit oracle | `NoopDiagnosticSink` valid emission | `assertion-quality` | Very low; intent is less explicit | Explicit no-throw assertion if touched | Opportunistic | `R030-TEST` module review |

Gap type examples: `negative`, `boundary`, `error`, `recovery`,
`concurrency`, `migration`, `contract`, `assertion-quality`.

## Flake, wait и duration findings

| Test/suite | Signal | Reproduction/seed | Duration | Owner | Disposition | Exit condition |
|---|---|---|---:|---|---|---|
| 9 wait-bearing files listed above | 10 unbounded `await()` calls; no test/fork timeout | Static analysis | N/A | `R030-TEST` | Remediate | Every async path has bounded, diagnosable completion and failure-safe cleanup |
| 5 files with fixed sleeps | 6 calls, all locally bounded or semantic | Static analysis | up to 1–10 s by helper/scenario | `R030-TEST` | Review; do not blanket-replace | Each retained sleep has written rationale; observable conditions use deterministic coordination where practical |
| Scheduler thread tests | 9 timed joins; termination usually not asserted | Static analysis | join bound `1 s` | `R030-TEST` | Remediate with termination assertion/finally cleanup | No test can silently leave a live worker |
| Two CLI suites | Temporary `System.out` replacement | Current sequential JUnit execution | N/A | `R030-TEST` | Safe in current mode; guard before parallelism | Resource lock or no global mutation before parallel execution |
| Selected 15-suite repeat | No failure reproduced in 5 fixed-order runs | 5 independent runs; no seed/retry | about 13 s/run | `R030-TEST` | Weak green signal | Scheduled seeded random-order/repeat pilot still required |

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

- [x] Test inventory and lifecycle classification complete
- [x] Per-module and aggregate baseline captured
- [x] Coverage universe and exclusions accepted
- [ ] Fixed floors and per-module ratchets enforced
- [ ] Codecov signal operational либо имеет external-unavailability disposition
- [ ] Codecov status подтверждён как non-required
- [x] Risk-based gaps have disposition
- [x] Flake/wait/duration findings have disposition
- [ ] PIT command/profile и reports воспроизводимы
- [ ] PIT survived mutants классифицированы
- [ ] PIT runtime cost измерена
- [ ] Diagnostic pilots have adoption decisions
- [ ] Published testing documentation matches live build
- [x] Status matrix updated
