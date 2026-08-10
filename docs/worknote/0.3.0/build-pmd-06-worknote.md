---
title: "BUILD-PMD-06 — PMD rule evaluation worknote"
version: "0.3.0"
goal_id: "R030-BUILD"
work_item: "BUILD-PMD-06"
status: "P0/P1 complete; P2 pending"
document_type: "Temporary execution worknote"
source_of_truth: false
language: "en"
---

# BUILD-PMD-06 — bounded PMD rule evaluation

## 1. Purpose and authority

This temporary worknote records the evidence-based evaluation of PMD rules
beyond the already adopted CPD control. It does not make PMD an accepted build
gate and does not replace the authoritative release documents:

- contract: [R030-BUILD](goals/R030-BUILD-build-quality.md);
- current state: [status matrix](status-matrix.md);
- measurements and adoption decisions:
  [build-quality ledger](evidence/build-quality-ledger.md);
- durable mechanics of existing controls:
  [build-quality](../../dev/build-quality.md).

The phrase "full PMD" means evaluating PMD's source-analysis capability across
the agreed signal families. It does **not** mean enabling PMD's default ruleset
or every rule in selected categories.

## 2. Scope and non-goals

The evaluation targets the same checked-in `src/main/java` universe as CPD:
19 production runtime JAR modules. The root parent, all build-only report POMs,
`ioc-application-tck`, test sources, generated sources and vendor sources remain
outside this evaluation.

In scope:

- dead and unused code candidates;
- exception, resource, null-check and correctness candidates;
- complexity hotspots;
- allocation and string-construction performance candidates;
- charset and locale portability candidates;
- per-rule signal/noise, overlap with SpotBugs and runtime cost;
- a report-only adoption decision based on repository evidence.

Out of scope:

- `pmd:check`, merge blocking or a no-new-findings ratchet;
- PMD's default ruleset or category-wide rule references;
- test-source analysis;
- security/SAST expansion;
- multithreading rules without a separately demonstrated signal gap;
- suppressions, an accepted-findings baseline or mass remediation;
- automatic deletion or refactoring from one static finding;
- changes to production behavior during the measurement pass.

Analyzer failure, ruleset-loading failure, missing expected input or missing or
malformed reports must fail the evaluation invocation. Findings themselves
remain advisory.

## 3. Checkpoints

| ID | Work | Exit evidence | State |
|---|---|---|---|
| `P0` | Rule and tool inventory | Versions, module disposition, named candidate rules, exclusions and P1 acceptance criteria are recorded | `completed` |
| `P1` | Report-only compatibility and baseline run | Candidate engine works with the pinned plugin; XML/HTML reports cover exactly 19 production roots; cost and raw findings are captured | `completed` |
| `P2` | Semantic triage | Each rule track has actionable/noise/overlap counts and representative dispositions | `pending` |
| `P3` | Adoption decision | `Adopt`, `Adopt with a reduced ruleset`, `Defer` or `Reject` is recorded with lifecycle and ownership consequences | `pending` |

Only one checkpoint is active at a time. P0 intentionally changed no POM,
reactor module, Make target or CI workflow; P1 added only opt-in report
infrastructure and did not adopt a PMD merge gate.

## 4. P0 repository context

| Field | Value |
|---|---|
| Date | `2026-08-09` |
| Branch / start commit | `release-0.3.0` / `d032c2d566b679e84fc40e6efb1b5f896cbebdcd` |
| Maven revision | `0.3.0-SNAPSHOT` |
| Initial repository state | clean; synchronized with `origin/release-0.3.0` |
| Verification context | last recorded `make verify` passed, but `verify.fresh=false` because the fingerprint belongs to an earlier commit |
| Reactor | 24 projects: root, 20 functional JARs, 3 build-only report POMs |
| Existing PMD use | Maven PMD Plugin `3.28.0`, bundled PMD `7.17.0`; only aggregate CPD is active |
| Existing PMD inheritance | root configuration has PMD source analysis skipped; `build-support/cpd-report` alone owns `aggregate-cpd` |

The stale verification fingerprint is not treated as current gate evidence. It
does not block this documentation-only P0 inventory; P1 must establish fresh
execution evidence for its own changes.

## 5. Tool version decision for P1

Official sources establish the following P0 snapshot:

- [Maven PMD Plugin `3.28.0`](https://maven.apache.org/plugins/maven-pmd-plugin/summary.html)
  is the current stable plugin and bundles PMD `7.17.0`;
- the official [PMD 7 release history](https://pmd.github.io/pmd/pmd_release_notes_old_pmd7.html)
  identifies stable PMD `7.26.0`, released on 2026-06-29, while the
  [current release notes](https://pmd.github.io/pmd/pmd_release_notes.html)
  identify `7.27.0` as a development snapshot;
- the plugin documents
  [overriding its bundled PMD dependencies](https://maven.apache.org/plugins/maven-pmd-plugin/examples/upgrading-PMD-at-runtime.html)
  with a newer compatible engine.

P1 therefore starts with Maven PMD Plugin `3.28.0` and an explicit PMD engine
`7.26.0`. This is a **compatibility candidate**, not an adopted version. P1 must
stop on incompatibility or output drift and record the evidence; it must not
silently fall back to bundled `7.17.0`. Snapshot PMD versions are not candidates.

The newer engine is worth testing rather than accepting the bundled engine by
default because releases after `7.17.0` contain fixes relevant to this exact
evaluation surface, including `UnusedPrivateMethod`, `CloseResource`,
`UselessPureMethodCall` and complexity-analysis false positives.

## 6. P0 named rule inventory

P0 inspected the PMD Java `7.17.0` ruleset metadata already present in the local
Maven repository. The four broad categories contain 222 rules in total:
64 best-practice, 92 error-prone, 41 design and 25 performance rules. Enabling
those categories wholesale would combine unrelated policy, style and framework
assumptions and would make a PMD upgrade implicitly change project policy.

P1 may enable only the following exact rule references. Availability and
behavior must be revalidated against PMD `7.26.0` before the authoritative run.

### Track A — dead and unused candidates

| Exact rule | Desired signal | P1 caution |
|---|---|---|
| `category/java/bestpractices.xml/UnusedAssignment` | overwritten or unread assignments | Distinguish deliberate state transition and generated-style code |
| `category/java/bestpractices.xml/UnusedFormalParameter` | parameters with no implementation use | Ports, callbacks, framework signatures and future-compatible contracts may be intentional |
| `category/java/bestpractices.xml/UnusedLocalVariable` | genuinely dead locals | Confirm no debugging or resource-lifetime role |
| `category/java/bestpractices.xml/UnusedPrivateField` | unreferenced private state | Check reflection, binding and serialization first |
| `category/java/bestpractices.xml/UnusedPrivateMethod` | unreachable private implementation | Check framework and annotation-driven invocation first |

Findings are candidates for `R030-RETIRE`; they never authorize automatic
deletion.

### Track B — correctness, failure and portability

| Exact rule | Desired signal | P1 caution |
|---|---|---|
| `category/java/bestpractices.xml/PreserveStackTrace` | lost causal exception information | Compare with established wrapping and suppression contracts |
| `category/java/bestpractices.xml/UseTryWithResources` | incomplete close/suppression semantics | Verify ownership before changing resource lifetime |
| `category/java/bestpractices.xml/RelianceOnDefaultCharset` | host-dependent text behavior | Separate durable/wire formats from explicitly local text |
| `category/java/bestpractices.xml/UseStandardCharsets` | avoid fallible charset lookup | Mostly small maintainability signal |
| `category/java/errorprone.xml/EmptyCatchBlock` | silently discarded failures | Intentional polling/probing must remain explicit |
| `category/java/errorprone.xml/CloseResource` | leaked closeable resources | Framework-managed and transferred ownership commonly produce noise |
| `category/java/errorprone.xml/DoNotThrowExceptionInFinally` | original failure can be replaced | Check try-with-resources and `addSuppressed` behavior |
| `category/java/errorprone.xml/ReturnFromFinallyBlock` | control flow masks body result/failure | Expected to be high-confidence and low-volume |
| `category/java/errorprone.xml/AvoidLosingExceptionInformation` | exception context is discarded | Do not require sensitive values in diagnostics |
| `category/java/errorprone.xml/BrokenNullCheck` | contradictory null guard | Confirm analyzer data-flow assumptions |
| `category/java/errorprone.xml/MisplacedNullCheck` | dereference occurs before guard | Confirm reachability at the adapter boundary |
| `category/java/errorprone.xml/UnusedNullCheckInEquals` | ineffective equality null guard | Check record/generated equality implementations |
| `category/java/errorprone.xml/UselessOperationOnImmutable` | ignored immutable result | Compare with SpotBugs return-value findings |
| `category/java/errorprone.xml/UselessPureMethodCall` | discarded side-effect-free result | Recheck against PMD `7.26.0` false-positive fixes |
| `category/java/errorprone.xml/InvalidLogMessageFormat` | placeholder/argument mismatch | Confirm SLF4J fluent and structured logging support |
| `category/java/errorprone.xml/UseLocaleWithCaseConversions` | locale-dependent case conversion | Durable identities and protocol values are highest priority |

### Track C — complexity hotspots

| Exact rule | Desired signal | P1 treatment |
|---|---|---|
| `category/java/design.xml/CognitiveComplexity` | difficult-to-follow control flow | Measure several thresholds; hotspot, not defect |
| `category/java/design.xml/CyclomaticComplexity` | high independent path count | Calibrate separately for methods and classes |
| `category/java/design.xml/NPathComplexity` | combinatorial execution paths | Review only materially high outliers |
| `category/java/design.xml/AvoidDeeplyNestedIfStmts` | deeply nested branching | Prefer guard/seam evidence over mechanical extraction |
| `category/java/design.xml/NcssCount` | unusually large method/type | Size is navigation evidence, not a quality verdict |
| `category/java/design.xml/ExcessiveParameterList` | unstable or overly broad boundary | Constructors/config records and explicit ports need separate treatment |

P1 must report threshold sweeps separately. It must not choose thresholds merely
to reach zero findings or copy defaults from another repository.

### Track D — allocation and string-construction candidates

| Exact rule | Desired signal | P1 caution |
|---|---|---|
| `category/java/performance.xml/AvoidInstantiatingObjectsInLoops` | avoidable repeated allocation | Require a relevant loop and plausible cost |
| `category/java/performance.xml/InefficientStringBuffering` | avoidable intermediate strings | Modern compiler/JDK behavior must be considered |
| `category/java/performance.xml/ConsecutiveAppendsShouldReuse` | repeated builder lookup/use | Prefer clarity unless allocation impact is credible |
| `category/java/performance.xml/ConsecutiveLiteralAppends` | fragmented literal appends | Usually micro-signal; do not churn code |
| `category/java/performance.xml/StringInstantiation` | redundant `new String(...)` | Expected high-confidence small finding |
| `category/java/performance.xml/AddEmptyString` | conversion through empty-string concatenation | Confirm readability and null semantics |
| `category/java/performance.xml/UselessStringValueOf` | redundant string conversion | Confirm overload resolution remains unchanged |

Performance findings are hypotheses. Adoption or remediation requires a clear
allocation path or measurement; PMD priority alone is not performance evidence.

## 7. Explicitly rejected P0 candidates

P0 rejects the following from the initial run:

- PMD's default ruleset and whole-category references;
- style, naming, documentation and formatting policy;
- `GodClass`, `LawOfDemeter`, `DataClass`, `TooManyMethods` and broad coupling
  metrics, because they would initially measure architectural taste more than a
  concrete release risk;
- multithreading rules, because SpotBugs plus focused concurrency review already
  own that signal and no incremental gap has been demonstrated;
- test rules and test sources;
- rules whose primary purpose is security scanning;
- suppression hygiene rules before PMD suppression itself has an accepted
  operating contract.

P2 may propose adding or removing an exact rule only with a recorded signal gap
or demonstrated noise. A category reference is never the shortcut.

## 8. Implemented P1 build topology

P1 uses a separate build-only owner, `build-support/pmd-report`, rather than
mixing source-analysis output into
`build-support/cpd-report`. CPD and PMD have different scope registries,
findings, lifecycle and future enforcement policy even though they use the same
Maven plugin.

The implemented topology is:

- a dedicated `pmd-scope.tsv` with explicit disposition for all 25 reactor
  projects;
- a positive list of the 19 production `src/main/java` roots;
- one aggregate report-only execution producing machine-readable XML and human
  HTML in a dedicated `target/pmd/` directory;
- a fail-closed integrity check for scope drift, missing sources and missing or
  malformed reports;
- a single analysis pass; no additional `pmd:check` pass;
- no regular `make verify` or CI adoption until P2 signal/noise and P3 adoption
  are
  reviewed.

The execution is `aggregate-pmd-no-fork` in `verify`. The developer command
selects `build-support/pmd-report` and its upstream projects with `-pl ... -am`.
This keeps the single PMD aggregator out of the parallel full-reactor group of
JaCoCo, SpotBugs and CPD aggregators. A trial that activated PMD across the
entire parallel reactor failed with `Duplicate artifact resolution result`; the
selected-reactor command removes that race without weakening the ordinary
`make verify` gate. The choice also avoids the lifecycle fork performed by
standard `aggregate-pmd`.

## 9. P1 entry and exit criteria

P1 may begin only from a clean, synchronized context. Its implementation must
be kept separate from P0 documentation and must establish:

1. Maven PMD Plugin `3.28.0` + PMD `7.26.0` compatibility on JDK 21;
2. an exact 25-project disposition and exact 19-root positive source scope;
3. deterministic XML and HTML from a clean run and an immediate repeat;
4. analyzer/ruleset/report failures that fail closed while findings remain
   report-only;
5. per-track raw counts, cross-module locations, elapsed time and peak memory;
6. overlap with SpotBugs, compiler, ArchUnit and existing tests;
7. no tracked suppressions, baseline or production-code remediation.

If one of these cannot be established without widening scope, P1 stops for an
explicit status-matrix decision.

## 10. P1 execution evidence

P1 started on 2026-08-10 from clean synchronized commit
`0ced27e3b1bf22ca76ac6adcf3866a8ab7824ecf`. A pre-change `make verify`
completed successfully in `02:09`. Adding the PMD report owner changed the full
reactor topology from 24 to 25 projects; the analysis command deliberately
selects a 22-project upstream reactor ending in `ioc-pmd-report`.

### Compatibility, scope and failure behavior

- Maven PMD Plugin `3.28.0` loaded the explicitly overridden `pmd-core` and
  `pmd-java` engine `7.26.0` on JDK 21 and emitted report version `7.26.0`;
- all 34 P0 exact rule references loaded; category references, ruleset
  exclusions and tracked suppressions remain forbidden;
- the registry contains one disposition for each of the 25 reactor projects:
  19 production JARs analyzed, the TCK/root/other report POMs excluded and the
  PMD report POM marked aggregate;
- the positive scope contains 19 checked-in production roots and 510 Java
  source files. PMD XML lists only the 48 files with violations, so coverage is
  proven by the guarded positive source configuration rather than by pretending
  that a zero-finding source appears in the report;
- stale output is deleted before the run. Missing/empty XML or HTML, malformed
  or out-of-scope report paths, engine drift, analyzer/configuration errors,
  ruleset drift and scope drift fail the invocation;
- the shared synthetic-reactor harness now passes 6 happy paths and 39 negative
  scenarios, including PMD scope, engine, encoding, ruleset, analyzer-error,
  source-suppression, lifecycle-wiring and report-integrity mutations;
- findings remain report-only: no `pmd:check`, CI job, baseline, suppression or
  production-code remediation was introduced.
- the final clean full-reactor check exposed one pre-existing SpotBugs baseline
  anchor rebuilt at bytecode offset `345` instead of `346` for
  `RemoteFetchService.fetchOne`; type, class, method signature, source line,
  hash, occurrence and disposition were unchanged, so only that exact anchor
  was refreshed and no finding or suppression was accepted by P1.

PMD `7.26.0` warns that `AvoidLosingExceptionInformation` and
`UselessOperationOnImmutable` are scheduled for removal in PMD 8. Both produced
zero P1 findings. P2/P3 must decide whether to replace or remove them before any
future PMD 8 upgrade; the warning is not hidden.

### Raw signal

The clean report contains 92 violations in 48 files across 9 of the 19 analyzed
modules:

| Track | Findings | Rules producing signal |
|---|---:|---|
| A — dead/unused | 6 | `UnusedAssignment` 3; `UnusedFormalParameter` 3 |
| B — correctness/resource/portability | 25 | `CloseResource` 15; `PreserveStackTrace` 5; `UseTryWithResources` 3; `RelianceOnDefaultCharset` 2 |
| C — complexity | 37 | `CyclomaticComplexity` 25; `ExcessiveParameterList` 9; `CognitiveComplexity` 2; `AvoidDeeplyNestedIfStmts` 1 |
| D — allocation/string | 24 | `AvoidInstantiatingObjectsInLoops` 23; `ConsecutiveAppendsShouldReuse` 1 |

The other 22 candidate rules produced no findings. Cross-module distribution:

| Module | Findings | Files |
|---|---:|---:|
| `bootstrap/ioc-app` | 27 | 14 |
| `adapters/adapter-transport-smb` | 19 | 7 |
| `core/ioc-application` | 16 | 12 |
| `adapters/adapter-sink-csv` | 13 | 5 |
| `adapters/adapter-store-jdbc` | 9 | 6 |
| `adapters/adapter-ingest` | 3 | 1 |
| `adapters/adapter-cli-picocli` | 2 | 1 |
| `platform/platform-etl` | 2 | 1 |
| `platform/platform-concurrency` | 1 | 1 |

These are raw analyzer occurrences, not accepted defects or debt items. P2 owns
semantic deduplication and disposition.

### Cost and reproducibility

| Run | Maven wall | Process wall | Peak RSS | Result |
|---|---:|---:|---:|---|
| clean `make pmd-analysis` | `01:37` | `98.28 s` | `1,189,012 KiB` | 92 findings / 48 files |
| immediate repeat | `01:27` | `88.97 s` | `986,692 KiB` | 92 findings / 48 files |

The PMD report module itself took `3.520 s` and `3.632 s`. XML was byte-identical
after removing its generated `timestamp` attribute; HTML was byte-identical
without normalization. This is the deterministic content contract used for P1,
not a claim that timestamps are reproducible bytes.

After lifecycle-wiring and suppression guards were added, a final warm
`make pmd-analysis` completed 22/22 projects in `01:44`; the report owner took
`4.144 s` and reproduced the same 92 findings / 48 files. This confirmation is
not substituted for the clean/repeat cost pair above.

The selected reactor also completed 182 test suites / 845 tests with 0 failures,
0 errors and the same 2 external SMB skips. SpotBugs has accepted findings in 11
of the 48 PMD-reported files, but file overlap is not finding equivalence; P2
must compare individual semantics. Javac and all 27 ArchUnit rules were green:
they provide compilation and dependency-boundary evidence, not unused/resource/
complexity policy. Existing tests protect runtime behavior but do not make the
static findings true or false.

The final ordinary `make verify` completed the full 25-project reactor in
`01:31` with the same 182 suites / 845 tests, 0 failures, 0 errors and 2 skips.
SpotBugs reported 65 accepted / 0 visible findings and CPD integrity passed.
The PMD report owner completed in `0.135 s` without running the evaluation
execution, confirming that P1 did not add PMD source analysis to the ordinary
lifecycle or CI path.

## 11. Decision log

| Date | Decision | Rationale |
|---|---|---|
| 2026-08-09 | Activate `BUILD-PMD-06` as a bounded evaluation, not implicit adoption | SpotBugs triage is complete, so incremental source-analysis signal can now be measured independently |
| 2026-08-09 | Complete P0 with exact rule references grouped into four tracks | Whole categories contain 222 mixed-policy rules and would make upgrades alter policy implicitly |
| 2026-08-09 | Use plugin `3.28.0` with candidate engine `7.26.0` in P1 | Keep the stable plugin while evaluating current PMD fixes relevant to the candidate rules |
| 2026-08-09 | Keep P0 documentation-only | Tool wiring, reports and cost belong to P1 and require their own reviewable change |
| 2026-08-10 | Complete P1 with a dedicated opt-in report owner and fail-closed integrity contract | The candidate versions work on JDK 21, exact scope and reports are reproducible, while findings remain advisory |
| 2026-08-10 | Select only `pmd-report` and its upstream reactor in `make pmd-analysis` | Running all independent aggregate report mojos concurrently exposed Maven artifact-resolution contention; selected-reactor execution retains the intended source/test graph without the race |
| 2026-08-10 | Carry 92 raw occurrences into P2 without remediation or suppression | P1 measures compatibility and inventory; semantic signal/noise decisions belong to the next checkpoint |
