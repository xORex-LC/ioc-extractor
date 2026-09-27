# Router implementation plan

Status: design accepted for implementation; R0 bounded admission and R1 module
and compiler skeleton completed, 2026-09-27. Reviewed source baseline:
`f43037ee87ef`, branch `module/platform/router`. See
[R0 evidence and contracts](r0-admission.md) and
[R1 implementation evidence](r1-implementation.md). R2–R5 are not implemented.
Companion: [IOC processing plan](ioc-processing-implementation-plan.md).

## Scope and authoritative design

Implement technical plan compilation and execution, without IOC parsing,
classification codes, artifact schemas, canonical identity or persistence.
Use [configuration design](configuration-execution-design.md) and
[resolution contract](routing-resolution-and-logging.md) for proposed behavior.
Use [Camel assessment](camel-migration-assessment.md) option B as the qualification
target. [Router internals](router-internals.md) describes alternative A; it is
not a second implementation backlog to execute alongside B.

The existing `platform-etl` runner remains the outer stage/checkpoint owner.
Router replaces the admitted inner operation/branch execution, not intake,
ledgers, retry scheduling or canonical commit. Expected outcomes return to the
caller; business diagnostic disposition and policy stay at the preparation boundary.

## Reassessment findings

- `IocExtractionService` currently constructs deduplication and classification
  before preparation. Router implementation alone cannot satisfy field-specific
  cleanup: the companion plan must change the entry seam.
- `ProcessedImportRowPreparer` currently accepts an already mapped logical row.
  Its admission/finalization change belongs to IOC integration, not to the engine.
- The proposed `adapter-processing-camel` can depend on application ports to
  implement them, but that does not make the whole adapter a domain-independent
  library. Keep neutral compiler/runtime code separate from IOC bridges, with
  package dependency tests. A separate neutral Maven module is justified if these
  dependencies need enforcement at artifact level; decide at R0, not by naming.
- Sharing `platform-etl` diagnostics and observers avoids a second execution
  policy. Do not retain a custom operation interpreter inside one Camel Processor.

## Ordered slices

Each slice includes focused tests, documentation and the applicable quality gate.
Names below identify work, not pre-existing classes. R0 and IOC P0 are one joint
contract review, recorded once; neither workstream implements the other's policy.

| Slice | Work and touched owners | Exit evidence |
|---|---|---|
| R0 — contracts and runtime admission | Agree typed operation input/output, expected failure identity, immutable plan and invocation-local state with P0. Prove exact Camel/Boot/JDK closure from the current POM, startup/shutdown and error propagation in a bounded experiment. Select module/package boundaries and record a new ADR at the next available number. | Dependency tree, minimal runtime tests, no inward framework leaks; explicit disposition of option B. If B fails, revise the design before implementing A; do not ship both. |
| R1 — module and compiler skeleton | Add the admitted adapter/module(s), parent management and complete build-quality wiring. Separate descriptor validation, route generation, runtime and application bridge packages. Define registered operation/destination bindings without importing IOC types into neutral packages. | ArchUnit/dependency tests, module README, healthy module and aggregate reports. Startup rejects cycles, duplicate IDs, unknown bindings and exceeded structural limits. |
| R2 — selection and execution | Compile ordered condition/operation nodes; implement FIRST/ALL/EXCLUSIVE and genuine no-match action. One owner evaluates each predicate; any custom preselector is limited to semantics Camel does not directly express. Generate real operation/branch execution nodes. | Table-driven MATCH/NO_MATCH/BLOCKED cases, NOT propagation, ordered short-circuit counters, zero dispatch on ambiguity/blocked exclusive selection; no second interpreter. |
| R3 — outcomes and recovery | Lazy views computed at most once per invocation; explicit recovery edges and allowlisted reason matching; preserve original failure references and demanded-consumer resolution. Return bounded evidence to the application bridge. | Strict and recovered sibling cases, alternate failure, no catch-all exception recovery; unexpected faults propagate. Router exposes evidence, not IOC diagnostic severity rules. |
| R4 — runtime lifecycle and observability hooks | Bootstrap owns activation/readiness and shutdown. Reuse existing observation/diagnostic contracts; add typed trace hooks through bridges. No payload-based endpoint selection, unrestricted DSL, redelivery or new worker pool. | Concurrent caller isolation, parent MDC restoration, bounded shutdown with timed tests; one diagnostic delivery owner and no redundant Camel exception logging. |
| R5 — qualification and handover | Qualify with synthetic non-IOC operations, then run document/import integration fixtures from P3/P4. Measure startup, allocations, retained state and throughput against the existing preparation path. | Reproducible workload/JDK/config measurements and reviewed resource budget; all gates green, no duplicate runtime in migrated scope. |

R1 establishes the package dependency rule for the application bridge, but the
bridge itself is introduced with the first typed application consumer in P3/P4.
There is no placeholder port or bootstrap activation in the compiler skeleton.

R5 closes only with the real consumers: a synthetic route demo closes neither
customer coverage nor end-to-end recovery. R0 must establish concrete structural
limits and qualification workload sizes; do not silently inherit arbitrary engine
defaults or invent performance guarantees before measurement.

## Interface ownership with IOC processing

| Concern | Router owner | IOC/application owner |
|---|---|---|
| Configuration | Technical structure, dependency graph, execution limits | Operator binding in bootstrap; registry arguments, artifact/schema/authority checks |
| Operations | Invocation protocol and dependency scheduling | Parser, host derivation, classification, field mapping |
| Recovery | Explicit alternate execution and causal evidence | Recoverable business reasons and final WARN/ERROR disposition |
| Output | Selected destinations and typed prepared results through bridge | Canonical key/reduction, import finalization, checkpoint and writes |
| Runtime | Camel route lifecycle and technical failures | Readiness composition, run/delivery correlation and durable recovery |

Framework objects (`Exchange`, endpoint URIs, CamelContext) never cross inward
ports. Document occurrences and structured import rows retain distinct typed entry
contracts; do not replace them with an untyped universal map.

## Build-quality and completion

Apply the [shared module-admission checklist](camel-task-design.md#build-quality-admission-required-in-every-module-adding-slice)
in R1 and every later module addition: reactor/dependency management, ArchUnit,
Surefire/Failsafe discovery and suite inventory, local/aggregate JaCoCo scopes,
floors and ratchets, SpotBugs scopes/report ordering, CPD/PMD source roots and
aggregate dependencies. New production code is analyzed; moving it is not an
exemption. Review verifier fixtures and explicit module inventories together.

For each completed production slice run focused tests followed by `make verify`
and `make pmd-analysis`; R4 resource/exception ownership also requires the applicable
`make pmd-watchlist` review. Inspect raw findings and coverage movement, preserve
existing thresholds, and record exact final-worktree evidence. Test summaries must
distinguish offline execution from external qualification and diagnostic pilots.

Done means technical contracts are documented and tested, module boundaries are
enforced, exact dependency compatibility is proven, real consumer handover passes,
and replaced inner execution is removed. It does not include publishing a reusable
library, migrating sync/ingestion to Camel, or expanding `platform-events`.
