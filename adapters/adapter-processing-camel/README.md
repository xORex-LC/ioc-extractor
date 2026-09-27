# adapter-processing-camel

Internal Camel integration family for bounded preparation plans. The `contract`
package contains neutral immutable descriptors, `compile` validates references
and binds ordered predicates to a selector while generating local view, branch
and dispatch routes. `runtime` owns the embedded Camel context and selects an
admitted plan by ID. It computes prerequisite views on demand once per call,
then dispatches selected branches through a sequential Camel Recipient List.
The IOC/application bridge awaits its first real consumer.

Only this adapter may depend on Camel. Operator input never supplies endpoint
addresses or scripts. The compiled selector owns tri-state FIRST/ALL/EXCLUSIVE
eligibility and no-match decisions; Camel owns operation and destination
execution. Expected unavailable prerequisites return a failure reference;
unexpected exceptions abort the call. The outer application pipeline owns
diagnostics, checkpoint and persistence. This module is not independently
published.

`camel-direct` supplies the local runtime endpoint, `camel-core-languages`
supplies Camel's `simple` language required during context startup, and
`jspecify` completes Camel's annotation types for bytecode analysis. These
runtime/analysis dependencies have no source imports in this module. The
source-level Camel API and route model dependencies are declared directly.

See [routing capability](../../docs/dev/processing.md),
[module map](../../docs/MODULARIZATION.md) and
[ADR 0031](../../docs/ADR/0031-bounded-camel-preparation-runtime.md).
