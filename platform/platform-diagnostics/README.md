# platform/platform-diagnostics

## Purpose and boundary

IOC-specific diagnostic codes, catalog metadata, rendering and the product
exception boundary. The reactor artifact is `com.iocextractor:ioc-platform-diagnostics`,
an internal product module rather than the published reusable diagnostics API.
It contains no Spring, logging backend, persistence, transport or use-case logic.

Generic diagnostic values, code/severity/impact contracts, results, policies,
collectors and sinks belong to [platform-diagnostics-api](../platform-diagnostics-api/README.md).
This module adds product ownership and message metadata without forcing those
concepts into generic consumer codes. Rendering is text conversion; emission,
redaction policy and control-flow decisions remain outside.

## Structure and reading order

| Source / directory | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Ordinary internal JAR depending inward on the shared API and platform errors |
| [IOC metadata](src/main/java/com/iocextractor/diagnostics/README.md) | Product code contract, category/context vocabulary and DiagnosticException |
| [codes](src/main/java/com/iocextractor/diagnostics/codes/README.md) | Subsystem-owned enums with stable IDs, severity/impact and fallback messages |
| [catalog](src/main/java/com/iocextractor/diagnostics/catalog/README.md) | Explicit aggregate and generated-reference entries |
| [render](src/main/java/com/iocextractor/diagnostics/render/README.md) | Renderer and context-formatting seam |
| [Tests](src/test/java/com/iocextractor/diagnostics/README.md) | Catalog registration/generation, exception and rendering contracts |

There are no module-local resources. Fallback templates live in code; there is no
message-bundle/i18n loader. Exact operation guarantees belong to source/Javadoc;
[observability](../../docs/dev/observability.md) owns cross-module delivery/disclosure.

## Dependencies and consumers

Direct production dependencies are `io.github.xorex-lc:ioc-platform-diagnostics-api`
and `com.iocextractor:ioc-platform-errors`; both have JDK-only production closure.
The root manages Java 21 and dependency versions, with inherited JUnit/AssertJ in
test scope. This module has no dependency on application, adapters, bootstrap or
`platform-diagnostics-logging`. The composed runtime architecture suite guards
framework/adapter/logging coupling; the module POM adds no local Enforcer policy.

Application/adapters import owned code enums to produce shared `Diagnostic` values;
`DiagnosticException` preserves IOC's unchecked exception boundary. Bootstrap wires
`TemplateDiagnosticRenderer` with the formatter from the
[diagnostic logging bridge](../platform-diagnostics-logging/README.md).
That outer bridge implements emission/log fields; it may inspect `IocDiagnosticCode`
for category. Generic codes remain valid inputs and render as literal IDs.

## Catalog maintenance and safety

The [diagnostic catalog](../../docs/DIAGNOSTICS-CATALOG.md) is generated from explicit
`DiagnosticCatalogs` registration through the documentation contract test. Do not
edit that file manually or create a competing translated catalog. Add/change code
metadata at its owner, register new enums and regenerate only when needed; the
catalog reference explains the validation/update path.

Default rendering exposes supplied context values. `DiagnosticException` messages
also include raw context and preserve the diagnostic cause, without calling a
renderer. A masking formatter changes rendered text, not the diagnostic or exception
object. Producers own safe context/messages/causes across each output channel;
see [disclosure policy](../../docs/dev/observability.md#disclosure-and-redaction).
Severity/impact metadata does not independently authorize skip, commit or retry.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=platform/platform-diagnostics
```

Local Surefire suites check catalog metadata/completeness and generated Markdown,
exception identity/cause and rendering. `make verify` additionally checks consumers,
architecture and logging integration. A catalog entry or production reference is
not evidence that every condition emits correctly at runtime. The
[diagnostics consumer guide](../../docs/guides/libraries/diagnostics.md) describes the
separate reusable API; [test policy](../../docs/TESTING.md) and
[module map](../../docs/MODULARIZATION.md) own project-wide rules.
