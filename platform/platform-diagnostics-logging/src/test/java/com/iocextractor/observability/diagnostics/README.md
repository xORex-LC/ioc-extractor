# Diagnostic logging bridge tests

Local JUnit/AssertJ tests use a real Logback provider for captured SLF4J events
and MDC. They need no Spring context, network, database, credentials or external
service. The [module reference](../../../../../../../README.md)
owns dependencies and the
[production package](../../../../../../main/java/com/iocextractor/observability/diagnostics/README.md)
owns event/formatter behavior.

## Test map

| Source | Observable contract |
|---|---|
| [LoggingDiagnosticSinkTest.java](LoggingDiagnosticSinkTest.java) | All severity-to-level mappings, typed action/outcome/code/category/severity, supplied renderer text, cause forwarding, generic category omission/restoration and selected-context redaction |
| [RedactingDiagnosticContextFormatterTest.java](RedactingDiagnosticContextFormatterTest.java) | Every severity, URL user-info/query handling for a registered value and its embedded copy, unchanged original context, fingerprints based on original rather than sanitized values |
| [PipelineDiagnosticMdcTest.java](PipelineDiagnosticMdcTest.java) | Diagnostic delivery inside stage run/source/stage correlation; terminal suppression summary inside run but outside stage; concurrent run isolation and restoration of pre-existing MDC |

Three suites use Surefire naming; there are no local Failsafe suites or resource
fixtures. Logback is test-scoped. Sink tests capture event-local key/value pairs,
message and Throwable separately from MDC. Their appender prepares deferred event
state and is detached after each test. The pipeline correlation suite uses a
substitute capturing sink, so it qualifies runner/observer scope rather than
complete `LoggingDiagnosticSink` output. Its concurrent case uses a timed barrier,
bounded future waits, a containing `@Timeout` and asserted worker termination.

## Running and extending

From the repository root:

```bash
make test-module MODULE=platform/platform-diagnostics-logging
make test-one MODULE=platform/platform-diagnostics-logging TEST=LoggingDiagnosticSinkTest
make verify
```

Keep checks focused on observable fields, messages, causes and restored context.
Use synthetic values and explicit renderer composition for disclosure scenarios.
A redacted rendered message is not proof that Throwable/free text/MDC is safe;
the formatter only transforms its selected context substitutions. These tests
also do not prove durable delivery, arbitrary secret discovery or every provider's
failure/concurrency behavior.

Captured Logback records are not final ECS JSON. Bootstrap's
[LoggingMigrationIT](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java)
owns Boot encoder integration. Shared resilient-wrapper failures are tested in
[diagnostics API tests](../../../../../../../../platform-diagnostics-api/src/test/java/com/iocextractor/platform/diagnostics/README.md);
this bridge does not implement that containment. Follow
[TESTING.md](../../../../../../../../../docs/TESTING.md) for discovery and test-quality policy, and
[observability](../../../../../../../../../docs/dev/observability.md) for cross-module contracts. Update
owning references when a verified mapping or disclosure contract changes.
