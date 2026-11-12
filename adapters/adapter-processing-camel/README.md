# adapters/adapter-processing-camel

## Purpose and boundary

Internal embedded Camel runtime for bounded local preparation plans. The reactor
artifact is `com.iocextractor:ioc-adapter-processing-camel`; it is not a runnable
service or an independently published library.

The module validates technical descriptors, compiles registered predicates and
local routes, resolves demanded views and dispatches selected branches. It owns
the embedded context, producer and endpoint bindings. IOC schemas, diagnostics,
classification, mapping policy, canonical writes and failure-policy checkpoints
belong to the caller. Operator configuration never supplies Camel endpoint URIs,
scripts or arbitrary expression-language programs.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Camel integration family and test architecture dependency |
| [contract](src/main/java/com/iocextractor/adapter/processing/camel/contract/README.md) | Neutral descriptors, typed outcomes, selection/failure evidence and optional observation hooks |
| [compile](src/main/java/com/iocextractor/adapter/processing/camel/compile/README.md) | Admission limits/references, once-bound predicates and generated local view/branch routes |
| [runtime](src/main/java/com/iocextractor/adapter/processing/camel/runtime/README.md) | Isolated context lifecycle, per-invocation views/recovery, sequential dispatch and readiness |
| [tests](src/test/java/com/iocextractor/adapter/processing/camel/README.md) | Real embedded Camel execution, admission, recovery, lifecycle and dependency boundaries |

All three production packages share one test package. Fixtures are synthetic
in-memory models/processors; there is no module-local resource tree.

## Dependencies

Production dependencies are Camel API, route model, core engine/processor and
support, the direct component, core languages and JSpecify. The parent manages
their versions; Java 21 is the project baseline. ArchUnit is test-scoped and
JUnit/AssertJ come from the shared parent test setup.

`camel-direct` provides local endpoints; `camel-core-languages` supplies the
`simple` language needed at context startup. JSpecify supplies Camel annotation
types for bytecode analysis. These dependencies are needed even without direct
source imports. `camel-support` supplies DSL builder superclasses and test
exchange fixtures. Preserve these roles during dependency cleanup.

There are no application/domain/platform, Spring, CSV, database or remote
transport dependencies here. Neutral does not mean every package is Camel-free:
`contract` carries no Camel types, while `compile` deliberately accepts Camel
processors and emits RouteBuilders. The local architecture test prevents IOC
application/domain/bootstrap and Spring leakage across these packages.

## Entry points and contracts

Compile trusted `PlanDescriptor` values against an `OperationCatalog` using
`CamelPlanCompiler`, then construct/close `CamelRouteRuntime`. Each `execute`
selects one admitted plan, resolves reached eligibility and selected required
views before any destination call, and dispatches eligible branches sequentially.
View/reply state is per invocation. Branches have separate native Exchange and
UnitOfWork instances; there is no batch-wide transaction or dispatch/Recipient
List/aggregation wrapper route. Expected failures are typed; unexpected faults
abort the call without rolling back effects of earlier processors.

Collection snapshots do not deep-copy arbitrary values. Callers supply immutable
or safely shared payloads and thread-safe reusable processors, predicates, scopes
and observers. Separate exchange headers/properties do not isolate mutation of
shared body objects. Runtime close rejects new calls and bounds active-call drain
before stopping Camel; it cannot guarantee termination of arbitrary caller code.

Current IOC bootstrap admits required document and processed-import policies
into one shared context through
[IocRouterConfiguration](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocRouterConfiguration.java) and
[RouterRuntimeConfiguration](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/RouterRuntimeConfiguration.java).
This is the required processing path, not an optional backend activated by an
ad-hoc registration. Bootstrap owns Spring/MDC/tracer integration, policy
fingerprints, readiness exposure and shutdown registration.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-processing-camel
```

All five local suites run in Surefire, including real embedded Camel tests;
there are no local Failsafe suites. Full reactor acceptance uses `make verify`.
The test README distinguishes technical conformance from IOC composition and
capacity qualification.

See [processing internals](../../docs/dev/processing.md),
[processing routes guide](../../docs/guides/ioc-processing-routes.md),
[ADR 0031](../../docs/ADR/0031-bounded-camel-preparation-runtime.md) and
[ADR 0040](../../docs/ADR/0040-native-sequential-preparation-dispatch.md).
Historical measurements and their limits live in
[native capacity](../../docs/dev/native-capacity.md); they are not portable
throughput or whole-process memory guarantees. Exact member guarantees remain
in Java Javadoc; package READMEs provide the local reading map.
