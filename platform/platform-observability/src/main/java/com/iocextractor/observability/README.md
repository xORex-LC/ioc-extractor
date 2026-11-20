# IOC logging vocabulary and bindings

This package owns IOC field/action metadata and small conveniences over the
shared logging library. It supplies no backend, JSON encoder or business recovery
policy. See the [module reference](../../../../../../README.md)
for dependency rules and [package-info.java](package-info.java) for the boundary.

## Source map

| Source | Responsibility |
|---|---|
| [LogField.java](LogField.java) | Stable wire keys, descriptions and shared scalar descriptors |
| [EventAction.java](EventAction.java) | Stable action values, owning area and catalog description |
| [EventOutcome.java](EventOutcome.java) | IOC enum binding to shared ECS success/failure/unknown values |
| [ObservabilityMode.java](ObservabilityMode.java) | `oneshot`/`daemon` labels; configuration/profile selection belongs to bootstrap |
| [MdcScope.java](MdcScope.java) | IOC-field MDC convenience, including owned callback initialization |
| [SensitiveLogValueSanitizer.java](SensitiveLogValueSanitizer.java) | Delegate selected URL-component transformation to shared `LogRedaction` |
| [logging/](logging/README.md) | IOC typed-event facade and ETL logging observer |

## Vocabulary and producer responsibility

`LogField.spec()` supplies the key/type pair used by the reusable builder;
`key`, `valueType` and `description` expose its catalog metadata. Standard ECS
entries reuse shared descriptors; custom fields belong under `ioc.*`.
`EventAction.value()` is the wire action; `area` and `description` classify the
catalog entry and are not automatically emitted as fields. New actions require
their first production producer. Outcome helpers use lower-case wire values.
The mode-label enum here does not configure logging or select runtime operation;
bootstrap owns its separate
[typed observability selector](../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/ObservabilityMode.java).

Catalog descriptions state meaning/disclosure obligations; they do not implement
policy gates. A TRACE-only item field must be gated by its producer. The
[decision tracer](../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/LoggingPipelineDecisionTracer.java)
combines explicit configuration with logger TRACE. Control-event names use
`ioc.event.type`, and `ioc.operation.disposition` is independent of ECS outcome.
Do not infer an UNKNOWN outcome for every skipped operation. The generated
[catalog](../../../../../../../../docs/LOGGING-CATALOG.md) owns the full inventory.

## MDC callback ownership and sanitization

`MdcScope` delegates restoration to the shared same-thread scope. `put` accepts
only a catalogued STRING field, converting a non-null Object through
`String.valueOf`; null temporarily hides that key. This text conversion differs
from event-local scalar validation. `hide` accepts any catalogued type.

Acquire an empty scope before population inside a resource body. For a callback
that must return an owned AutoCloseable, `open(initializer)` populates the scope
and returns it on success; its caller then closes it. If initialization throws
a RuntimeException or Error, it attempts cleanup, preserves the original failure
and suppresses a distinct cleanup failure. Reusing the same exception does not
cause self-suppression. A failing provider can still prevent full restoration.

Nested scopes close in LIFO order on their owning thread; untouched MDC writes
remain unchanged and workers reconstruct explicit context. These are caller
obligations, not context-propagation or thread-ownership validation. Successful
close is idempotent; writes afterwards fail. Read the
[shared MDC reference](../../../../../../../platform-logging-slf4j/src/main/java/com/iocextractor/platform/logging/README.md)
for underlying mechanics and limitations.

`SensitiveLogValueSanitizer.sanitize` delegates to lexical URL-component masking:
recognized user-info/query content is hidden, null remains null, and paths,
fragments or scheme-less credentials can remain visible. It does not fingerprint
values, scrub Throwable/free messages or install severity-dependent rules.
Those diagnostic rules belong to the
[bridge](../../../../../../../platform-diagnostics-logging/README.md); disclosure across all
channels remains producer-owned.

## Generated catalog and extension checks

[LoggingCatalogDocumentationTest](../../../../../test/java/com/iocextractor/observability/LoggingCatalogDocumentationTest.java)
generates Markdown from enum declaration order, metadata and scalar types. Normal
tests compare the committed catalog after normalizing CRLF and trailing whitespace
at the end of the document.
For an intentional field/action change, regenerate explicitly from the repository
root, then inspect the diff and run normal checks:

```bash
./mvnw -B -ntp -pl platform/platform-observability -am test \
  -Dtest=LoggingCatalogDocumentationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dioc.docs.update=true
```

[LoggingTaxonomyTest](../../../../../test/java/com/iocextractor/observability/LoggingTaxonomyTest.java)
checks unique action values/keys, action naming/areas, field namespaces, metadata
and shared bindings. It does not prove correct producer disclosure or final JSON.
Bootstrap's [CatalogReferenceRatchetTest](../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/CatalogReferenceRatchetTest.java)
checks production bytecode references to actions, not actual logging calls;
compiler-generated enum switch maps can satisfy it. It is not a LogField producer
coverage check. Add meaningful producer/JSON regressions with a vocabulary change;
see the [test reference](../../../../../test/java/com/iocextractor/observability/README.md).
