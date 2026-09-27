# adapter-processing-camel

Internal Camel integration family for bounded preparation plans. The `contract`
package contains neutral immutable descriptors, `compile` validates references
and generates local operation/destination routes, and `runtime` owns the embedded
Camel context. Execution selection is planned for R2. The IOC/application bridge
is introduced with its first real consumer; no unused port is declared in R1.

Only this adapter may depend on Camel. Operator input never supplies endpoint
addresses or scripts. The outer application pipeline owns diagnostics, checkpoint
and persistence. This module is not independently published.

`camel-direct` supplies the local runtime endpoint, `camel-core-languages`
supplies Camel's `simple` language required during context startup, and
`jspecify` completes Camel's annotation types for bytecode analysis. These
runtime/analysis dependencies have no source imports in this module. The
source-level Camel API and route model dependencies are declared directly.

See [routing capability](../../docs/dev/processing.md),
[module map](../../docs/MODULARIZATION.md) and
[ADR 0031](../../docs/ADR/0031-bounded-camel-preparation-runtime.md).
