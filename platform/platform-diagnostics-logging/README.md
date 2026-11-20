# ioc-platform-diagnostics-logging

## Purpose and boundary

IOC's bridge from structured diagnostics to operational SLF4J events. It supplies
`LoggingDiagnosticSink` and the context formatter used by the service's diagnostic
renderer. Generic diagnostic values and delivery contracts remain logging-free
in [platform-diagnostics-api](../platform-diagnostics-api/README.md);
IOC metadata and message templates belong to
[platform-diagnostics](../platform-diagnostics/README.md).

This is an internal product JAR under `com.iocextractor`, using the shared reactor
version. It is separate from the published generic diagnostics and SLF4J logging
libraries. It supplies no application entry point, Spring auto-configuration,
logging provider, queue, persistence or retry policy.

## Structure

| Location | Responsibility |
|---|---|
| [Production package](src/main/java/com/iocextractor/observability/diagnostics/README.md) | Sink event mapping, selected-context disclosure policy and MDC boundary |
| [Tests](src/test/java/com/iocextractor/observability/diagnostics/README.md) | Logback event capture, formatter behavior and pipeline correlation |
| [pom.xml](pom.xml) | Bridge dependency family; Logback is test-scoped |

The package reference maps all local types; exact API details live beside them.
Cross-module processing and disclosure rules belong to
[observability](../../docs/dev/observability.md).

## Dependencies and direction

Direct production dependencies are `ioc-platform-diagnostics-api`,
`ioc-platform-diagnostics`, `ioc-platform-observability` and `slf4j-api`, with
versions managed by the parent. Observability brings the generic
`ioc-platform-logging-slf4j` implementation and ETL integration; IOC diagnostics
brings the product error hierarchy. Neither the bridge nor its production
closure selects a logging backend. Logback classic/core, JUnit and AssertJ
support local tests.

Keep service business rules, adapters and bootstrap wiring outside this module.
Bootstrap may depend on the bridge to compose it. The
[architecture test](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
keeps diagnostics core logging-free and observability packages independent of
domain/adapters/bootstrap; the generic diagnostics API has a separate JDK-only
rule. These rules do not imply an exhaustive module allowlist check.

## Entry points and service wiring

`LoggingDiagnosticSink(Logger, DiagnosticRenderer)` implements the shared
synchronous `DiagnosticSink` port. It maps severity and adds typed diagnostic
fields through the IOC observability facade. It neither applies `FailurePolicy`
nor deduplicates occurrences, enforces budgets or changes processing outcomes.
The supplied renderer controls message content; the sink does not install
redaction itself.

[AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
composes `TemplateDiagnosticRenderer(new RedactingDiagnosticContextFormatter())`,
then wraps the logging sink in the shared `ResilientDiagnosticSink`. The wrapper
reports runtime delivery failures through a service-owned fallback. Direct sink
use does not contain renderer/provider failures; JVM Errors remain outside the
wrapper's runtime-failure containment. The caller owns latency and resources.

The formatter fingerprints registered raw context values at INFO and above;
DEBUG/TRACE use the shared lexical URL-component sanitizer. This is a rendered
context boundary, not complete log scrubbing. Original diagnostics and causes
are retained unchanged; producers own safe content in messages, context and
exception chains. See the
[package reference](src/main/java/com/iocextractor/observability/diagnostics/README.md)
for the exact scope and limitations.

## Verification and related docs

From the repository root:

```bash
make test-module MODULE=platform/platform-diagnostics-logging
make docs
make verify
```

The [test map](src/test/java/com/iocextractor/observability/diagnostics/README.md)
separates captured event/formatter checks from pipeline MDC behavior and final
backend encoding. Bootstrap's
[LoggingMigrationIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java)
qualifies the Boot ECS integration outside this module. The generated
[logging catalog](../../docs/LOGGING-CATALOG.md) owns field/action vocabulary;
[observability](../../docs/dev/observability.md#disclosure-and-redaction) owns disclosure
policy. Update affected references and tests with mapping or formatter changes;
do not hand-edit generated catalogs.
