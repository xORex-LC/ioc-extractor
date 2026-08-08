# build-support/coverage-report

## Purpose

Non-production Maven report module that creates the authoritative reactor-wide
JaCoCo HTML/XML report after all production dependencies have completed.

**Layer rule:** dependencies exist only to define the coverage universe and
reactor ordering. This module contains no runtime code and is not a published
library.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | Explicit production-module universe and JaCoCo `report-aggregate` execution |
| `target/site/jacoco-aggregate/index.html` | Generated human-readable aggregate report |
| `target/site/jacoco-aggregate/jacoco.xml` | Generated machine-readable aggregate report |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production or test-support module.

## Notes

`core/ioc-application-tck` is intentionally outside the production denominator.
The module defines no JaCoCo threshold or exclusion. SpotBugs aggregation and
report-integrity validation belong to the sibling `spotbugs-report` module.
The complete quality lifecycle is documented in
[`docs/dev/build-quality.md`](../../docs/dev/build-quality.md).
