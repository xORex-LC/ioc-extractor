# core/ioc-processing

## Purpose and boundary

Pure IOC preparation shared by document extraction and processed dataframe import:
classification, exact structured-cell parsing, bounded semantic reuse and declarative
field mapping. The reactor artifact is `com.iocextractor:ioc-processing`, an internal
functional JAR rather than a runnable service or admitted standalone library.

The module composes domain behavior and platform error contracts. It does not
load configuration, execute routes, select canonical identity, allocate IDs, emit
diagnostics or write storage. Bootstrap owns validated registries and composition;
application owns orchestration and preparation lifetimes; adapters own concrete
execution and encoding. There is one shared field evaluator for both processing paths.

## Structure and reading order

| Directory | Responsibility |
|---|---|
| [Processing package](src/main/java/com/iocextractor/processing/README.md) | Local capability and collaborator map |
| [model](src/main/java/com/iocextractor/processing/model/README.md) | IOC paired with one materialized classification |
| [classification](src/main/java/com/iocextractor/processing/classification/README.md) | Network policy delegation and neutral file decisions |
| [parse](src/main/java/com/iocextractor/processing/parse/README.md) | Complete structured-cell match with network-address validation |
| [session](src/main/java/com/iocextractor/processing/session/README.md) | Invocation-owned classification and host reuse |
| [mapping](src/main/java/com/iocextractor/processing/mapping/README.md) | Column gates, providers/transforms, filters and located value rejections |
| [Tests](src/test/java/com/iocextractor/processing/README.md) | Local contracts and downstream evidence boundaries |

There are no module-local resource trees. Exact type/member guarantees live in
Javadoc; [processing](../../docs/dev/processing.md) owns the cross-module flow and
[route guide](../../docs/guides/ioc-processing-routes.md) owns supported configuration.

## Dependencies and guards

Production dependencies point only to `ioc-domain` and `ioc-platform-errors`.
The parent manages versions and Java 21. JUnit/AssertJ are inherited test support;
ArchUnit is a module-local test dependency. There is no application/TCK, adapter,
bootstrap, diagnostics delivery, Spring, Camel, CSV or JDBC production dependency.

Module Enforcer rules reject the configured framework/integration dependency
families transitively. Local `ProcessingBoundaryTest` checks outward/application/
adapter/bootstrap and Spring/Camel/CSV/JDBC dependencies. The composed runtime
architecture suite supplies additional project-wide import/layer rules. These
checks do not constitute a separate Maven artifact for each package.

## Entry points and ownership

`IndicatorClassifier` creates complete decisions; `ClassifiedIndicator` carries them
to mapping. `ExactIndicatorParser` checks a prepared cell, while refang remains a
caller step. `ConfigurableRowMapper` resolves one supplied view for each column,
then evaluates that column's gate, value and ordered transformations. It returns
scalar cells; the CSV adapter supplies wire formatting and prepared-row handling.

`IndicatorProcessingSession` may reuse pure classification and successful host
results within one thread-confined invocation. A document processing handle or
processed-import staging attempt owns and closes it. Single-row entry points use
a local scope; the direct document call disables cache admission. Providers and
transforms still execute for every reached occurrence. The session is neither a
persistent cache nor the authority for processing acceptance or identity.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=core/ioc-processing
```

Local suites use Surefire and synthetic classifiers/extractors/registries. The
[CSV adapter](../../adapters/adapter-csv/README.md), [Camel adapter](../../adapters/adapter-processing-camel/README.md)
and [bootstrap](../../bootstrap/ioc-app/README.md) qualify their own integrations.
`make verify` is the complete deterministic reactor gate. See [test policy](../../docs/TESTING.md)
and [module map](../../docs/MODULARIZATION.md) for project-wide rules.
