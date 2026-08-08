# Build quality

Build quality is a repository-owned Maven capability, not an external service.
The canonical `verify` lifecycle compiles and tests the reactor, enforces module
and architecture rules, analyzes production code, builds aggregate reports and
checks that every expected result is current and complete. Controls have
different policies: some findings block immediately, some remain diagnostic,
and analyzer or report-integrity failures never become a green build silently.

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
       |    reconcile reactor modules with SpotBugs/CPD scope manifests
       |    validate the accepted SpotBugs baseline and generate its filter
       |
       +-- initialize
       |    remove stale module and aggregate analyzer outputs
       |
       +-- ordinary reactor lifecycle
       |    compile -> test -> package
       |    Maven Enforcer + ArchUnit + documentation contracts
       |    module JaCoCo and raw SpotBugs reports
       |    filtered SpotBugs presentation reports
       |
       +-- final build-support modules
            JaCoCo aggregate
            raw and filtered SpotBugs aggregates + exact baseline gate
            repository-wide PMD CPD report + source/report integrity gate

CI build completion
  -> upload SpotBugs and CPD evidence even when the build failed late
```

Maven may report that aggregate mojos require exclusive reactor access during
the parallel build. That message describes Maven serialization of aggregate
work, not an analyzer error. Correct ordering comes from the build-support POMs:
their dependencies define the production universe and ensure that aggregation
runs only after the applicable modules.

## Control model

| Control | Input | Enforcement |
|---|---|---|
| Maven Enforcer | Toolchain and POM metadata | Violations block every ordinary build |
| JUnit/Surefire contracts | Unit, integration, architecture, golden and documentation tests | Test failure blocks `verify` |
| ArchUnit | Compiled production classes | Dependency or package-boundary violation blocks `verify` |
| JaCoCo | Test execution data and production classes | Report generation is part of `verify`; coverage values are currently diagnostic |
| SpotBugs | Applicable production bytecode | New, stale, moved or metadata-drifted findings block the exact ratchet; analyzer/report failures also block |
| PMD CPD | Applicable checked-in production Java sources | Findings are diagnostic; analyzer, scope or report failure blocks |
| Maven dependency analysis | Main and test bytecode | Opt-in advisory report; not part of ordinary `verify` |
| Dependency-Check | Resolved dependency graph and local vulnerability data | Separate security workflow, deliberately outside ordinary `verify` |

The distinction between a finding policy and tool health is important. CPD
duplicates do not fail the build, but a missing CPD report does. SpotBugs is
stricter: both an unhealthy analysis and any raw finding not represented by the
reviewed exact baseline fail the build.

## Ownership boundaries

| Surface | Owner |
|---|---|
| Versions, inherited plugin executions and root `validate` wiring | Root [`pom.xml`](../../pom.xml) |
| Stable developer commands | Root [`Makefile`](../../Makefile) |
| CI Maven invocation and verification fingerprint | [`tools/ci/build.sh`](../../tools/ci/build.sh) |
| CI jobs and report retention | [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) |
| Shared scope/report verifier and contract matrices | [`build-support/build-quality/`](../../build-support/build-quality/) |
| SpotBugs scope, accepted baseline, aggregation and late gate | [`build-support/spotbugs-report/`](../../build-support/spotbugs-report/) |
| CPD scope, source universe, aggregation and late gate | [`build-support/cpd-report/`](../../build-support/cpd-report/) |
| JaCoCo aggregate universe | [`build-support/coverage-report/`](../../build-support/coverage-report/) |

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
  configure `spotbugs.skip=true`;
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
3. reconciles the reactor and both analyzer manifests;
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
calibration before change. A future no-new-duplication ratchet needs a separate
baseline and enforcement decision.

## Coverage

The inherited JaCoCo agent records test execution for functional modules.
Module reports are written under `target/site/jacoco/`; the final build-only
coverage module combines the applicable production universe under
`build-support/coverage-report/target/site/jacoco-aggregate/`.

The reusable TCK is not part of the production coverage denominator. The
current lifecycle generates HTML/XML evidence but does not enforce a numeric
coverage floor. Introducing a floor or ratchet requires an explicit universe,
missing-report policy and measured baseline rather than a copied percentage.

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

1. Add or change the module in the root reactor and preserve dependency
   direction.
2. Give the project an explicit disposition in both SpotBugs and CPD manifests;
   the two dispositions may differ only for a documented reason.
3. For an analyzed scope, add the matching ordering dependency and, for CPD,
   the exact production source root to the report module.
4. For a SpotBugs-excluded child project, configure inherited analysis with an
   explicit `skip=true`.
5. Run full `make verify`; confirm the expected raw/filtered module reports and
   aggregate membership.
6. Update the module map and relevant co-located README when the module's role
   or production/test/build-only classification changes.

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

## Sources of truth

- Reactor, plugin versions, phases and inheritance: root
  [`pom.xml`](../../pom.xml).
- Scope and report topology: SpotBugs and CPD manifests plus their build-support
  POMs and co-located READMEs.
- Exact SpotBugs identity and acceptance schema:
  [`SpotBugsBaselineVerifier.java`](../../build-support/build-quality/SpotBugsBaselineVerifier.java)
  and its black-box fixture matrix.
- Shared reactor/source/report integrity:
  [`BuildQualityVerifier.java`](../../build-support/build-quality/BuildQualityVerifier.java)
  and its synthetic-reactor matrix.
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
