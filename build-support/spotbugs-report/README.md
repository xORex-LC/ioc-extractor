# build-support/spotbugs-report

## Purpose

Non-production Maven report module that creates the authoritative reactor-wide
SpotBugs XML/HTML report and validates report completeness after all production
dependencies have completed.

**Layer rule:** dependencies exist only to define the analyzed production
universe and reactor ordering. This module contains no runtime code and is not a
published library.

## Structure

| File/output | Purpose |
|---|---|
| `pom.xml` | Production dependency ordering, `spotbugs-aggregate` and late report-integrity wiring |
| `spotbugs-scope.tsv` | Single disposition registry for every root and child reactor project |
| `spotbugs-baseline-exclude.xml` | Reviewed C3 baseline shared by every inherited module analysis |
| `../build-quality/BuildQualityVerifier.java` | Shared JDK-only scope and report-integrity verifier |
| `target/spotbugs/spotbugs.xml` | Generated machine-readable reactor aggregate |
| `target/spotbugs/spotbugs.html` | Generated human-readable reactor aggregate |

## Dependencies

**Depends on:** all 19 production modules in `platform`, `core`, `adapters` and
`bootstrap`.

**Not imported by:** any production, test-support or coverage-report module.

## Notes

`core/ioc-application-tck`, the root parent, `coverage-report` and `cpd-report`
are explicitly excluded from production-bytecode analysis. `spotbugs-report`
has the separate `aggregate` disposition. The root lifecycle removes stale
module-local SpotBugs outputs during `initialize`, so an incremental build cannot
satisfy the integrity gate with a report from a prior invocation.

The root-only `validate` gate runs the shared verifier before child projects are
built. It requires the registry to cover exactly the root Maven reactor,
checks each path against its POM coordinates and packaging, requires explicit
`skip=true` for every excluded child project, and requires the report module's
dependencies to equal the `analyzed` artifact set. Adding any reactor project
therefore fails closed until it receives an explicit disposition and, when
analyzed, an ordering dependency. A synthetic-reactor contract harness in
`../build-quality` protects these rules against accidental weakening. The final
`verify` step derives all expected XML/HTML paths from the same registry,
validates their structure and rejects reports from excluded scopes.

Findings remain report-only. Analyzer errors, missing module reports or missing
aggregate outputs fail the reactor build.

The current remediation baseline contains 77 reviewed findings represented by 71
narrow selectors. Every selector combines a bug pattern with an exact class and
method or field; package-, category- and pattern-wide exclusions are forbidden.
Of the accepted findings, 59 are analyzer false positives and 18 are policy
noise where the detector's generic unchecked-
exception advice is inapplicable to a documented boundary contract. The
inherited root execution applies this one filter to every module analysis. The
aggregate mojo then merges those module XML reports, so module and reactor-wide
views cannot acquire separate baseline copies.

Each filter comment links a selector back to its finding ID in the release
worknote. Removing or changing code must remove the now-unused selector in the
same change. Any new finding remains visible and is handled by the report-only
adoption policy until `BUILD-SPOTBUGS-05` introduces the enforcement ratchet.

Module HTML is rendered from native XML using the SpotBugs engine's
`default.xsl` resource to avoid a second bytecode-analysis pass. Every
`spotbugs.version` update must therefore verify that both module XML and HTML
are still generated; replace this AntRun bridge if a future plugin version can
produce both complete formats in one native execution.
