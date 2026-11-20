# com.iocextractor.domain

## Responsibility and structure

The IOC bounded context owns framework-free values and semantic rules. This
package is a namespace; its six capability packages contain the production types.

| Package | Responsibility | Allowed domain dependencies |
|---|---|---|
| [model](model/README.md) | IOC vocabulary, identity and provenance | None |
| [refang](refang/README.md) | Ordered literal text rewriting | None |
| [extract](extract/README.md) | Engine-neutral detection and overlap decisions | `model` |
| [feature](feature/README.md) | Normalization, address parsing, features and derivation | `model` |
| [classify](classify/README.md) | Declarative mask-policy evaluation | `feature`, `model` |
| [attribute](attribute/README.md) | Source-section attribution | `extract`, `model` |

[DomainBoundaryTest](../../../../../test/java/com/iocextractor/domain/DomainBoundaryTest.java)
enforces these acyclic edges. Domain packages must not import application,
processing, platform ETL/diagnostics, adapters or bootstrap. Default pure service
implementations remain here; regex/PSL libraries and workspace IO remain outside.

## Integration reading order

Start with `model`, then the capability being changed. `PatternEngine` and
`HostClassifier` are outward implementation seams. `TextRewrite` and
`ExtractionClaims` let callers provide invocation-owned storage without giving the
domain file or database authority. `MatchCursor` and `MarkerCursor` expose one
current match/marker at a time; callers keep their backing text alive while scanning.

Bootstrap supplies configuration and composition. The [application stages](../../../../../../../ioc-application/src/main/java/com/iocextractor/application/pipeline/stage/README.md)
connect these services; [processing](../../../../../../../../docs/dev/processing.md) owns the end-to-end
flow. The [module README](../../../../../../README.md) owns dependencies and commands.
