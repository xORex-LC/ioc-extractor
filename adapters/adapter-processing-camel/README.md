# adapter-processing-camel

Internal Camel integration family for bounded preparation plans. The `contract`
package contains neutral immutable descriptors, `compile` validates references
and binds ordered predicates to a selector while generating local view, branch
and dispatch routes. `runtime` owns the embedded Camel context and selects an
admitted plan by ID. It computes prerequisite and selected-branch mapping views
on demand once per call, then dispatches eligible branches through a sequential
Camel Recipient List.
`RoutingExecutionScopes` and `RoutingTraceSink` are optional neutral hooks:
bootstrap supplies MDC scopes and maps value-free decisions with the pinned
policy fingerprint to the existing application tracer. A registered plan
activates an isolated context; without a
registration, bootstrap creates no Camel runtime. `CamelRouteRuntime` admits
concurrent callers with invocation-local views and replies, exposes readiness,
rejects new calls during close and waits for active calls for a bounded period
before stopping Camel. The IOC operation bindings and document/import callers
still await their first real consumer. Malformed dynamic rule IDs and reason
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
source-level Camel API and route model dependencies are declared directly.

`RouterQualificationTest` runs the 1,000-input synthetic correctness matrix in
Surefire. `make router-qualification SIZE=100000` runs the opt-in 100,000-input
profile in fresh JVMs for 1/4/16 selected branches, 1/4 callers and
success/failure/recovery mixtures. The script records per-profile startup,
thread allocations, retained-heap samples and throughput under a fixed heap.
This profile does not assert an IOC end-to-end throughput target; the real
document/import bindings and their previous preparation paths are still needed
for a valid before/after comparison.

See [routing capability](../../docs/dev/processing.md),
[module map](../../docs/MODULARIZATION.md) and
[ADR 0031](../../docs/ADR/0031-bounded-camel-preparation-runtime.md).
