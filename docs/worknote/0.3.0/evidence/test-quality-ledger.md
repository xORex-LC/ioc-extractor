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

## Instrumentation

| Control | Version/config | Local command | CI evidence | State |
|---|---|---|---|---|
| JaCoCo agent/report | TBD | TBD | TBD | `planned` |
| JaCoCo per-module check | TBD | TBD | TBD | `planned` |
| JaCoCo aggregate check | TBD | TBD | TBD | `planned` |
| Surefire unit lifecycle | `3.5.6`; default includes; bootstrap injects project version | `make test` / `make verify` | 171 mixed-level suites in fresh verify | `existing-mixed` |
| Failsafe integration lifecycle | `3.5.6` managed only; no project execution | N/A | No `*IT` source or reports | `missing` |
| JUnit tag convention | No tags, composed annotations or filters | N/A | No selectable cohorts in current CI | `missing` |
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
