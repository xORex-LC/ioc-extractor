# build-support/coverage-report

## Purpose

Non-production Maven report module that creates and verifies the authoritative
reactor-wide JaCoCo HTML/XML report after all production dependencies have
completed.

**Layer rule:** dependencies exist only to define the coverage universe and
reactor ordering. This module contains no runtime code and is not a published
library.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | Reactor ordering, aggregate generation, stale-output cleanup and late coverage/test gates |
| `coverage-scope.tsv` | Fail-closed disposition for every one of the 25 reactor projects and local-report expectation |
| `coverage-ratchets.tsv` | Aggregate and 19 production-module line/branch baselines plus absolute missed context |
| `CoverageVerifier.java` | JDK-only universe, report-integrity and no-regression gate |
| `CoverageVerifierTest.java` | Synthetic-reactor happy/negative contract matrix |
| `target/site/jacoco-aggregate/index.html` | Generated human-readable aggregate report |
| `target/site/jacoco-aggregate/jacoco.xml` | Generated machine-readable aggregate report |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production or test-support module.

## Notes

`core/ioc-application-tck` is intentionally outside the production denominator.
No class or package exclusion is accepted. The root `validate` phase reconciles
the complete scope registry, report-module dependencies, report topology and
ratchet scopes before child projects run. The late verifier then requires the
aggregate XML/HTML, its exact 19-group production universe, 17 module-local
XML/HTML pairs and two declared downstream-only groups. Missing, stale or
unexpected execution/report files fail the build.

Line and branch ratios are compared as exact integer fractions, without decimal
rounding. Increased missed-branch counts also fail even when denominator growth
would preserve the ratio. Small modules additionally ratchet absolute missed
instructions; the snapshot preserves the same context diagnostically for larger
modules. These are no-regression baselines, not the separate fixed release
floors still awaiting coverage-gap remediation.

The module also runs the late `TestLifecycleVerifier` check after the production
reactor so missing, duplicate or wrong-engine Surefire/Failsafe XML cannot be
accepted as complete evidence.

SpotBugs aggregation and its own report-integrity validation belong to the
sibling `spotbugs-report` module. The complete test and quality lifecycles are
documented in [`docs/TESTING.md`](../../docs/TESTING.md) and
[`docs/dev/build-quality.md`](../../docs/dev/build-quality.md).
