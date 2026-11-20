# SLF4J logging library tests

JUnit Jupiter/AssertJ checks use a real test-scoped Logback provider, ArchUnit and
synthetic service-owned field descriptors. No Spring application, network,
database or credentials are required. The
[module reference](../../../../../../../README.md) owns dependencies;
[production references](../../../../../../main/java/com/iocextractor/platform/logging/README.md)
map API types and package boundaries.

## Test and helper map

| Source | Observable contract |
|---|---|
| [LogFieldSpecTest.java](LogFieldSpecTest.java) | Exact dotted-key grammar, nulls, descriptor value equality and deeply segmented validation without call-stack exhaustion |
| [LogEventTest.java](LogEventTest.java) | Scalar normalization/snapshots, declaration identity/null omission, rejection without state loss, all levels, disabled validation, MDC collisions, copy-returning provider builders, propagated failures and async provider snapshots |
| [MdcScopeTest.java](MdcScopeTest.java) | Nested first-value restoration, reverse first-touch order, untouched writes, hide/null, successful close/write-after-close, body/cleanup failures and explicit worker context |
| [EcsProfileTest.java](EcsProfileTest.java) | Exactly five descriptors, wire outcome, nanoseconds and helper bounds, omitted values and generic-field bypass of ECS semantics |
| [LogRedactionTest.java](LogRedactionTest.java) | Explicit masks at every level, STRING-only masks and lexical URL transformation boundaries |
| [LoggingArchitectureTest.java](LoggingArchitectureTest.java) | JDK/SLF4J-only production dependencies and exact eight-type exported surface |
| [LoggingLibraryExampleTest.java](LoggingLibraryExampleTest.java) | Extract, compile and execute the three actual plain-guide Java classes outside the library package; assert native fields, cause forwarding, omission, masks and restored MDC |
| [LogCapture.java](LogCapture.java) | Test-owned descriptors/logger, deferred-state preparation, field extraction and appender/context cleanup |
| [MdcAdapterSwap.java](MdcAdapterSwap.java) | Test-only reflective SLF4J MDC adapter replacement with restoration |

Seven suites use Surefire naming; there are no local Failsafe suites or resource
fixtures. Cases that use the global MDC adapter share a resource lock. Fault
injection depends on the tested SLF4J implementation's private field and must be
reviewed on provider/API upgrades; it is not a production library operation.

Async/worker cases use timed coordination and bounded waits, containing
`@Timeout`, `finally` cleanup and termination assertions. The Logback AsyncAppender
case witnesses one provider's native scalar/MDC handoff. It does not establish
thread safety for a shared mutable builder/scope or qualify every provider.

## Running and evidence ownership

From the repository root:

```bash
make test-module MODULE=platform/platform-logging-slf4j
make test-one MODULE=platform/platform-logging-slf4j TEST=LogEventTest
make verify
```

The guide test invokes the JDK compiler on real sources, then checks captured
Logback events. This is in-checkout API/provider evidence, not a fresh-cache
packaged consumer or proof of persisted JSON. Use the
[plain guide](../../../../../../../../../docs/guides/libraries/logging-slf4j.md) and its
[Russian counterpart](../../../../../../../../../docs/guides/ru/libraries/logging-slf4j.md) as the maintained
example sources; publication tooling separately checks language parity.

Bootstrap's [LoggingBootRecipeIT](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingBootRecipeIT.java)
compiles the actual Boot recipe, runs it in another JVM without IOC runtime
classes, parses output bytes and checks six unsupported scenarios. Its supported
encoder path is synchronous mutable Logback events. The async library test does
not extend that path. [LoggingMigrationIT](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java)
and [LogbackConfigurationIT](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LogbackConfigurationIT.java)
own IOC producer JSON and backend wiring; IOC catalogs/ETL observer tests belong
to [platform-observability](../../../../../../../../platform-observability/README.md).

The [plain packaged consumer](../../../../../../../../../tools/fixtures/logging-consumer/README.md) and
[Boot packaged consumer](../../../../../../../../../tools/fixtures/logging-boot-consumer/README.md) separately
exercise guide sources with resolved JARs, empty caches, hashes/origins and
missing/unavailable-SLF4J negatives. Follow the
[publication procedure](../../../../../../../../../docs/guides/library-publication.md#local-packaging-qualification)
for complete steps. Neither local library tests nor original repository records
qualify another provider, deployment or collector ingestion.

Add regressions for changed supported field, failure, cleanup and disclosure
outcomes. Keep checks independent of private event storage; distinguish event
construction, provider delivery, appender/encoder acceptance and actual bytes.
Follow [TESTING.md](../../../../../../../../../docs/TESTING.md) for lifecycle/coverage rules. Update public
Javadoc, owning references, paired guides and relevant consumer regressions with
a contract change; preserve historical release evidence.
