# Build quality

[Русская версия](ru/build-quality.md)

Build quality is a repository-owned Maven capability, not an external service.
The canonical `verify` lifecycle compiles and tests the reactor, enforces module
and architecture rules, analyzes production code and builds aggregate reports.
Each adopted static-analysis control checks that its expected result is current
and complete. Controls have different policies: some findings block
immediately, some remain diagnostic, and analyzer or report-integrity failures
for an adopted control never become a green build silently.

This document explains the durable mechanism and its maintenance workflow. It
does not record release snapshots, tool-version history, current finding counts
or individual finding dispositions; those belong to executable configuration,
tracked baselines and generated reports.

## Build flow

The local and CI build use the same Maven entry point:

```text
make verify
  -> tools/ci/build.sh
  -> ./mvnw -B -ntp -T 1C verify
       |
       +-- root validate
       |    compile JDK-only quality verifiers
       |    run their synthetic-reactor contract matrices
       |    reconcile reactor modules with SpotBugs/CPD/PMD scope manifests
       |    validate test discovery/source inventory and coverage topology
       |    validate the accepted SpotBugs baseline and generate its filter
       |
       +-- initialize
       |    remove stale test, coverage and analyzer outputs
       |
       +-- ordinary reactor lifecycle
       |    compile -> test -> package
       |    Maven Enforcer + ArchUnit + documentation contracts
       |    module JaCoCo and raw SpotBugs reports
       |    filtered SpotBugs presentation reports
       |
       +-- final build-support modules
            JaCoCo aggregate + exact test-report union + coverage policy
            raw and filtered SpotBugs aggregates + exact baseline gate
            repository-wide PMD CPD report + exact group-count ratchet

successful Maven build
  -> revalidate the Codecov aggregate input

CI build completion (success or failure)
  -> upload test, coverage, SpotBugs and CPD evidence even when the build failed late

successful CI build
  -> separate least-privilege reporting job downloads the verified artifact
  -> pinned Codecov Action uploads only the aggregate XML, best-effort

regular PMD source-policy job
  -> tools/ci/pmd.sh policy
  -> selected upstream reactor, tests/analyzers not owned here skipped
  -> exact adopted ruleset + XML/HTML integrity + per-rule count ratchet
  -> upload PMD source evidence even when analysis failed late
```

PMD source analysis is an adopted regular control, but it stays in its own CI
job rather than the canonical full-reactor lifecycle:

```text
make pmd-analysis
  -> tools/ci/pmd.sh policy
  -> ./mvnw -Ppmd-analysis -pl build-support/pmd-report -am verify
       compile the selected upstream reactor; skip tests, JaCoCo and SpotBugs
       run one aggregate-pmd-no-fork execution
       verify exact scope, ruleset, engine, XML/HTML and finding counts

make pmd-watchlist
  -> tools/ci/pmd.sh watchlist
  -> the same lifecycle with a separate three-rule ruleset and output directory
```

Selecting the PMD report owner and its upstream graph keeps this selected-reactor
aggregator from racing the independent JaCoCo, SpotBugs and CPD aggregate mojos
in the parallel full reactor. The regular policy job complements rather than
replaces the separate `make verify` release gate. The watchlist is locally
opt-in and is not a merge gate.

`tools/ci/build.sh` and the regular PMD policy invocation record independent
workspace fingerprints. `make context` exposes both `verify.fresh` and
`pmd.fresh`; a complete local quality claim requires both results to be
`passed` and fresh for the current commit and worktree. Running the watchlist
does not refresh PMD policy evidence.

Workflow presence and branch enforcement are different contracts. The
repository owns the jobs and artifacts in `.github/workflows/ci.yml`; whether a
job is a required merge status is external GitHub branch/ruleset state and must
be verified explicitly before claiming it as a required gate.

Maven may report that aggregate mojos require exclusive reactor access during
the parallel build. That message describes Maven serialization of aggregate
work, not an analyzer error. Correct ordering comes from the build-support POMs:
their dependencies define the production universe and ensure that aggregation
runs only after the applicable modules.

## Control model

| Control | Input | Enforcement |
|---|---|---|
| Maven Enforcer | Toolchain and POM metadata | Violations block every ordinary build |
| JUnit/Surefire/Failsafe contracts | Fast, integration, architecture, golden and documentation suites | Surefire owns `*Test`; Failsafe owns `*IT`; failures and lifecycle/report-union drift block `verify` |
| ArchUnit | Compiled production classes | Dependency or package-boundary violation blocks `verify` |
| JaCoCo | Test execution data and 25 production JARs | Exact universe/report integrity, aggregate/per-module no-regression ratchets and fixed aggregate/domain/application floors block `verify` |
| Codecov | The verified reactor aggregate JaCoCo XML | Informational project/base-relative/patch reporting only; external upload failure never changes the local gate result |
| SpotBugs | Applicable production bytecode | New, stale, moved or metadata-drifted findings block the exact ratchet; analyzer/report failures also block |
| PMD CPD | Applicable checked-in production Java sources | Every duplicate stays visible; analyzer/scope/report failure or a change from the reviewed group-count snapshot blocks `verify` |
| PMD source policy | 22 named rules over applicable checked-in production Java sources | Regular separate CI job; rules outside the advisory-count snapshot have zero tolerance, and any per-rule count drift blocks |
| PMD source watchlist | Three deferred rules over the same sources | Explicit local review command; findings remain diagnostic and it is not a regular CI gate |
| Maven dependency analysis | Main and test bytecode | Opt-in advisory report; not part of ordinary `verify` |
| Dependency-Check | Resolved dependency graph and local vulnerability data | Separate security workflow, deliberately outside ordinary `verify` |

The distinction between a finding policy and tool health is important. An
unhealthy analyzer always fails. CPD and PMD additionally use small count
snapshots: they force semantic review when signal changes but do not suppress
historical occurrences. SpotBugs is stricter still: every accepted raw finding
has an exact reviewed identity rather than a count-only snapshot.

The test lifecycle is documented in [`docs/TESTING.md`](../TESTING.md).
Surefire owns the fast naming cohort and Failsafe owns the integration naming
cohort. Root validation checks the accepted taxonomy and source universe; a late
coverage-module check proves that the disjoint report sets cover the expected
source union. External suite shells remain explicitly conditioned and their
skips are not treated as successful provisioned evidence.

## Ownership boundaries

| Surface | Owner |
|---|---|
| Versions, inherited plugin executions and root `validate` wiring | Root [`pom.xml`](../../pom.xml) |
| Stable developer commands | Root [`Makefile`](../../Makefile) |
| CI Maven invocations and local freshness fingerprints | [`tools/ci/build.sh`](../../tools/ci/build.sh) and [`tools/ci/pmd.sh`](../../tools/ci/pmd.sh) |
| CI jobs and report retention | [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) |
| Shared scope/report verifier and contract matrices | [`build-support/build-quality/`](../../build-support/build-quality/) |
| SpotBugs scope, accepted baseline, aggregation and late gate | [`build-support/spotbugs-report/`](../../build-support/spotbugs-report/) |
| CPD scope, source universe, aggregation and late gate | [`build-support/cpd-report/`](../../build-support/cpd-report/) |
| PMD source-policy/watchlist scope, exact rulesets, aggregation and late gate | [`build-support/pmd-report/`](../../build-support/pmd-report/) |
| JaCoCo scope, baselines, aggregate and late gate | [`build-support/coverage-report/`](../../build-support/coverage-report/) |
| Test taxonomy, source ratchet and report-union verifier | [`build-support/test-quality/`](../../build-support/test-quality/) |

The `build-support/build-quality` directory is intentionally not a Maven
module. Root `validate` compiles its dependency-free Java sources directly, so
scope and baseline mistakes fail before child projects are built. Reporting
modules are build-only POMs: their dependencies define ordering and must never
become runtime or library dependencies.

## SpotBugs exact-finding ratchet

SpotBugs uses a project-owned exact ratchet instead of `spotbugs:check`. The
standard check goal would run analysis again and still would not own the
project's per-occurrence acceptance policy. The current design performs one
unfiltered bytecode analysis per applicable module, then uses that result for
both enforcement and presentation.

### Scope and inheritance

The root POM owns the inherited production-bytecode execution. The
[`spotbugs-scope.tsv`](../../build-support/spotbugs-report/spotbugs-scope.tsv)
registry gives every root reactor project exactly one disposition:

- `analyzed` — a production JAR that must produce raw and filtered reports;
- `excluded` — no production-bytecode analysis; a child module must explicitly
  configure the inherited SpotBugs plugin with `<skip>true</skip>`;
- `aggregate` — the one build-only module that owns reactor aggregation and the
  late gate.

The reusable `ioc-application-tck` JAR is explicitly excluded because its main
classes implement test contracts rather than runtime production behavior. Root
and other build-only POMs are also explicit, never implicit omissions.

Root `validate` requires the manifest paths to equal the root `<modules>` set,
checks artifact IDs and packaging, checks excluded-module skip declarations and
requires the aggregate module's ordering dependencies to equal the analyzed
set. Adding a reactor module therefore fails closed until its disposition and
ordering are reviewed.

### One analysis, two views

Each analyzed module produces:

| Output | Meaning |
|---|---|
| `target/spotbugs-raw/spotbugs-raw.xml` | Native unfiltered analyzer evidence and blocking input |
| `target/spotbugs/spotbugs.xml` | Filtered machine-readable presentation view |
| `target/spotbugs/spotbugs.html` | Filtered human-readable module view |

The aggregate module produces the same raw/filtered separation under
`build-support/spotbugs-report/target/`. Its raw HTML is the most useful
human-readable triage entry point:
`target/spotbugs-raw/spotbugs.html`.

The accepted baseline is not passed to bytecode analysis. Root `validate`
materializes an untracked `FindBugsFilter` under
`target/build-quality/spotbugs-accepted-filter.xml`; the JDK-only
`SpotBugsReportFilter` applies the engine's workflow filter to existing raw XML
without another analysis pass. The filtered report is therefore only a quiet
day-to-day view. Enforcement always compares unfiltered native XML.

### Accepted finding contract

[`spotbugs-accepted-findings.xml`](../../build-support/spotbugs-report/spotbugs-accepted-findings.xml)
is the only tracked acceptance source of truth. Every finding records:

- stable evidence ID and owning module;
- bug type, instance hash and occurrence number;
- priority, rank and category;
- exact primary class plus method or field and JVM descriptor;
- source path and bytecode offset when the analyzer provides them;
- disposition, owner, evidence and non-empty rationale;
- a concrete root-level review trigger;
- a narrow presentation selector containing bug type, exact class and exact
  method and/or field.

Hard comparison uses analyzer identity and metadata, including occurrence and
bytecode offset. Source line is retained for diagnostics but is advisory, so an
unrelated line shift does not churn the baseline. Findings without a bytecode
location use their otherwise exact class/member identity.

SpotBugs filters cannot select an instance hash. A single narrow presentation
selector may therefore hide several already reviewed occurrences at the same
member. This does not weaken enforcement: every raw occurrence still needs its
own accepted identity. A new occurrence with an already known hash or selector
fails as new.

Review triggers name the external invariant that justified acceptance, for
example a path-provenance or boundary-failure contract. Generic text such as
"review when code or analyzer changes", unknown references, duplicate triggers
and unused triggers are rejected. Package-, category- and pattern-wide
selectors are also rejected.

### Early and late gates

The root `validate` execution:

1. compiles the shared verifiers;
2. runs the scope and baseline synthetic fixture matrices;
3. reconciles the reactor, analyzer manifests and report dependencies;
4. validates baseline schema, identities, metadata and review triggers;
5. generates the operational presentation filter.

The aggregate module's late `verify` execution then requires:

- one healthy raw XML and one filtered XML/HTML pair for every analyzed module;
- no analyzer reports from excluded modules;
- healthy raw and filtered aggregate reports;
- zero analyzer errors and missing classes;
- the raw aggregate to equal the exact multiset union of module raw findings;
- the accepted identities to equal raw findings exactly;
- zero findings in filtered module and aggregate views;
- every acceptance selector to match the raw finding it claims to explain.

Consequently these conditions all fail ordinary `make verify`: a new finding,
a fixed finding with a stale acceptance, changed priority/rank/category,
changed hard identity, engine-version drift, a missing report, visible filtered
output, aggregate divergence, analyzer errors or missing classes.

### Handling a SpotBugs failure

1. Run the full `make verify`. Scope/baseline schema failures happen early; a
   finding delta or report-integrity failure happens in the final aggregate
   module.
2. Inspect the module raw XML and the raw aggregate HTML. Do not infer safety
   from an empty filtered report.
3. Prefer fixing a valid finding and adding a focused regression. Removing the
   finding without removing its acceptance is intentionally a stale-baseline
   failure.
4. If the finding is demonstrably analyzer noise or an inapplicable generic
   policy, run `make spotbugs-baseline-proposal` immediately after the failed
   full analysis. The command reads current module raw reports and writes an
   untracked new/stale identity delta under `target/build-quality/`.
5. Review the proposal, then edit the tracked accepted-findings XML manually.
   Add exactly one acceptance with disposition, owner, evidence, rationale,
   concrete review trigger and exact selector. Never copy the proposal wholesale
   and never edit the generated filter.
6. Remove a stale acceptance in the same change that removes its finding.
7. Run full `make verify` again and inspect the accepted-baseline diff before
   committing.

The proposal is deliberately non-accepting: it cannot populate human judgement
fields or modify the tracked baseline. It also fails when module raw reports are
missing or unhealthy. Run it before a focused Maven command, because focused
lifecycles may delete or replace only part of the reactor report set.

### Toolchain and analyzer upgrades

Plugin and engine versions are pinned independently in the root POM. Treat a
JDK, compiler, plugin or engine upgrade as an explicit rebaseline event:

1. run a clean full reactor analysis;
2. inspect every identity, occurrence, rank and bytecode delta;
3. verify the workflow-filter entry point and XML/HTML rendering;
4. update accepted engine metadata only after semantic review;
5. run the complete negative fixture matrices and a second full `verify`.

There is intentionally no accept-all refresh command. A tool upgrade must not
turn newly detected defects into accepted findings mechanically.

## PMD CPD

CPD is a repository-wide source analysis, not a per-module duplication check.
Its [`cpd-scope.tsv`](../../build-support/cpd-report/cpd-scope.tsv) gives every
reactor project an explicit disposition. The aggregate module owns a positive
list of applicable checked-in `src/main/java` roots and depends on those
modules for reactor ordering.

The current scope excludes test-contract code, tests, Maven-generated sources,
build outputs and vendor/generated trees. Root `validate` reconciles the
manifest, ordering dependencies and configured source roots. Before analysis,
the previous report directory is deleted; afterwards, the verifier requires
structurally valid XML/HTML and exact equality between XML file paths and every
current Java file in the analyzed roots.

Outputs are:

- `build-support/cpd-report/target/cpd/cpd.xml` — machine-readable evidence;
- `build-support/cpd-report/target/cpd/cpd.html` — human-readable report.

Duplicate matches remain diagnostic. A match is a prompt for semantic review,
not an automatic refactoring instruction: shared tokens do not prove shared
knowledge, legal dependency direction or a useful abstraction. The threshold
and language options belong to the CPD report POM and require repository
calibration before change.

The report POM also owns `ioc.cpd.expectedDuplications`. The late verifier
requires the number of XML `duplication` groups to equal this reviewed snapshot.
Both an increase and a decrease stop the build: inspect the complete XML/HTML,
fix an accidental duplicate or update the one value in the same reviewed
change. Requiring an update after a decrease prevents unused budget from
silently admitting later debt.

This is deliberately a group-count ratchet, not an assertion that every token
match is a defect and not an exact CPD baseline. A new group can replace a
removed group without changing the count; exact token fingerprints would add
considerable churn because CPD group boundaries move when surrounding code is
edited. Introduce that stronger identity only after evidence that the simple
ratchet repeatedly misses relevant changes. Source-local `CPD-OFF` markers,
class/pair exclusions and generated accepted-copy registries are not part of
the contract.

## PMD source-analysis policy and watchlist

PMD source analysis is a permanent mixed blocking/advisory control, separate
from the CPD report even though both use Maven PMD Plugin. Its
[`pmd-scope.tsv`](../../build-support/pmd-report/pmd-scope.tsv) gives every
reactor project one disposition, while the report POM owns a positive list of
production `src/main/java` roots and ordering dependencies.

The checked-in [`pmd-ruleset.xml`](../../build-support/pmd-report/pmd-ruleset.xml)
is the adopted policy: all 22 rules are named individually. Its calibrated
properties are `CognitiveComplexity.reportLevel=16` and
`ExcessiveParameterList.minimum=13`. Category-wide references, ruleset
exclusions, suppressions and an accepted-finding identity baseline are
forbidden.
Plugin and PMD engine dependencies are pinned independently so an engine
upgrade cannot silently fall back to the plugin's bundled version.

The `16/13` thresholds were calibrated against reviewed handler and construction
hotspots. Lower thresholds also flagged flat validation, state handling and
framework wiring; no evaluated `CyclomaticComplexity` threshold isolated a useful
subset. Recalibration requires a reviewed comparison of actionable findings and
noise across the full production scope, rather than a target finding count.

The separate
[`pmd-watchlist-ruleset.xml`](../../build-support/pmd-report/pmd-watchlist-ruleset.xml)
contains exactly `PreserveStackTrace`, `CloseResource` and `NcssCount`. The first
two currently mostly report framework/lifecycle ownership that PMD cannot see;
the third reports large methods for explicit review. Keeping them executable
preserves future detection without pretending
that their present signal has adoption quality.

Rule disposition is about routine execution, not whether a rule happened to
find code on adoption day:

| Disposition | Meaning | Current rules outside the adopted set |
|---|---|---|
| Adopted | Runs in every PMD policy job. A rule not listed in the advisory-count snapshot has expected count zero and blocks on its first occurrence | The 22 exact references in `pmd-ruleset.xml` |
| Watchlist | Still executable with full scope/report integrity, but not regular CI policy | `PreserveStackTrace`, `CloseResource`, `NcssCount` |
| Dropped | Not executed routinely because repository evidence showed style, framework/state-machine noise or unmeasured optimization advice | `UseTryWithResources`, `CyclomaticComplexity`, `AvoidDeeplyNestedIfStmts`, `AvoidInstantiatingObjectsInLoops`, `ConsecutiveAppendsShouldReuse`, `ConsecutiveLiteralAppends`, `AddEmptyString` |
| Replaced | Removed deprecated PMD 7 names; the maintained successor is already adopted | `AvoidLosingExceptionInformation`, `UselessOperationOnImmutable` → `UselessPureMethodCall` |

Dropped does not mean permanently forbidden or that old findings were accepted.
Re-evaluate the relevant rule family after a PMD major/minor upgrade that
changes rule semantics, a demonstrated escaped defect, a materially new
framework/lifecycle pattern, measured allocation/string performance debt, or a
release-quality policy review. Re-evaluation starts in report-only mode against
the whole production-source scope; it does not add the rule directly to the
adopted set.

The profile runs one `aggregate-pmd-no-fork` execution in `verify`. It deletes
only the selected stale PMD output first. The policy writes:

- `build-support/pmd-report/target/pmd/pmd.xml` — machine-readable findings;
- `build-support/pmd-report/target/pmd/pmd.html` — human-readable report.

The watchlist writes the same pair under `target/pmd-watchlist/`, so running it
does not overwrite or validate the policy report accidentally.

[`pmd-advisory-counts.tsv`](../../build-support/pmd-report/pmd-advisory-counts.tsv)
is the complete non-zero PMD snapshot. Each row names one exact adopted rule,
its expected finding count and the reason the current signal remains advisory.
Every adopted rule absent from the file has an implicit expected count of zero.
The verifier requires exact per-rule equality, so both growth and improvement
require semantic review and an explicit snapshot update. The file is not a
suppression: all occurrences remain in XML/HTML, and adding a rule outside the
adopted ruleset is rejected.

This is a count ratchet, not a finding-identity baseline. Removing one finding
and introducing another finding of the same rule in the same run leaves the
count unchanged. The complete XML/HTML remains the review evidence for that
residual risk; a location- or fingerprint-based PMD baseline should be added
only if repeated escaped changes justify its maintenance cost.

The integrity verifier reconciles the root reactor, scope registry, report
dependencies, configured source roots, UTF-8 contract, engine dependencies and
exact ruleset. It then requires both reports, rejects PMD processing or
configuration errors and rejects any reported file outside the current source
inventory. PMD XML contains only files with findings; a source with zero
findings is therefore covered through the positive root/source inventory, not
through a fabricated report entry.

Root validation also pins both exact rulesets, calibrated properties, profile
selection, stale-output cleanup and late report-verifier command, including
`failonerror=true`. It rejects unexpected rule-level configuration, source
include/exclude patterns and production-source `NOPMD` or
`@SuppressWarnings("PMD…")` markers. Findings therefore cannot be made
invisible by deleting the gate or adding an unreviewed local suppression.
The late verifier also rejects a violation whose rule does not belong to the
selected policy/watchlist, so the two report paths cannot be substituted for
one another silently. For policy reports it then reconciles every per-rule
count; watchlist findings remain count-unconstrained.

New zero-tolerance findings or any advisory-count drift fail
`make pmd-analysis`. The regular CI job calls the same repository-owned leaf
command and retains the complete policy XML/HTML. Ordinary `make verify` does
not activate the profile because the selected aggregator previously contended
with independent aggregate mojos inside the parallel full reactor.

Do not add `pmd:check`, `NOPMD`, annotations, exclusions or a finding identity
baseline to make a report green. Review each changed occurrence semantically:
fix it when appropriate; otherwise update the relevant non-zero count and its
rationale in the same reviewed change. Moving a rule to the watchlist requires
evidence and a reviewed policy change; it is not a suppression mechanism.

Run `make pmd-watchlist` when any of these assumptions change:

- exception translation, causal-chain or secondary-failure precedence;
- ownership transfer for JDBC, SMB, Spring contexts, executors or other
  closeable lifecycles;
- a large method/type is materially restructured or a new one appears;
- PMD engine, Maven PMD Plugin, JDK or source-language level changes;
- before proposing adoption of any watchlist rule.

The watchlist remains advisory and outside regular CI. Its result must still be
structurally healthy: missing reports, analyzer errors, scope drift or ruleset
drift fail the command.

## Coverage

[TESTING.md](../TESTING.md#coverage-quality-gate) owns the coverage/test-quality
policy, including fixed floors and how to add useful behavioral tests. This
section explains collection, authority and enforcement.

### Collection and report authority

The inherited JaCoCo agent appends Surefire and Failsafe execution to each
functional module's `target/jacoco.exec`. With tests enabled, `initialize`
removes old test reports, execution data and module/aggregate coverage directories.
A skipped-test packaging/analyzer run preserves previous evidence; it does not
refresh coverage.

Module XML/HTML is generated under `target/site/jacoco/` during that module's
`verify`. The build-only coverage module runs afterwards and combines execution
data across the production graph under
`build-support/coverage-report/target/site/jacoco-aggregate/`.
A module's aggregate group can include downstream integration/E2E execution that
its earlier local report could not see. Gate decisions use aggregate/group
counters; local reports are required investigation/integrity evidence, not a
second baseline authority. Reactor ratios are computed from summed counters,
not an average of module percentages.

### Scope and policy registries

| Registry | Authority |
|---|---|
| [coverage-scope.tsv](../../build-support/coverage-report/coverage-scope.tsv) | Every reactor project's disposition and required/aggregate-only/no local report |
| [coverage-ratchets.tsv](../../build-support/coverage-report/coverage-ratchets.tsv) | Exact line/branch baseline counters, missed instructions, explicit instruction policy and rationale |
| [coverage-floors.tsv](../../build-support/coverage-report/coverage-floors.tsv) | Fixed line/branch floors, or explicit `none` for ratchet-only scopes |

The scope currently contains 31 projects, 25 production groups and 24 required
local reports. Root, reusable TCK and build-only POMs are outside production
denominators; `ioc-platform-errors` is aggregate-only because it has no local
test JVM. Its downstream execution still belongs to the production aggregate.
Manual JaCoCo class/package includes/excludes and covered-module skip overrides
are rejected.

Root `validate` compiles the JDK-only `CoverageVerifier`, runs its
synthetic-reactor matrix and reconciles the reactor, registries, artifact
coordinates, aggregate ordering dependencies, inherited agent/report/cleanup
wiring and late gate. Each aggregate/production scope needs both a ratchet row
and a floor-policy row.

Late `verify` requires non-empty aggregate XML/HTML, exact group membership and
counter sums, plus expected local XML/HTML and execution data. Unexpected local
reports/data from excluded or aggregate-only scopes are rejected. Stale-output cleanup prevents
an older file from satisfying a missing report in the canonical test lifecycle.
The separate test-lifecycle verifier checks the exact source/Surefire/Failsafe
union. Healthy percentages cannot compensate for a report-integrity failure.

### Exact comparisons

For reactor and every production group, the gate checks line/branch
non-regression against the recorded fraction and independently checks any fixed
floor:

```text
actualCovered * baselineTotal >= baselineCovered * actualTotal
actualCovered * 100 >= minimumPercent * actualTotal
```

The implementation uses integer cross-products, so rounded HTML percentages
cannot hide a regression. Ratchet comparison rejects a newly nonzero counter
after a zero-denominator baseline and disappearance of a previously nonzero
counter; a fixed-floor scope cannot use an absent denominator to pass.

Absolute missed branches also cannot grow. For example, going from 80/100
covered branches to 170/200 improves the ratio from 80% to 85%, but increases
missed branches from 20 to 30 and fails the missed-count check.

Missed instructions are enforced only when the registry column
`absolute-instructions` is `enforced`; `observed` retains the number for review.
Small scopes commonly use this extra protection, but the explicit registry
setting decides. The gate does not infer policy from module size.

### Diagnose a coverage failure

1. Identify whether the failure is topology/report integrity, a fixed floor,
   a ratio, missed branches or enforced missed instructions. Read the named
   scope and exact counters before changing tests.
2. For incomplete evidence, restore the correct lifecycle/report wiring and run
   the full gate. A focused or skipped-test run is not a replacement aggregate.
3. For a behavioral gap, follow the
   [coverage workflow](../TESTING.md#reach-the-gate-through-useful-tests): choose
   a contract case and independent oracle at the owning layer.
4. For legitimate removal/refactoring or a new scope/counter, review the changed
   denominator, behavior and tests explicitly. Keep floors intact; do not lower
   a baseline, relabel a report or add an exclusion to obtain a pass.
5. Run the complete gate after focused checks. Accept stable improvements with
   exact counters and rationale in the same reviewed change; inspect the
   protected metrics together so one update cannot loosen another.

### Codecov handoff

After a successful CI build, `tools/ci/codecov.sh verify-input` repeats local
report/policy verification before retention. A separate job downloads that
evidence and requires its aggregate XML before the full-SHA-pinned Codecov Action
and pinned CLI upload only the explicit file. Report discovery, plugins and
telemetry are disabled; OIDC permission belongs to the reporting job.

The Codecov project/base/patch statuses are informational. Missing project-owned
evidence fails its owning check; only the external upload Action is best-effort.
An external failure cannot replace local enforcement or turn failed local
coverage green. Exact reporting targets belong to
[TESTING.md](../TESTING.md#codecov-reporting).

## Dependency and security analysis

`make dependency-analysis` packages main and test bytecode with tests skipped at
execution time, then runs Maven Dependency Plugin analysis sequentially. Do not
replace `-DskipTests` with `-Dmaven.test.skip=true`: the latter removes test
compilation and changes the analyzed universe.

Dependency findings remain advisory because bytecode analysis cannot reliably
model starters, SPI/ServiceLoader discovery, runtime implementations and shared
test aggregates. The opt-in `dependency-analysis` Maven profile provides the
same analysis inside full `verify`; neither form is part of ordinary CI, and no
broad ignore registry is maintained.

OWASP Dependency-Check is a separate security control. Network-backed database
updates are explicit, while the ordinary local scan uses existing local data.
Its lifecycle and suppressions belong to the security workflow, not to
SpotBugs or CPD code-quality acceptance.

## Adding or changing a Maven module

1. Add/change the root reactor module and preserve dependency direction.
2. Give it explicit coverage, SpotBugs, CPD and PMD dispositions. Differences
   require a documented reason; no registry may silently omit a project.
3. Add the matching report-ordering dependencies and exact CPD/PMD production
   source roots. Review the coverage local-report requirement, aggregate group,
   measured ratchet and fixed-floor entry, including explicit `none` where applicable.
4. For a SpotBugs-excluded child, explicitly set inherited analysis `skip=true`.
   Review added/removed/reclassified suites and the lifecycle source registry.
5. Run focused tests, then full `make verify` and `make pmd-analysis`; inspect
   report membership, findings and counter/baseline deltas together.
6. Use `make ci`/`make pre-push` for the complete regular local gate. Both
   `verify.fresh=true` and `pmd.fresh=true` are needed for fresh local evidence.
7. Update affected capability docs, module map and local README when
   responsibility or production/test/build-only classification changes.

Never satisfy a new-module failure by weakening registry equality or report
checks. The required disposition is the control that prevents silent analyzer
scope loss.

## Extending the quality system

A new analyzer starts as a measured report, not as an immediate blocking gate.
Define the desired signal, applicable source/bytecode universe, tool-health
failure policy, reproducible machine/human reports, ownership, cost and
signal/noise disposition first. Before adoption, provide:

- one authoritative scope registry or another fail-closed derivation;
- stale-output cleanup and missing-report detection;
- a deterministic aggregate when cross-module evidence matters;
- fixture tests for scope drift and negative failure paths;
- narrow, reviewable acceptance semantics if a baseline is necessary;
- one ordinary Maven lifecycle path shared by local builds and CI.

Do not reuse SpotBugs acceptance as a generic suppression framework. Each tool
has different identity, noise and failure semantics.

## Capacity evidence boundary

Native operational smoke/load commands remain opt-in developer tools and do not
replace deterministic reactor gates or provisioned external transport tests.
The retained implementation, historical measurements and their limits are in
[native capacity](native-capacity.md). Closed experiment drivers and temporary
release bundles are not part of the supported quality lifecycle.

## Sources of truth

- Reactor, plugin versions, phases and inheritance: root
  [`pom.xml`](../../pom.xml).
- Scope and report topology: SpotBugs, CPD and PMD manifests plus their build-support
  POMs and co-located READMEs; CPD count policy lives in its POM and PMD non-zero
  policy counts live in `pmd-advisory-counts.tsv`.
- Exact SpotBugs identity and acceptance schema:
  [`SpotBugsBaselineVerifier.java`](../../build-support/build-quality/SpotBugsBaselineVerifier.java)
  and its black-box fixture matrix.
- Shared reactor/source/report integrity:
  [`BuildQualityVerifier.java`](../../build-support/build-quality/BuildQualityVerifier.java)
  and its synthetic-reactor matrix.
- Coverage policy/report verification:
  [`CoverageVerifier.java`](../../build-support/coverage-report/CoverageVerifier.java),
  [`CoverageVerifierTest.java`](../../build-support/coverage-report/CoverageVerifierTest.java)
  and the three coverage registries linked above.
- Test discovery and report-union verification:
  [`TestLifecycleVerifier.java`](../../build-support/test-quality/TestLifecycleVerifier.java)
  and [`test-lifecycle.properties`](../../build-support/test-quality/test-lifecycle.properties).
- Stable commands and developer-safe proposal procedure:
  [`Makefile`](../../Makefile) and [`tools/README.md`](../../tools/README.md).
- CI execution and retained artifacts:
  [`tools/ci/build.sh`](../../tools/ci/build.sh) and
  [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).
- Architecture and security policy: [BOUNDARIES.md](../BOUNDARIES.md) and
  [SECURITY-ENGINEERING.md](../SECURITY-ENGINEERING.md).

## When to update this document

Update this document in the same change when any of these semantics change:

- canonical local/CI Maven lifecycle or report-module ordering;
- analyzer scope classification or new-module admission;
- raw versus filtered enforcement ownership;
- accepted-finding identity, review trigger or proposal safety contract;
- analyzer/report failure policy or blocking versus diagnostic disposition;
- PMD/CPD count-snapshot format or update procedure;
- aggregate/report paths exposed to developers or CI;
- coverage universe, threshold policy or dependency-analysis adoption.

Do not update it for a tool patch version, finding count, report duration or
individual accepted finding unless the mechanism itself changes.

## Related documents

- [BOUNDARIES.md](../BOUNDARIES.md) — project-wide executable boundary rules.
- [MODULARIZATION.md](../MODULARIZATION.md) — reactor and build-support module map.
- [SECURITY-ENGINEERING.md](../SECURITY-ENGINEERING.md) — security controls and
  the boundary between generic code quality and SAST.
- [`build-support/spotbugs-report/README.md`](../../build-support/spotbugs-report/README.md)
  — exact report-module reference.
- [`build-support/cpd-report/README.md`](../../build-support/cpd-report/README.md)
  — CPD report-module reference.
- [`build-support/pmd-report/README.md`](../../build-support/pmd-report/README.md)
  — adopted PMD source-policy and watchlist report-module reference.

## Reusable library coordinates and publication admission

Report verifiers admit `io.github.xorex-lc` for the six artifacts in the
[library catalog](../guides/libraries/README.md): concurrency, diagnostics API,
SLF4J logging, configuration inspection, events core and its Spring adapter.
Other reactor JARs retain `com.iocextractor`. Negative fixtures reject foreign
coordinates and the retired group for admitted libraries.

Extracting or publishing a library does not remove its runtime bytecode from
coverage or static-analysis scope. All four registries, report dependencies,
source roots and local-report requirements must remain consistent. The TCK's
explicit test-support classification is a different reason for exclusion.

Publication admission is a separate contract: parent-independent consumer POM,
component-specific dependency closure, main/sources/Javadoc/license bytes,
signatures, immutable bundles and recovery. The
[publication tooling](../../tools/ci/library-publication.py) owns exact artifact/
dependency rules; the [publication guide](../guides/library-publication.md) owns
usage and the [release policy](../RELEASE-PROCESS.md#reusable-library-versions)
owns versioning/admission.

The CI component matrix qualifies concurrency, diagnostics, logging and
configuration. A separate events job qualifies core before the Spring adapter;
the `library-consumer` aggregate status succeeds only if both jobs succeed.
Failed, cancelled or skipped consumer jobs cannot produce a successful aggregate.
Whether that status is required is external branch/ruleset state.

Standalone consumers use isolated settings/caches outside the checkout, verify
manifest bytes and selected origin, and exercise actual guide examples.
Application-owned Boot recipes remain bootstrap integration tests, with Boot
outside the shared logging JAR. Tool fixtures do not add reactor suites or
production groups. Local file-repository success is not proof of public
publication or a second deployed service.
