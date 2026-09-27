# P2 shared mapping admission

Status: P2 implemented in two commits. The first relocates existing pure IOC
preparation; the second admits the typed operator plan without changing the
active document or import route.

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

The operator slice adds typed `ioc.processing` records: named plans, unary
derived views, `configured` classifications, ordered branch routing,
per-column view bindings and recursive AND/OR/NOT conditions. `type-in` accepts
only a nonempty IOC enum list; other registered feature predicates accept no
arguments. `ProcessingPlanCatalog` checks references, branch/condition limits,
explicit enabled-artifact coverage or omission, and compiles IOC bindings into
the existing technical `PlanDescriptor`. The compiled catalog is exposed once
as `ProcessingPlanBindings` to later flow attachments. The shared mapper
evaluates each column against its resolved classified view. No configured plan is registered
with `RouterRuntimeConfiguration` yet. P3/P4 retain document/import attachment;
P5 owns pinned activation and policy identity.

An override of an ungated `const` column is admitted but omitted from demanded
views because it cannot affect the cell. An override of `id` or `source.label`
is rejected because those providers belong to the write/source context. A
`const` column with a gate keeps its bound view because the gate can change the
output. View operation semantics remain unary; unsupported network input has
an expected typed failure rather than implicit fan-out or coercion.
