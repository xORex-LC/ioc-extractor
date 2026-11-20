# Scalar ECS profile

This package supplies a small shared vocabulary, using ECS 8.11 field meanings.
It uses the JDK and sibling logging descriptors; it supplies no encoder, provider,
JSON schema validator or service catalog. See the
[module reference](../../../../../../../../README.md) and
[package-info.java](package-info.java).

## Source map and helper semantics

| Source | Responsibility |
|---|---|
| [EcsFields.java](EcsFields.java) | Five shared immutable scalar descriptors |
| [EventOutcome.java](EventOutcome.java) | SUCCESS/FAILURE/UNKNOWN with exact lower-case `value()` |

| Descriptor | Representation | Producer/helper responsibility |
|---|---|---|
| `EVENT_ACTION` | `event.action` / STRING | Service operation name; `action` rejects null/blank without trimming |
| `EVENT_OUTCOME` | `event.outcome` / STRING | `outcome` emits success/failure/unknown; omit when classification does not apply |
| `EVENT_DURATION` | `event.duration` / LONG | Measured nanoseconds; `durationNanos` rejects negatives, with no conversion |
| `ERROR_TYPE` | `error.type` / STRING | Type-only error; with a Throwable let the qualified Boot encoder own `error.*` |
| `FILE_PATH` | `file.path` / STRING | Full path including name; producer owns resolution and disclosure |

Descriptors declare representation only. Generic `field` can accept a negative
LONG duration or arbitrary STRING outcome; helper semantics are additional checks.
Passing the outcome enum through generic STRING conversion uses `toString()`,
while `outcome` deliberately uses its lower-case wire value. An unmeasured duration
may be omitted; a skipped operation is not automatically UNKNOWN. Arrays such as
ECS type/category are outside this scalar API.

## Encoder and service boundary

Applications own serialization, runtime/version metadata, dataset and custom
namespaces. Check their complete catalog for reserved fields and scalar/object
path conflicts; exact MDC collision hiding is not a complete ECS/schema check.
With a Throwable, the qualified Boot path owns the whole error object, including
its stack trace. A generic Path input is not made absolute by this library.

The [Boot recipe](../../../../../../../../../../docs/guides/libraries/logging-slf4j-boot.md) owns executable
backend configuration and dataset/error rules. It qualifies a synchronous
FileAppender receiving mutable Logback events; async/snapshot event transports
require separate evidence. Its encoder belongs in application code, not this
library. [Bootstrap integration tests](../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingBootRecipeIT.java)
check output bytes and observable failures, whereas
[local ECS tests](../../../../../../../test/java/com/iocextractor/platform/logging/EcsProfileTest.java)
check descriptors and helper behavior. See the
[plain guide](../../../../../../../../../../docs/guides/libraries/logging-slf4j.md#ecs-and-encoder-ownership)
for the producer/encoder ownership contract.
