# IOC typed-event facade and ETL observer

This package binds IOC catalogs to the shared SLF4J builder and observes the ETL
kernel. It depends on sibling vocabulary, the generic logging API, SLF4J and ETL;
no domain, adapter, bootstrap, JSON or appender configuration belongs here. See
[package-info.java](package-info.java) and the
[module reference](../../../../../../../README.md).

## Source map

| Source | Responsibility |
|---|---|
| [LogEvents.java](LogEvents.java) | Fresh IOC TRACE/DEBUG/INFO/WARN/ERROR event factories using the caller's logger |
| [LogEvent.java](LogEvent.java) | Bind IOC fields/actions/outcomes to the reusable mutable event builder |
| [LoggingPipelineObserver.java](LoggingPipelineObserver.java) | Owned run/stage correlation scopes and stage lifecycle records |

## Catalog bindings and delegated behavior

Use `action(EventAction)` for its stable lower-case value and
`outcome(EventOutcome)` for the shared ECS wire value. `durationNanos` keeps
nonnegative nanoseconds without conversion. `field(LogField, Object)` accepts
only catalogued descriptors and delegates scalar representation checks; it is
not complete semantic/disclosure validation. For example, generic STRING enum
conversion uses `toString()`, so assigning an action enum through `field` is not
equivalent to calling `action`.

Shared mechanics own type identity, STRING/LONG/BOOLEAN snapshots, null omission,
exact MDC collision hiding and provider calls. Invalid fields/helpers fail before
level filtering. A null local value does not hide ambient MDC; non-null local
values hide only their exact same-key collision for the emission. The facade
keeps the supplied logger category, message and optional original Throwable.
No automatic redaction, retry or fallback logger is added. Builders remain
mutable and not thread-safe; see the
[shared logging reference](../../../../../../../../platform-logging-slf4j/src/main/java/com/iocextractor/platform/logging/README.md).

## Pipeline observer scopes and records

`openRun` uses the IOC callback scope factory to install `ioc.run.id` and
`ioc.source.id`, plus `ioc.source.path` and `ioc.mode` from envelope attributes
under those wire keys. Missing optional attributes temporarily hide stale ambient
values. `openStage` sets `ioc.stage` from the current StageId. The runner owns
scope closure; context is reconstructed on the calling worker, not propagated
by an executor helper. Untouched ambient context is not globally cleared.

| Callback | Level / action / outcome | Additional data |
|---|---|---|
| `stageStarted` | DEBUG / `stage_start` / unknown | Stage correlation from its scope |
| `stageCompleted` | DEBUG / `stage_complete` / success | Supplied elapsed nanoseconds |
| `stageFailed` | ERROR / `stage_complete` / failure | Supplied elapsed nanoseconds and original RuntimeException |

The observer uses its own `LoggingPipelineObserver` logger category. The runner
supplies duration and callback timing; the observer does not measure work or
classify durable state. Terminal suppression delivery occurs under the runner's
run scope after active stage scopes unwind. Separate preparation/continuation
invocations have separate run scopes.

Callbacks are synchronous and not generally isolated. Provider, scope or callback
failure can fail a call before work or after stage effects; `stageFailed` is not
exhaustive and no transaction/recovery authority is implied. The
[ETL reference](../../../../../../../../platform-etl/README.md) owns these failure boundaries.
[AppConfig](../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
selects this observer for extraction. The
[test reference](../../../../../../test/java/com/iocextractor/observability/README.md)
separates facade captures, shared mechanisms, cross-module pipeline correlation
and bootstrap JSON evidence.
