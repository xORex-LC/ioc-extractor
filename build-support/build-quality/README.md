# build-support/build-quality

## Purpose

Repository-owned, JDK-only verification of build-quality analyzer scope and
report integrity. The verifier is neutral infrastructure shared by SpotBugs,
PMD CPD and PMD source analysis; it contains no production code and is not a
Maven reactor project.

## Structure

| File | Purpose |
|---|---|
| `BuildQualityVerifier.java` | Fail-closed reactor/manifest reconciliation and control-specific report validation |
| `BuildQualityVerifierTest.java` | Synthetic-reactor contract harness for happy paths and negative mutations |
| `SpotBugsBaselineVerifier.java` | Exact accepted-finding validation, raw/report reconciliation and proposal-only delta generation |
| `SpotBugsBaselineVerifierTest.java` | Synthetic baseline, analyzer-health and aggregate-integrity contract harness |
| `SpotBugsReportFilter.java` | Applies the generated narrow filter to raw SpotBugs XML without a second bytecode-analysis pass |

The root POM compiles the JDK-only sources with AntRun and runs both contract
harnesses once in `validate` with `inherited=false`. It then validates the real
SpotBugs, CPD and PMD manifests plus the SpotBugs accepted baseline before Maven
starts child projects. Analyzer report modules run the applicable verifier
later in `verify`, after their reports exist.

## Contract tests

Run the root-only fail-fast gate with:

```bash
./mvnw -B -ntp -N validate
```

The fixture harnesses never change the checkout. They create temporary
synthetic reactors and currently verify:

- `BuildQualityVerifier`: 7 happy paths and 48 negative scenarios covering
  reactor/manifest drift, packaging and skip policy, report-module ordering,
  SpotBugs/CPD/PMD scope, exact PMD rulesets/properties, suppressions, stale
  output cleanup and late XML/HTML integrity;
- `SpotBugsBaselineVerifier`: 4 happy paths and 24 negative scenarios covering
  accepted-entry schema, review-trigger quality, selector precision, analyzer
  health, exact raw identity comparison, filtered visibility, module coverage
  and aggregate-union integrity.

Each negative case must return a non-zero status and a stable, specific
diagnostic. Add a regression scenario whenever verifier behavior is tightened
or a fail-open path is found.

## Ownership

Control-specific manifests and report configuration remain with
`build-support/spotbugs-report`, `build-support/cpd-report` and
`build-support/pmd-report`. Common reactor parsing, dispositions and set
reconciliation belong here; do not copy them back into individual report
modules.
