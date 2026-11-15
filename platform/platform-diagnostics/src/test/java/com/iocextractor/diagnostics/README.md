# IOC diagnostics tests

## Responsibility and suites

Four Surefire suites validate IOC metadata, exception and rendering behavior over
the shared API. There are no local Failsafe suites, resource fixtures or external
infrastructure dependencies. The generated catalog is a repository-owned input/
output of its dedicated test, rather than a module resource.

| Source | Contract covered |
|---|---|
| [DiagnosticCatalogTest](DiagnosticCatalogTest.java) | Exact persisted import-ID lookup, shared severity vocabulary, stable unique metadata and compiled-enum registration completeness |
| [DiagnosticCatalogDocumentationTest](DiagnosticCatalogDocumentationTest.java) | Generated Markdown versus committed catalog, optional explicit regeneration |
| [DiagnosticExceptionTest](DiagnosticExceptionTest.java) | Null rejection, product exception boundary, retained diagnostic/cause and contextual message |
| [render/TemplateDiagnosticRendererTest](render/TemplateDiagnosticRendererTest.java) | Template values, missing placeholders, nonrecursive replacement and generic literal-ID fallback |

## Fixture ownership and evidence limits

Catalog completeness scans file-backed compiled classes in the codes package.
It is not runtime JAR/plugin discovery. The documentation test locates the root
by walking to a `pom.xml`/`docs` pair, compares normalized generated Markdown and
writes only when `ioc.docs.update=true`. Ordinary validation must leave the
committed generated catalog unchanged. Its
[production catalog reference](../../../../../main/java/com/iocextractor/diagnostics/catalog/README.md)
owns the explicit regeneration command.

Renderer tests use synthetic context and a fixed clock; the exception tests use
synthetic diagnostic/cause values. They do not qualify sensitive-value masking,
serialized logs, every template's runtime producer or all historical persisted IDs.
The default formatter deliberately exposes values; the outer diagnostic-logging
module owns masking/integration tests. Bootstrap's
[CatalogReferenceRatchetTest](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/CatalogReferenceRatchetTest.java)
checks production references, while actual emission requires producer/backend tests.

## Running and related docs

Run from the repository root:

```bash
make test-module MODULE=platform/platform-diagnostics
make test-one MODULE=platform/platform-diagnostics TEST=TemplateDiagnosticRendererTest
```

`make verify` supplies the full deterministic reactor and downstream architecture/
logging checks. Tests for generic results, policies, collectors and sinks belong
to the separate diagnostics API module. Apply [TESTING](../../../../../../../../docs/TESTING.md) for
assertions/report ownership and see the [module README](../../../../../../README.md)
for dependency boundaries.
