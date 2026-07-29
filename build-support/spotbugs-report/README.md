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

Module HTML is rendered from native XML using the SpotBugs engine's
`default.xsl` resource to avoid a second bytecode-analysis pass. Every
`spotbugs.version` update must therefore verify that both module XML and HTML
are still generated; replace this AntRun bridge if a future plugin version can
produce both complete formats in one native execution.
