# IOC observability tests

JUnit Jupiter/AssertJ tests use a real test-scoped Logback provider to check IOC
catalog bindings, facade events and MDC. No Spring context, database, network or
credentials are required. The
[module reference](../../../../../../README.md) owns dependencies;
[base](../../../../../main/java/com/iocextractor/observability/README.md)
and [logging references](../../../../../main/java/com/iocextractor/observability/logging/README.md)
map local responsibilities.

## Test map

| Source | Observable behavior |
|---|---|
| [LoggingTaxonomyTest.java](LoggingTaxonomyTest.java) | Unique action values/field keys, action syntax/areas, field namespaces/descriptions, shared ECS bindings and enum-only facade field entry point |
| [LoggingCatalogDocumentationTest.java](LoggingCatalogDocumentationTest.java) | Normalized comparison of generated action/field Markdown with the committed catalog; explicit opt-in regeneration |
| [MdcScopeTest.java](MdcScopeTest.java) | Nested/first-value restoration, null/hide, untouched writes, write-after-close, STRING-only fields, callback initialization and original/suppressed failure identity |
| [SensitiveLogValueSanitizerTest.java](SensitiveLogValueSanitizerTest.java) | Delegated URL user-info/query handling, fragment preservation, partial/plain input and null |
| [logging/IocLogFieldBindingTest.java](logging/IocLogFieldBindingTest.java) | IOC descriptor conversion to native scalar values, null omission, supported text and rejected representations |
| [logging/LogEventTest.java](logging/LogEventTest.java) | Catalog wire helpers, levels/logger category, ambient versus local fields, native values, cause forwarding, copy-returning builders and async Logback snapshots |

Six suites use Surefire naming; there are no local Failsafe suites or resource
fixtures. Provider/callback cases use a shared MDC resource lock. The initialization
failure test temporarily replaces SLF4J's private MDC adapter through reflection
and restores it in `finally`; this is test-only, version-sensitive fault injection.
The async case has a containing `@Timeout`, timed latch/waits, appender shutdown
and worker termination assertion. It witnesses this Logback handoff, not every
provider or the synchronous Boot recipe's use with an async encoder.

## Running and catalog maintenance

From the repository root:

```bash
make test-module MODULE=platform/platform-observability
make test-one MODULE=platform/platform-observability TEST=LoggingCatalogDocumentationTest
make verify
```

Normal tests compare the generated
[LOGGING-CATALOG.md](../../../../../../../../docs/LOGGING-CATALOG.md) without modifying it. Set
`ioc.docs.update=true` only for intentional regeneration after catalog changes;
use the [base package procedure](../../../../../main/java/com/iocextractor/observability/README.md#generated-catalog-and-extension-checks)
and inspect the generated diff. Catalog descriptions and successful generation
do not prove disclosure policy or final encoder output.

## Cross-module evidence

- [Shared logging tests](../../../../../../../platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/README.md)
  own generic type identity, normalization, provider faults, complete helper
  semantics and actual consumer-guide compilation.
- [PipelineDiagnosticMdcTest](../../../../../../../platform-diagnostics-logging/src/test/java/com/iocextractor/observability/diagnostics/PipelineDiagnosticMdcTest.java)
  checks runner/observer run and stage scopes, terminal summary placement,
  concurrent correlation isolation and restoration through a capturing sink.
- [CatalogReferenceRatchetTest](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/CatalogReferenceRatchetTest.java)
  checks production bytecode references to EventAction; a switch-map reference is
  not proof of a log call, and the test is not LogField producer coverage.
- [ArchitectureTest](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
  owns layer rules; [LoggingMigrationIT](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java)
  and [LogbackConfigurationIT](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LogbackConfigurationIT.java)
  check IOC producer JSON and backend wiring.
- [LoggingBootRecipeIT](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingBootRecipeIT.java)
  owns the separate application-owned recipe's output bytes and unsupported
  inputs; it does not make the recipe encoder part of this module.

Add regressions for changed IOC bindings, initializer failures, producer wire
values and disclosure. Keep generic API mechanics with the owning library rather
than copying their implementation tests. Follow [TESTING.md](../../../../../../../../docs/TESTING.md)
for discovery/coverage policy and [observability](../../../../../../../../docs/dev/observability.md) for
cross-module operation. Logged completion is not a canonical transaction receipt,
and local captured events are not live deployment or collector-ingestion evidence.
