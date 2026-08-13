# build-support

## Purpose

Build-time-only Maven modules that produce verification evidence without
participating in the runtime architecture or publication surface.

**Layer rule:** build-support may depend on production reactor modules solely
to inspect their build outputs. Production modules must never depend on
build-support.

## Structure

| Directory | Purpose |
|---|---|
| `build-quality/` | Shared JDK-only analyzer scope/report verifiers and synthetic contract harnesses; not a reactor module |
| `coverage-report/` | Reactor-wide JaCoCo HTML/XML report aggregation |
| `spotbugs-report/` | Reactor-wide SpotBugs XML/HTML aggregation and report-integrity validation |
| `cpd-report/` | Repository-wide production-source PMD CPD XML/HTML report and integrity validation |
| `pmd-report/` | Adopted PMD source-policy and watchlist XML/HTML reports with fail-closed scope/ruleset integrity |

## Dependencies

**Depends on:** production reactor modules required by the selected report.

**Not imported by:** platform, core, adapters, bootstrap or packaged runtime
artifacts.
