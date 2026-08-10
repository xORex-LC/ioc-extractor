# build-support/pmd-report

## Purpose

Non-production Maven report module for the opt-in BUILD-PMD-06 PMD
production-source evaluation. It owns one repository-wide XML/HTML report and
its fail-closed scope and integrity contract.

**Layer rule:** dependencies exist only for reactor ordering and PMD type
resolution. The analyzed universe is an explicit list of checked-in production
`src/main/java` roots. This module contains no runtime code and is not published.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | Opt-in `pmd-evaluation` profile, PMD `7.26.0` plugin realm, explicit source universe and report-integrity wiring |
| `pmd-ruleset.xml` | Exact 34-rule P1 candidate set; no category-wide references or suppressions |
| `pmd-scope.tsv` | Fail-closed disposition for all Maven reactor projects |
| `../build-quality/BuildQualityVerifier.java` | Shared JDK-only scope, ruleset and report verifier |
| `target/pmd/pmd.xml` | Generated machine-readable aggregate findings |
| `target/pmd/pmd.html` | Generated human-readable aggregate report |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production, test-support or other build-support module.

## Operation

Run the report-only evaluation from the repository root:

```bash
make pmd-analysis
```

Ordinary `make verify` includes this build-only POM in reactor topology but does
not activate the PMD source analysis. The developer target selects this module
and its upstream reactor with `-pl build-support/pmd-report -am`; that keeps the
PMD aggregator away from the independent coverage, SpotBugs and CPD aggregate
mojos in a parallel build. The `pmd-evaluation` profile runs exactly one
`aggregate-pmd-no-fork` analysis after its 19 ordering dependencies, removes
stale output first and fails on analyzer/ruleset/report errors. Findings remain
advisory and do not fail the invocation.

`core/ioc-application-tck`, tests, generated/vendor sources, the root parent and
all build-only POMs are outside analysis scope. Root validation rejects PMD
source suppression markers and protects the stale-output cleanup plus late
report-verifier invocation. The shared synthetic-reactor matrix protects these
constraints, the scope, ruleset and report contract from silent weakening.

The cross-module lifecycle and triage policy are documented in
[`docs/dev/build-quality.md`](../../docs/dev/build-quality.md).
