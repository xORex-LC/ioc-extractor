# com.iocextractor.processing

## Responsibility and structure

Framework-free IOC preparation between the domain and application. This namespace
contains five capability packages; production classes live in those packages.

| Package | Responsibility | Local collaborators |
|---|---|---|
| [model](model/README.md) | Classified IOC value | Domain model and classification values |
| [classification](classification/README.md) | Network/file classification facade | Domain match policy and feature values |
| [parse](parse/README.md) | Exact structured-cell parsing | Domain extractor and network-address parser |
| [session](session/README.md) | Bounded semantic reuse | Classification facade and domain host derivation |
| [mapping](mapping/README.md) | Declarative scalar field evaluator and filters | Classified model, domain types/bare-IP rule and platform errors |

`domain` does not depend on this module. Application and outer composition consume
it; this module imports neither. [ProcessingBoundaryTest](../../../../../test/java/com/iocextractor/processing/ProcessingBoundaryTest.java)
guards the outward boundary, not a separate per-package DAG.

## Integration seams

Bootstrap supplies configured policy, provider/transform/condition registries and
admitted bindings. The CSV preparer consumes `RowMapper`; it turns located mapping
rejections into diagnostics. Document/import handles own semantic-session lifetime.
Route plans, named-view selection, derived-view execution and required-plan dispatch
remain outside this package; the shared evaluator consumes their resolved values.

Follow the [module README](../../../../../../README.md) for build/dependency rules
and [processing](../../../../../../../../docs/dev/processing.md) for the full preparation/checkpoint flow.
