# core/ioc-domain

## Purpose and boundary

Pure Java IOC vocabulary and rules: refang text, detect occurrences, attribute
section provenance, derive network features and evaluate mask classification.
The reactor artifact is `com.iocextractor:ioc-domain`, an internal functional JAR.
It is neither a runnable service nor an admitted standalone library.

The domain depends only on the JDK in production. It does not open documents or
databases, load configuration, log decisions or own ETL execution. Application
orchestration and adapters supply text storage, regex matching, PSL classification
and validated policy. Pure default services such as `ReplacementRefanger`,
`RegexIndicatorExtractor` and `MarkerSourceAttributor` live inside this module.

## Structure and reading order

| Directory | Responsibility |
|---|---|
| [Domain package](src/main/java/com/iocextractor/domain/README.md) | Capability map and allowed dependency edges |
| [Model](src/main/java/com/iocextractor/domain/model/README.md) | IOC types, identity, provenance and mask-code values |
| [Refang](src/main/java/com/iocextractor/domain/refang/README.md) | Ordered literal rewrites and applied-rule facts |
| [Extraction](src/main/java/com/iocextractor/domain/extract/README.md) | Regex port, match priority, overlap decisions and offsets |
| [Attribution](src/main/java/com/iocextractor/domain/attribute/README.md) | Section-marker selection and nearest preceding provenance |
| [Features](src/main/java/com/iocextractor/domain/feature/README.md) | Normalization, supported address forms and derived views |
| [Classification](src/main/java/com/iocextractor/domain/classify/README.md) | First-match rules over structural features |
| [Tests](src/test/java/com/iocextractor/domain/README.md) | Local contracts, deterministic doubles and evidence boundaries |

There are no module-local resource trees. Bootstrap assembles services and rules;
[processing](../../docs/dev/processing.md) explains their cross-module use. Exact type
and operation contracts belong to the linked Java sources and Javadoc.

## Dependencies and guards

The compile dependency tree contains no external or sibling-module dependencies.
The parent owns Java 21 and dependency versions; inherited JUnit/AssertJ and the
module-local ArchUnit dependency are test-only. Module Enforcer rules reject the
configured framework/IO integration families. The local `DomainBoundaryTest`
guards the capability DAG; the composed runtime's architecture suite additionally
guards imports and inward module dependencies.

`PatternEngine` is implemented by [regex adapters](../../adapters/adapter-regex-re2j/README.md);
`HostClassifier` by the [PSL adapter](../../adapters/adapter-psl/README.md). These links
identify collaborators, not Maven dependencies pointing outward.

## Execution contracts

Materialized string APIs return immutable outcome lists. The document path uses
`TextRewrite`, `MatchCursor`, `ExtractionClaims` and `MarkerCursor` against an
externally owned text/workspace. Match and marker positions are absolute UTF-16
indices into the same refanged text, not original-document byte offsets. Refang
can change lengths; extraction and attribution must share the resulting text.

Lazy matching avoids copying the full input, but resource bounds, overlap storage,
accepted-occurrence sorting and cleanup belong to the caller/workspace. A streaming
port alone does not establish a whole-service memory or capacity guarantee.
Outcome facts carry no logging or diagnostic-sink dependency.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=core/ioc-domain
```

All local suites use Surefire. Actual regex/PSL integrations and composed runtime
behavior are checked downstream; `make verify` is the deterministic reactor gate.
The POM's `mutation-pilot` profile is diagnostic and is not a regular score gate.
See [architecture](../../docs/ARCHITECTURE.md), [module map](../../docs/MODULARIZATION.md)
and [test policy](../../docs/TESTING.md) for their respective project-wide rules.
