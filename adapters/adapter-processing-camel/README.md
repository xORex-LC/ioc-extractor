# adapter-processing-camel

Internal Camel integration family for bounded preparation plans. The `contract`
package contains neutral immutable descriptors, `compile` validates references
and binds ordered predicates to a selector while generating local view and
branch routes. `runtime` owns the embedded Camel context and selects an
admitted plan by ID. It computes prerequisite and selected-branch mapping views
on demand once per call, then sends eligible branches sequentially through the
existing bound ProducerTemplate. Complete selection and required-view resolution
precede every destination call. Each branch retains its native consumer and
UnitOfWork, must return a typed reply, and sees a fresh exchange with the same
immutable declared input. Ordered replies accumulate only within this invocation
and become immutable on return. There is no extra dispatch route, Recipient List
exchange, aggregation wrapper or batch-wide UnitOfWork.
Fixed endpoints are bound after context startup and owned
by that runtime; the template receives endpoint references.
Parameterized predicates bind validated arguments once per condition leaf at
compilation. Operation and destination processors are also captured during
compilation; condition trace status labels are immutable constants, so disabled
tracing performs no status-name formatting. The bound predicates must be reusable across concurrent calls;
invocation-specific values never enter a shared argument cache.
`RoutingExecutionScopes` and `RoutingTraceSink` are optional neutral hooks:
bootstrap supplies MDC scopes and maps value-free decisions with the pinned
policy fingerprint to the existing application tracer. A registered plan
activates an isolated context; without a
registration, bootstrap creates no Camel runtime. `CamelRouteRuntime` admits
concurrent callers with invocation-local views and replies, exposes readiness,
rejects new calls during close and waits for active calls for a bounded period
before stopping Camel. Selected document and processed-import policies now share
one admitted context; the branch route sets plan and branch IDs before a
destination resolves its IOC-specific bindings. Malformed dynamic rule IDs and reason
codes are replaced in trace evidence without changing the returned failure
reference. The generated branch route sets the branch ID before calling its
destination so a caller may bind distinct field views for branches sharing a
destination; the reply retains the same branch ID. An unavailable destination
may carry opaque local evidence to its caller, without teaching this technical
module IOC diagnostics.

Only this adapter may depend on Camel. Operator input never supplies endpoint
addresses or scripts. The compiled selector owns tri-state FIRST/ALL/EXCLUSIVE
eligibility and no-match decisions; Camel owns ordinary operation and destination
execution. An explicit `view.recover` edge uses one earlier alternate view and a
registry-checked reason allowlist. The invocation-local result reports which
branches actually demanded each failure, whether recovery succeeded, and any
selected branch blocked during preparation. Destination replies are typed as
prepared, filtered or expected failure. Unexpected exceptions abort the call.
The outer application pipeline owns diagnostic severity, checkpoint and
persistence. This module is not independently published.

`camel-direct` supplies the local runtime endpoint, `camel-core-languages`
supplies Camel's `simple` language required during context startup, and
`jspecify` completes Camel's annotation types for bytecode analysis. These
runtime/analysis dependencies have no source imports in this module. The
Camel API, route model and runtime processor dependencies are declared directly;
`camel-support` also supplies the DSL builder
superclasses required at compile time and exchange fixtures in tests. Versions remain managed by the existing parent `camel.version` property.

Functional compiler/runtime conformance tests cover branch ordering, immutable
replies, distinct exchanges, native UnitOfWork completion, recovery, stopped
routes, readiness and bounded shutdown. Production Spring document/import
fixtures connect this runtime to IOC preparation and Native canonical storage.
Historical cost results and their limits are in
[native capacity](../../docs/dev/native-capacity.md); they do not establish a
portable throughput target.

See [routing capability](../../docs/dev/processing.md),
[module map](../../docs/MODULARIZATION.md) and
[ADR 0031](../../docs/ADR/0031-bounded-camel-preparation-runtime.md).
The execution simplification is decided by
[ADR 0040](../../docs/ADR/0040-native-sequential-preparation-dispatch.md).

The execution conformance suite checks native view UnitOfWork success/failure
completion, stopped-route refusal and isolation of mutable exchange headers and
properties between views and recipients. These guarantees also constrain future
changes to execution granularity.
