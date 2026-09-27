# P2 shared mapping admission

Status: P2 implementation in progress. This first P2 slice relocates existing
pure IOC preparation without changing the active document or import route.

`core/ioc-processing` now owns the single `ClassifiedIndicator`,
`IndicatorClassifier`, exact-cell composition, `ConfigurableRowMapper`, column
specification, conditions, providers, transforms and expected mapping-failure
types. `ioc-application` consumes it inward; `adapter-csv` retains artifact
selection, ID placeholder provider, write-plan adaptation, CSV codecs and
projection. The class move preserves existing registry keys and mapper behavior.
The value of `id` is still deferred outside processing; no canonical ID or
row key is computed by the new module.

The module compiles with only domain and platform-errors dependencies. Maven
Enforcer and ArchUnit exclude application, adapters, Spring, Camel, CSV, JDBC
and transport dependencies. The reactor and each coverage, SpotBugs, CPD and
PMD scope include the module; its mapping tests were moved with the code, so
the local JaCoCo report remains meaningful. The lowercase-host transform now
preserves case-sensitive fragment text as well as path/query text.

The remaining P2 work is typed operator plan binding, validation of named
views/classifications/field overrides and a compiled IOC-to-Router descriptor.
No new plan is accepted or activated by this relocation alone. P3/P4 retain
document/import attachment; P5 owns pinned activation and policy identity.
