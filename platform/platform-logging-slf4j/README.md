# platform-logging-slf4j

Typed SLF4J records, restoring MDC scopes and a small scalar ECS profile for
services that maintain their own field/action catalogs. Requires Java 21.
The IOC service now consumes this module through local catalog facades.
Independent publication remains a separate qualification step.

**Layer rule:** main code depends only on JDK and SLF4J API. IOC catalogs,
ETL/diagnostic integrations, application code and backend wiring stay outside
this module. Domain and application do not depend on this logging facade.

## Structure

| Package / type | Responsibility |
|---|---|
| `com.iocextractor.platform.logging.LogFieldSpec`, `LogValueType` | Immutable field declarations and three scalar wire representations |
| `logging.LogEvent`, `LogEvents` | Typed emission and action/outcome/nanosecond conveniences through SLF4J |
| `logging.MdcScope` | Restore touched keys on the same thread, in nested LIFO scopes |
| `logging.ecs` | Five standard descriptors and ECS outcome vocabulary |
| `logging.redaction.LogRedaction` | Explicit constant masking and narrow URL-component transformation |
| `src/test` | Public API, provider failure/async behavior, dependency boundary and executed guide examples |

## Dependencies

The only required main dependency is `org.slf4j:slf4j-api` (qualified at 2.0.18).
The application supplies its provider. Logback 1.5.38, ArchUnit, Jupiter and AssertJ
are test dependencies. Main code has no IOC catalog, ETL, diagnostics, Spring,
Logback, JSON encoder or automatic context-propagation dependency.

Coordinates: `io.github.xorex-lc:ioc-platform-logging-slf4j`, using the parent
`${revision}` in product lockstep. Artifact metadata follows the existing platform
libraries; this module's presence does not admit a publication workflow.

## Use and limits

Start with the [consumer guide](../../docs/guides/logging-library.md); its three
complete Java examples are compiled and executed by the module tests. Public
Javadoc describes null handling, exceptions, ownership and scalar conversions.

Field identity is its key; null omission retains the declared type. Wrong types
throw even at disabled levels. Null does not hide ambient MDC. Scopes do not
propagate across workers; reconstruct context explicitly. Masks belong to STRING
log fields and do not scrub a whole event or Throwable.

The shared ECS subset contains `event.action`, `event.outcome`, `event.duration`,
`error.type` and `file.path`. Generic fields validate representation only.
The application checks reserved/prefix paths and owns its backend metadata,
dataset and Throwable error object. Type/category arrays are outside this API.
IOC catalog facades and the ETL observer remain in
[`platform-observability`](../platform-observability/README.md) and delegate the
mechanics to this library. Bootstrap retains the backend and configuration.

Run `make test-module MODULE=platform/platform-logging-slf4j`. The
`library-publication` Maven profile can build source/Javadoc attachments for local
review; actual publication and cold-repository qualification remain separate.
