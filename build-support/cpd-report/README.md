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
| `pom.xml` | Explicit production-source universe, report-only `aggregate-cpd` execution and report-integrity gate |
| `target/cpd/cpd.xml` | Generated machine-readable repository report |
| `target/cpd/cpd.html` | Generated human-readable repository report |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production, test-support, coverage-report or SpotBugs
report module.

## Notes

`core/ioc-application-tck` is reusable test-contract code and is intentionally
outside the production-source scope. The positive source-root list also
excludes Maven-generated sources, build outputs and any non-reactor vendor
trees; explicit generated/vendor path patterns provide a second guard.

`minimumTokens=75` is the accepted 0.3.0 baseline after repository calibration.
Duplicate findings remain report-only. Analyzer errors or missing XML/HTML
outputs fail the reactor build.
