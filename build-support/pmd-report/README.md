# build-support/pmd-report

## Purpose

Non-production Maven report module for the adopted BUILD-PMD-06 PMD
production-source policy and its deferred watchlist. It owns separate
repository-wide XML/HTML reports and their fail-closed scope, ruleset and
integrity contracts.

**Layer rule:** dependencies exist only for reactor ordering and PMD type
resolution. The analyzed universe is an explicit list of checked-in production
`src/main/java` roots. This module contains no runtime code and is not published.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | `pmd-analysis` and `pmd-watchlist` profile selection, PMD `7.26.0` plugin realm, explicit source universe and report-integrity wiring |
| `pmd-ruleset.xml` | Exact 22-rule adopted policy with calibrated complexity thresholds |
| `pmd-watchlist-ruleset.xml` | Exact three-rule ownership/size watchlist, excluded from the regular CI policy |
| `pmd-scope.tsv` | Fail-closed disposition for all Maven reactor projects |
| `../build-quality/BuildQualityVerifier.java` | Shared JDK-only scope, ruleset and report verifier |
| `target/pmd/pmd.xml` | Generated machine-readable aggregate findings |
| `target/pmd/pmd.html` | Generated human-readable aggregate report |
| `target/pmd-watchlist/pmd.xml` | Generated machine-readable watchlist findings |
| `target/pmd-watchlist/pmd.html` | Generated human-readable watchlist report |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production, test-support or other build-support module.

## Operation

Run the adopted report-only policy from the repository root:

```bash
make pmd-analysis
```

Run the deferred watchlist explicitly when reviewing its ownership assumptions
or PMD/JDK changes:

```bash
make pmd-watchlist
```

Ordinary `make verify` includes this build-only POM in reactor topology but does
not activate PMD source analysis. The regular CI workflow invokes
`tools/ci/pmd.sh policy` in a separate job. Whether that job is required for a
merge is external branch-protection/ruleset state, not a property of this POM or
workflow file, and must be checked separately. Both developer targets
select this module and its upstream reactor with `-pl build-support/pmd-report
-am`; that keeps the PMD aggregator away from the independent coverage,
SpotBugs and CPD aggregate mojos in a parallel full-reactor build. The
`pmd-analysis` profile runs exactly one `aggregate-pmd-no-fork` execution after
its 19 ordering dependencies, removes only the selected output directory first
and fails on analyzer/ruleset/report errors. Findings remain advisory and do not
fail either invocation.

The adopted policy contains 22 exact rule references. `CognitiveComplexity`
uses `reportLevel=16`; `ExcessiveParameterList` uses PMD's `minimum=13`
property. The watchlist contains exactly `PreserveStackTrace`, `CloseResource`
and `NcssCount`. Do not move a rule between these sets, change a threshold or
add a suppression merely to quiet a report: repeat semantic triage and update
the build-quality decision evidence.

`core/ioc-application-tck`, tests, generated/vendor sources, the root parent and
all build-only POMs are outside analysis scope. Root validation rejects PMD
source suppression markers and protects the stale-output cleanup plus late
report-verifier invocation. The shared synthetic-reactor matrix protects these
constraints, the scope, ruleset and report contract from silent weakening.

The cross-module lifecycle, watchlist review triggers and triage policy are documented in
[`docs/dev/build-quality.md`](../../docs/dev/build-quality.md).
