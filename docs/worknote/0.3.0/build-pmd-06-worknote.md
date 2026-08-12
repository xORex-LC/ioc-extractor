---
title: "BUILD-PMD-06 — PMD rule evaluation worknote"
version: "0.3.0"
goal_id: "R030-BUILD"
work_item: "BUILD-PMD-06"
status: "P0/P1/P2/P3 complete; reduced report-only policy adopted"
document_type: "Temporary execution worknote"
source_of_truth: false
language: "en"
---

# BUILD-PMD-06 — bounded PMD rule evaluation

## 1. Purpose and authority

This temporary worknote records the evidence-based evaluation and P3 adoption
of PMD rules beyond the already adopted CPD control. Findings remain advisory;
the adopted control blocks tool-health and contract failures rather than PMD
violations. This worknote does not replace the authoritative release documents:

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
| `P2` | Semantic triage | Each rule track has actionable/noise/overlap counts and representative dispositions | `completed` |
| `P3` | Adoption decision | `Adopt`, `Adopt with a reduced ruleset`, `Defer` or `Reject` is recorded with lifecycle and ownership consequences | `completed` |

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

P2 must report threshold sweeps separately. It must not choose thresholds merely
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
- no regular `make verify` or CI adoption unless P3 explicitly accepts a
  lifecycle after reviewing the completed P2 evidence.

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
zero P1 findings. P2's replacement disposition is recorded below and P3 must
apply it before any future PMD 8 upgrade; the warning is not hidden.

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
of the 48 PMD-reported files, but file overlap is not finding equivalence; P2's
semantic comparison is recorded below. Javac and all 27 ArchUnit rules were green:
they provide compilation and dependency-boundary evidence, not unused/resource/
complexity policy. Existing tests protect runtime behavior but do not make the
static findings true or false.

The final ordinary `make verify` completed the full 25-project reactor in
`01:31` with the same 182 suites / 845 tests, 0 failures, 0 errors and 2 skips.
SpotBugs reported 65 accepted / 0 visible findings and CPD integrity passed.
The PMD report owner completed in `0.135 s` without running the evaluation
execution, confirming that P1 did not add PMD source analysis to the ordinary
lifecycle or CI path.

## 11. P2 semantic triage

P2 reviewed every one of the 92 raw occurrences against the production code,
resource ownership, exception precedence, concurrency contracts and focused
tests. It changed no production source, analyzer configuration, suppression or
accepted-findings baseline. No immediate correctness, resource or concurrency
defect was confirmed, so P2 did not interrupt the evaluation for remediation.

The occurrence taxonomy is deliberately more precise than a binary
signal/noise label:

- `actionable-fix` — a real bounded improvement, but not an immediate release
  risk;
- `debt-hotspot` — maintainability evidence for a future refactoring queue, not
  proof of incorrect behavior;
- `overlap` — the occurrence repeats another PMD metric or an already reviewed
  control at the same semantic boundary;
- `analyzer-false-positive` — the stated condition is not true under the
  implementation's ownership or data-flow contract;
- `policy-noise` — PMD observes the syntax correctly, but the generic policy is
  inappropriate for this boundary.

### 11.1 Overall disposition

| Disposition | Occurrences | Meaning in this baseline |
|---|---:|---|
| `actionable-fix` | 3 | One redundant initialization and two default-charset constructors |
| `debt-hotspot` | 7 | Seven distinct orchestration/construction seams; no behavior fix is authorized by the metric alone |
| `overlap` | 5 | Four duplicate PMD hotspot occurrences and one semantic overlap with the reviewed SpotBugs exception boundary |
| `analyzer-false-positive` | 17 | Resource/data-flow ownership is present but not proved by PMD |
| `policy-noise` | 60 | Correct syntax observation with no useful repository policy signal |
| **Total** | **92** | **Complete P1 baseline accounting** |

The raw occurrence signal is therefore 3 bounded fixes plus 7 semantic debt
clusters. The other 82 occurrences are overlap or noise. This does not imply a
10/92 defect rate: the seven hotspots are refactoring evidence, not defects,
and the two charset occurrences are one code change at one boundary.

### 11.2 Track A — dead and unused code

| Rule/site | Count | Disposition | Evidence |
|---|---:|---|---|
| `UnusedAssignment`: `SmbChangeNotifyWatcher.runSession/currentPending` | 1 | `analyzer-false-positive` | The volatile null publication is read by concurrent `close()` and is not dead even though the worker thread does not reread it |
| `UnusedAssignment`: `RemoteFetchDetectionCoordinator.runDetection/detected` | 1 | `actionable-fix` | The initial zero is overwritten before every read; removal is a small behavior-neutral cleanup, not a correctness fix |
| `UnusedAssignment`: `ArtifactWritePlan.materialize/idOffset++` | 1 | `analyzer-false-positive` | The incremented state supplies the next loop iteration; PMD mistakes the post-increment expression for an unused store |
| `UnusedFormalParameter`: the three JDBC `RowMapper` methods | 3 | `policy-noise` | `rowNum` is required by Spring's callback signature even though these mappings do not need it |

Track A totals: 1 actionable cleanup, 2 analyzer false positives and 3 policy
noise occurrences. `UnusedLocalVariable`, `UnusedPrivateField` and
`UnusedPrivateMethod` produced no findings.

### 11.3 Track B — correctness, failure and portability

| Rule/site cluster | Count | Disposition | Evidence |
|---|---:|---|---|
| `CloseResource`: four locals in `CsvSliceMaterialization.beginArtifact` | 4 | `analyzer-false-positive` | Ownership is transferred through writer/digest layers to the retained `CSVPrinter`; partial construction closes the deepest available owner and preserves close failure as suppressed |
| `UseTryWithResources`: `NioExportOperationGuard.FileLease.close` | 1 | `policy-noise` | The method implements the `AutoCloseable` boundary itself and must release lock, channel and local lock in a controlled order |
| `PreserveStackTrace`: `JdbcExportRunLedger.tryStart` | 1 | `policy-noise` | A unique-index conflict is decoded into an idempotent winner or a typed state-transition diagnostic; an unexplained database failure is rethrown unchanged |
| `CloseResource`: `SmbChangeNotifyWatcher.WatchWorker.close` | 1 | `analyzer-false-positive` | The local is a volatile alias of the TWR-owned current session, used only to cancel it from another thread |
| `CloseResource`: `SmbFileTransport.withClient` | 1 | `analyzer-false-positive` | Clients are intentionally cached per endpoint and closed on transient failure, idle expiry or adapter shutdown |
| `CloseResource`: connection/share in `SmbjChangeNotifySessionFactory.open` | 2 | `analyzer-false-positive` | The returned session owns directory/share/client; partial initialization closes the client, which owns its connections |
| `UseTryWithResources`: `SmbjChangeNotifySession.close` | 1 | `policy-noise` | Explicit best-effort directory/share cleanup guarantees final client teardown |
| `PreserveStackTrace`: `SmbjChangeNotifyPending.await` | 1 | `analyzer-false-positive` | `ExecutionException` is intentionally unwrapped and its actual cause becomes the cause of `RemoteTransportException` |
| `UseTryWithResources`: `SmbjShareClient.close` | 1 | `policy-noise` | Explicit share cleanup followed by unconditional client teardown is the resource's own close implementation |
| `CloseResource`: connection/share in `SmbjShareClientFactory.open` | 2 | `analyzer-false-positive` | The returned adapter owns the client/share graph; failed construction closes the client |
| `RelianceOnDefaultCharset`: `IocExtractorApplication` stdout/stderr writers | 2 | `actionable-fix` | Early CLI help/error text currently follows the host default charset; an explicit console encoding removes host-dependent behavior |
| `CloseResource`: Spring application context in `IocExtractorApplication.main` | 1 | `policy-noise` | Daemon context is process-lifetime state; the oneshot path delegates orderly shutdown to `SpringApplication.exit` |
| `CloseResource`: `DaemonExportScheduler.nudge` executor alias | 1 | `analyzer-false-positive` | The field-owned executor is created in `start` and shut down in `stop`; the local prevents a concurrent field reread |
| `PreserveStackTrace`: the two `IdStart` conversion catches | 2 | `policy-noise` | Binding deliberately exposes one stable validation exception and value-free operator contract rather than parser implementation detail |
| `CloseResource`: `LazyServiceStorage.initialize` | 1 | `analyzer-false-positive` | Successful datasource ownership moves to the lifecycle bean; failed migration closes it and suppresses a secondary close failure |
| `CloseResource`: `RemoteChangeWatchLifecycle.closeStartedWatches` | 1 | `analyzer-false-positive` | Watches are map-owned and the startup rollback loop closes every successfully created watch |
| `CloseResource`: `SyncConfig.transportRegistry` | 1 | `analyzer-false-positive` | Ownership moves into the Spring-managed `TransportRegistry`, whose close method deduplicates and closes adapter lifecycles |
| `PreserveStackTrace`: `PipelineRunner.executeInRunScope` | 1 | `overlap` | The original propagated failure is deliberately rethrown and observer/summary failures are suppressed; focused tests already pin the same boundary reviewed by SpotBugs |

Track B totals: 2 actionable portability occurrences, 15 analyzer false
positives, 7 policy-noise occurrences and 1 semantic overlap. The inspection
also confirmed the existing partial-construction and secondary-failure tests;
no resource leak or primary-exception replacement was found.

### 11.4 Track C — complexity

| Site cluster | Count | Disposition | Evidence |
|---|---:|---|---|
| `FileSourceMessageHandler.handle` (`Cognitive` + `Cyclomatic`) | 2 | 1 `debt-hotspot`, 1 `overlap` | One method owns retry, rejection and terminal diagnostic flow; the second metric adds no second debt item |
| `CsvArtifactSliceWriter` four methods | 4 | `policy-noise` | Flat state/protocol guards and typed failure mapping inflate path count without obscuring the slice lifecycle |
| `CsvSliceMaterialization.begin/beginArtifact` | 2 | `policy-noise` | Ordered callback and metadata invariants are expressed as explicit guard predicates |
| `SliceTreeVerifier.verifyIdentity` | 1 | `policy-noise` | Independent manifest identity guards are intentionally fail-closed |
| `JdbcExportRunLedger.validateTransitionData` | 1 | `policy-noise` | The branches are the explicit transition data contract |
| `SmbEndpointSettings` constructor | 1 | `policy-noise` | A cohesive immutable endpoint value contains ten independently validated configuration fields |
| `SmbExceptionMapper` three metrics | 3 | `policy-noise` | Cause-chain traversal and a flat closed token classification table are easier to audit in their current form |
| `SmbFileTransport` class/publish/path normalization | 3 | 1 `debt-hotspot`, 2 `policy-noise` | Atomic publish is a meaningful future decomposition seam; class total and security allow-list branches are not separate hotspots |
| `SmbjChangeNotifyPending.await` | 1 | `policy-noise` | Timeout, interrupt, cancellation and completion are the complete asynchronous outcome set |
| `AppConfig` class and two bean methods | 3 | `policy-noise` | Total complexity and long injection lists measure the composition root's explicit wiring role |
| `ExportPlanCatalog.format` | 1 | `policy-noise` | Flat format validation is the public-byte contract |
| `IocConfigPreflight` class total | 1 | `policy-noise` | The highest individual method is 7; total complexity merely aggregates independent validation rules |
| `IocEnvironmentPropertyMatcher.match` | 1 | `policy-noise` | Recursive record/list/map grammar is cohesive and bounded |
| `SyncHealthIndicator.overallStatus` | 1 | `policy-noise` | The boolean branches directly encode DOWN-before-DEGRADED precedence |
| JSON escaping and schema fingerprinting | 2 | `policy-noise` | Closed character escaping and aligned schema validation are auditable algorithms, not decomposition candidates |
| `ExportService` constructors | 2 | 1 `debt-hotspot`, 1 `overlap` | Both findings describe one broad orchestration collaborator surface |
| `IngestionService` constructor and recovery flow | 2 | 2 `debt-hotspot` | Collaborator breadth and recovery control flow are distinct future seams |
| `IocExtractionService` construction/pipeline plus its factory | 3 | 1 `debt-hotspot`, 2 `overlap` | All three expose one missing construction/wiring bundle rather than three independent problems |
| `ArtifactPublishService` class total | 1 | `debt-hotspot` | Reconcile, discovery and execution remain cohesive today but form a credible future decomposition seam |
| `BoundedKeyedSerialExecutor.submit` nesting | 1 | `policy-noise` | Nesting is confined to one synchronized admission state machine; mechanical extraction would hide its lock invariant |
| `PipelineRunner.executeInRunScope` | 1 | `policy-noise` | Nested scopes preserve exact stage/observer/diagnostic failure precedence and have focused regression coverage |

Track C totals: 7 distinct debt hotspots, 4 duplicate metric occurrences and
26 policy-noise occurrences. No metric is treated as proof that a class violates
SRP or that a method is incorrect.

The seven debt hotspots are handed to the `R030-QUAL` review ledger as
`QUAL-PMD-01..07`, in the table order represented by handler, SMB publish,
export construction, ingestion construction, ingestion recovery, extraction
construction and publish saga. Their `debt` disposition does not schedule a
0.3.0 refactoring.

### 11.5 Complexity threshold sensitivity

The PMD XML already records each measured metric value, so P2 recalculated the
inclusive threshold results from that authoritative report. No alternate
ruleset was written and no analyzer run was used to select a cosmetically empty
result.

| Rule | Thresholds | Findings | Interpretation |
|---|---|---:|---|
| `CyclomaticComplexity` | method `10`, class `80` (default) | 25 | 21 methods + 4 class totals; dominated by guard and composition-root noise |
| `CyclomaticComplexity` | method `12`, class `100` | 14 | Keeps the real handler hotspot but also 13 state/validation occurrences |
| `CyclomaticComplexity` | method `15`, class `120` | 3 | Drops the handler hotspot while retaining flat metadata/token checks and the composition root |
| `CyclomaticComplexity` | method `18`, class `140` | 1 | Retains only the flat SMB token classifier; raising the threshold cannot isolate useful signal |
| `CognitiveComplexity` | `15` (default) | 2 | Handler `20` plus SMB exception mapper `15` |
| `CognitiveComplexity` | `16` | 1 | Retains only the handler hotspot; this is the evidence-based P3 candidate |
| `CognitiveComplexity` | `20` / `21` | 1 / 0 | Shows the boundary; zero is not the selection objective |
| `ExcessiveParameterList` | `10` (default) | 9 | Mixes configuration/value objects, Spring wiring and application construction |
| `ExcessiveParameterList` | `12` | 4 | Narrows to export/extraction construction surfaces |
| `ExcessiveParameterList` | `13` | 3 | Two semantic hotspots: full export construction and extraction service/factory |
| `ExcessiveParameterList` | `14` | 1 | Retains only the 15-parameter extraction constructor |

No useful `CyclomaticComplexity` threshold separates the reviewed signal from
the noise. `CognitiveComplexity=16` and `ExcessiveParameterList=13` do. The
default `NPathComplexity=200` and default `NcssCount` produced zero findings;
lower thresholds were not invented merely to create work.

### 11.6 Track D — allocation and strings

All 24 Track D occurrences are `policy-noise`:

- the 23 `AvoidInstantiatingObjectsInLoops` sites create an object whose
  identity belongs to one row, artifact, endpoint, remote watch, batch or
  failure (`FileSourceMessageHandler`, CSV/JDBC row materialization,
  `DataframeSchemaReconciler`, `JdbcSnapshotSliceReader`, `SmbFileTransport`,
  `AppConfig`, `DaemonPublishScheduler`, `ExportPlanCatalog`,
  `IocConfigPreflight`, `RemoteChangeWatchLifecycle`, `SyncHealthIndicator`,
  `TransportRegistry`, `IngestRunRecoveryService`, `IngestionService`,
  `WriteArtifactsStage` and `RemoteSourceMonitor`); the two early-CLI callback
  objects are created only while building a lightweight command model;
- `ArtifactIdentityDefinition` has one
  `ConsecutiveAppendsShouldReuse` occurrence. Chaining the same builder would
  be cosmetic and would not remove an established allocation path.

There is no profile, allocation measurement or plausible reusable mutable
object behind these findings. P2 therefore does not authorize object pooling,
collection reuse across emitted rows/batches or performance claims.

### 11.7 Overlap with existing controls and tests

The file-level SpotBugs intersection remains 11 of 48 PMD-reported files. An
exact method comparison finds 12 PMD occurrences in 9 methods that also carry a
SpotBugs accepted identity, but almost all concern different semantics: SQL
construction versus row allocation, unchecked boundary policy versus
complexity/allocation, or lifecycle ownership versus exception translation.
Only `PipelineRunner.executeInRunScope/PreserveStackTrace` is a semantic
duplicate of an already reviewed SpotBugs failure-precedence boundary.

Focused tests provide supporting evidence for the reviewed behavior rather
than making findings disappear: `PipelineRunnerTest` and
`LazyServiceStorageTest` pin secondary-failure suppression;
`NioExportOperationGuardTest`, SMB watcher/transport tests,
`RemoteChangeWatchLifecycleTest` and scheduler tests pin lifecycle ownership;
JDBC export-ledger tests pin concurrent-start classification; ingestion and
keyed-executor tests pin the state machines identified as complexity hotspots.
There is no performance benchmark supporting Track D. Javac and ArchUnit remain
complementary controls and produced no equivalent unused/resource/complexity
finding.

### 11.8 P3 adoption decision

P3 adopts PMD source analysis with a reduced 22-rule report-only policy. It
keeps three still-interesting ownership/size rules executable in a separate
opt-in watchlist, drops seven rules whose current semantics cannot support a
useful repository policy and replaces two PMD-8-deprecated rules with the
already adopted successor.

| P3 disposition | Rules | Decision rationale |
|---|---|---|
| Adopt as named | `UnusedAssignment`, `UnusedFormalParameter`, `UnusedLocalVariable`, `UnusedPrivateField`, `UnusedPrivateMethod` | The three formal-parameter findings were cheaply clarified as intentionally ignored JDBC callback arguments; retaining the rule protects future real API leftovers without accepting a baseline |
| Adopt as named | `RelianceOnDefaultCharset`, `UseStandardCharsets`, `EmptyCatchBlock`, `DoNotThrowExceptionInFinally`, `ReturnFromFinallyBlock`, `BrokenNullCheck`, `MisplacedNullCheck`, `UnusedNullCheckInEquals`, `UselessPureMethodCall`, `InvalidLogMessageFormat`, `UseLocaleWithCaseConversions` | Bounded correctness/portability rules; the two charset findings were fixed and the remaining rules are narrow guards rather than broad style policy |
| Adopt with calibrated properties | `CognitiveComplexity` at `reportLevel=16`; `ExcessiveParameterList` at `minimum=13` | Repository thresholds retain reviewed handler/construction seams; `minimum` is the actual PMD 7 property name, not `reportLevel` |
| Adopt as named | `NPathComplexity` | Default 200-path guard stayed silent and represents a materially high outlier rather than style |
| Adopt as named | `InefficientStringBuffering`, `StringInstantiation`, `UselessStringValueOf` | Zero-signal, narrow high-confidence allocation/string mistakes; no generic loop policy |
| Executable watchlist | `PreserveStackTrace`, `CloseResource`, `NcssCount` | Preserve future signal, but keep 20 ownership-noisy occurrences and the currently silent size rule outside regular CI until lifecycle/ownership or size evidence changes |
| Drop | `UseTryWithResources`, `CyclomaticComplexity`, `AvoidDeeplyNestedIfStmts`, `AvoidInstantiatingObjectsInLoops`, `ConsecutiveAppendsShouldReuse`, `ConsecutiveLiteralAppends`, `AddEmptyString` | Current repository evidence is framework/lifecycle/state-machine/style noise or lacks measured performance benefit; enabling them would institutionalize noise rather than preserve signal |
| Replace/remove | `AvoidLosingExceptionInformation`, `UselessOperationOnImmutable` | PMD 7.26.0 marks both for removal; adopted `UselessPureMethodCall` is their named PMD 8 successor |

The P3 count is therefore 22 adopted, 3 watchlist, 7 dropped and 2 replaced.
"Dropped" means absent from both routine analysis and watchlist; it is not an
acceptance of current findings. A future code shape, PMD improvement or measured
signal can reopen a rule through the same bounded evaluation. "Watchlist" means
the rule remains runnable and strictly configured, but is not part of the
regular CI job.

### 11.9 P3 implementation and verification

P3 removed six P1 occurrences through bounded implementation changes: the
three P2 actionable occurrences plus three deliberately ignored callback
parameters that made `UnusedFormalParameter` cheap to adopt:

- early CLI stdout/stderr writers now use explicit UTF-8;
- the overwritten `detected` initialization was removed from
  `RemoteFetchDetectionCoordinator`;
- three Spring JDBC callback arguments were renamed to `ignoredRowNum` so the
  intentional interface obligation is explicit.

The adopted ruleset now reports 6 visible advisory occurrences in 6 files:
`UnusedAssignment` 2, `CognitiveComplexity` 1 and
`ExcessiveParameterList` 3. The two assignments remain reviewed analyzer false
positives; the complexity occurrences map to existing `QUAL-PMD-01`,
`QUAL-PMD-03` and `QUAL-PMD-06` debt rather than a hidden baseline. The
watchlist reports 20 occurrences in 13 files: `CloseResource` 15 and
`PreserveStackTrace` 5; `NcssCount` remains at zero.

`tools/ci/pmd.sh policy` is the single developer/CI leaf command. GitHub runs it
as a separate regular job and uploads `target/pmd/`; `make ci` runs the same job
before the full reactor build. Ordinary `make verify` still leaves the profile
inactive because P1 demonstrated aggregate-mojo contention in the parallel full
reactor. `make pmd-watchlist` selects a different ruleset and
`target/pmd-watchlist/`, so it neither overwrites nor validates the policy output.

Root validation pins both exact rule sets, both calibrated properties, profile
selection, output paths and late verifier arguments. The synthetic harness
passes 7 happy paths and 48 negative scenarios, including engine/property,
ruleset-attribute, calibrated-threshold, watchlist selection,
directory and missing-report mutations. A warm policy repeat completed in
`12.26 s` process wall (`11.127 s` Maven wall, `667,076 KiB` peak RSS); the
watchlist completed in `8.97 s` process wall (`7.928 s` Maven wall,
`625,200 KiB` peak RSS). Both used PMD `7.26.0`, produced XML/HTML and passed
scope/report integrity with no analyzer errors.

## 12. Decision log

| Date | Decision | Rationale |
|---|---|---|
| 2026-08-09 | Activate `BUILD-PMD-06` as a bounded evaluation, not implicit adoption | SpotBugs triage is complete, so incremental source-analysis signal can now be measured independently |
| 2026-08-09 | Complete P0 with exact rule references grouped into four tracks | Whole categories contain 222 mixed-policy rules and would make upgrades alter policy implicitly |
| 2026-08-09 | Use plugin `3.28.0` with candidate engine `7.26.0` in P1 | Keep the stable plugin while evaluating current PMD fixes relevant to the candidate rules |
| 2026-08-09 | Keep P0 documentation-only | Tool wiring, reports and cost belong to P1 and require their own reviewable change |
| 2026-08-10 | Complete P1 with a dedicated opt-in report owner and fail-closed integrity contract | The candidate versions work on JDK 21, exact scope and reports are reproducible, while findings remain advisory |
| 2026-08-10 | Select only `pmd-report` and its upstream reactor in `make pmd-analysis` | Running all independent aggregate report mojos concurrently exposed Maven artifact-resolution contention; selected-reactor execution retains the intended source/test graph without the race |
| 2026-08-10 | Carry 92 raw occurrences into P2 without remediation or suppression | P1 measures compatibility and inventory; semantic signal/noise decisions belong to the next checkpoint |
| 2026-08-11 | Complete P2 with full occurrence accounting and no production remediation | Review found 3 bounded fixes, 7 semantic debt hotspots, 5 overlaps and 77 noise/false-positive occurrences, but no immediate correctness/resource defect |
| 2026-08-11 | Leave the P1 ruleset unchanged until P3 | Threshold sensitivity supports a reduced candidate set, but lifecycle, ownership and adoption consequences require the explicit P3 decision |
| 2026-08-12 | Adopt a reduced 22-rule PMD source policy in report-only mode | The reduced set retains bounded correctness/dead-code guards and calibrated debt signal without converting 86 reviewed noise/debt occurrences into a suppression baseline |
| 2026-08-12 | Keep `PreserveStackTrace`, `CloseResource` and `NcssCount` in a separate executable watchlist | The rules retain possible future value, while current ownership assumptions or zero signal do not justify regular CI policy |
| 2026-08-12 | Run the adopted policy as a separate regular CI job | Selected-reactor Maven `verify` preserves fail-closed scope/ruleset/report checks and avoids the aggregate-mojo contention observed in the full parallel reactor |
