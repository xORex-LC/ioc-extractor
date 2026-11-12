# Camel preparation-plan tests

## Purpose and structure

Technical conformance for the [module](../../../../../../../../README.md). Five Surefire
`*Test` suites cover compiler, real embedded Camel execution and architecture.
There are no local Failsafe suites or test-resource directories. Models,
processors, predicates and expected results are synthetic inline fixtures.

| Suite | Observable contracts |
|---|---|
| [CamelPlanCompilerTest.java](CamelPlanCompilerTest.java) | Local registered routes, admission limits/IDs/cycles/references, argument snapshots and unexpected operation failure |
| [CamelRoutingExecutionTest.java](CamelRoutingExecutionTest.java) | Once-bound predicates, tri-state mode/condition ordering, default policies, per-call view reuse, typed replies, native UnitOfWork and exchange isolation including concurrent maximum fanout |
| [CamelRecoveryExecutionTest.java](CamelRecoveryExecutionTest.java) | Lazy allowlisted recovery, primary/alternate evidence, absence-aware predicates, actually reached consumers and mapping-only blocked branches |
| [CamelRuntimeLifecycleTest.java](CamelRuntimeLifecycleTest.java) | Startup/endpoint-binding cleanup, context ownership, readiness, concurrent callers, graceful/bounded/interrupted close and observational tracing |
| [NeutralPackagesArchitectureTest.java](NeutralPackagesArchitectureTest.java) | ArchUnit architecture-tagged ban on IOC domain/application/bootstrap/bridge and Spring dependencies in the three technical packages |

## Evidence boundaries

The four functional suites start actual local Camel contexts under containing
`@Timeout` limits and close them. Concurrent lifecycle/fanout fixtures use timed
latches/futures and release/join owned workers. Stopped-route tests exercise real
consumer refusal. View/branch synchronizations assert native UnitOfWork success
and failure completion; headers/properties are checked for cross-request leaks.
These checks do not demonstrate deep payload immutability, interruption of
arbitrary callbacks or cross-process fencing.

Typed expected failure, filtered output and unexpected exceptions are distinct
outcomes. Tests check that selection/preparation finishes before dispatch,
unreached work has no side effects/evidence, and failed destinations do not
silently reselect another branch. They do not establish a durable transaction,
canonical write, CSV schema or remote delivery; local processors are fixtures.

Bootstrap's [IocRouterConfigurationIT](../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IocRouterConfigurationIT.java) and
[RouterRuntimeConfigurationIT](../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/RouterRuntimeConfigurationIT.java)
check IOC registration/wiring, while
[RouterSelectedImportDeliveryIT](../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/RouterSelectedImportDeliveryIT.java)
checks selected processed-import composition. They are separate owning-module
evidence; they are not executed by this module's Surefire command alone.
[Native capacity](../../../../../../../../../../docs/dev/native-capacity.md) records historical cost
measurements and limitations; this suite is not a throughput benchmark.

## Verification

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-processing-camel
```

Full reactor acceptance uses `make verify`, including bootstrap suites and
repository-wide gates. [TESTING.md](../../../../../../../../../../docs/TESTING.md) owns discovery,
tags, meaningful assertions and worker cleanup requirements. The production
[contract](../../../../../../../main/java/com/iocextractor/adapter/processing/camel/contract/README.md), [compile](../../../../../../../main/java/com/iocextractor/adapter/processing/camel/compile/README.md) and
[runtime](../../../../../../../main/java/com/iocextractor/adapter/processing/camel/runtime/README.md) READMEs map the code behind these contracts.
