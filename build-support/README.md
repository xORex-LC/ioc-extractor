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
| `coverage-report/` | Reactor-wide JaCoCo HTML/XML report aggregation |
| `spotbugs-report/` | Reactor-wide SpotBugs XML/HTML aggregation and report-integrity validation |
| `cpd-report/` | Repository-wide production-source PMD CPD XML/HTML report and integrity validation |

## Dependencies

**Depends on:** production reactor modules required by the selected report.

**Not imported by:** platform, core, adapters, bootstrap or packaged runtime
artifacts.
