# build-support/cpd-report

## Purpose

Non-production Maven report module that creates the authoritative
repository-wide PMD CPD report after all production dependencies have
completed.

**Layer rule:** dependencies exist only to define reactor ordering. The
analyzed source universe is a positive list of checked-in production
`src/main/java` roots. This module contains no runtime code and is not a
published library.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | Explicit production-source universe, report-only `aggregate-cpd` execution and late report-integrity wiring |
| `cpd-scope.tsv` | Fail-closed disposition for every Maven reactor project |
| `../build-quality/BuildQualityVerifier.java` | Shared JDK-only reactor/source-scope and report-integrity verifier |
| `target/cpd/cpd.xml` | Generated machine-readable repository report |
| `target/cpd/cpd.html` | Generated human-readable repository report |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production, test-support, coverage-report or SpotBugs
report module.

## Notes

`core/ioc-application-tck` is reusable test-contract code and is intentionally
outside the production-source scope. A root-only `validate` gate checks the
disposition registry before child projects are built. The registry must match
the root reactor exactly, and its analyzed set must match both this module's
ordering dependencies and configured `src/main/java` roots, so a new reactor
module cannot be omitted silently. The synthetic-reactor contract harness in
`../build-quality` protects this behavior against accidental weakening.

The positive source-root list excludes Maven-generated sources, build outputs
and any non-reactor vendor trees; explicit generated/vendor path patterns
provide a second guard. Before each analysis, `initialize` removes the previous
`target/cpd` directory. After `aggregate-cpd`, the verifier requires non-empty,
structurally valid XML/HTML and reconciles the XML's unique file paths with
every current Java file in the analyzed roots.

`minimumTokens=75` is the accepted 0.3.0 baseline after repository calibration.
Duplicate findings remain report-only. Analyzer errors or missing XML/HTML
outputs fail the reactor build.
