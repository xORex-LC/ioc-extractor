# platform/platform-etl

## Purpose and boundary

Framework-free Pipes-and-Filters contracts and sequential execution for the
application's extraction pipeline. The kernel carries payloads, correlation
metadata and diagnostics without knowing IOC types, concrete stages, storage,
CLI or logging implementations. The application composes the stages and owns
their effects, resources and recovery.

This is a reactor module, `com.iocextractor:ioc-platform-etl`; it has not been
admitted as an independently published library. Its generic payload contracts
still depend on the project's diagnostics catalog and exception layer.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Module identity and dependencies |
| [src/main/java/com/iocextractor/platform/etl/](src/main/java/com/iocextractor/platform/etl/README.md) | Envelopes, stage composition, runner and observation hooks |
| [src/test/java/com/iocextractor/platform/etl/](src/test/java/com/iocextractor/platform/etl/) | Envelope and runner contract tests |

## Dependencies

Depends on `ioc-platform-diagnostics-api`, `ioc-platform-diagnostics` and
`ioc-platform-errors`. Keep IOC domain/application types, adapters, bootstrap,
Spring and SLF4J/Logback out of this module. Logging/MDC adapters implement
`PipelineObserver` elsewhere.

## Entry points and contracts

- `Pipeline.start().then(...)` connects declared stage payload types at compile
  time. The direct record constructor accepts wildcard stages and does not
  validate their type chain. Its defensive list copy does not copy stage
  instances or make their captured state safe for concurrent runs.
- `PipelineRunner` executes stages on the calling thread. Stages preserve the
  existing diagnostic prefix and exact observed counts. New retained occurrences
  are delivered to the non-throwing `DiagnosticSink` before policy evaluation;
  initial diagnostics are not redelivered as new occurrences.
- The retention budget limits `ELEMENT`/`RUN` diagnostic details independently of
  payload size. `OPERATION` occurrences bypass it, so it is not a total
  diagnostic-count or heap bound. The result summary preserves exact counts,
  including omitted detail. A custom
  `FailurePolicy` sees a notification reconstructed from compacted retained
  details and a suppression marker, rather than the full diagnostic history.
- Policy runs after each stage. An initial `FATAL` does not prevent the first
  stage from running, and an empty chain has no policy checkpoint. A rejection
  can follow a completed stage effect; the runner owns no transaction, rollback,
  retry, timeout or cancellation protocol.
- Observer callbacks and scope cleanup run synchronously and can fail the call
  before execution or after stage effects. `stageFailed` is not exhaustive:
  scope-opening, stage-start and scope-close failures need not reach it. Stage
  `DiagnosticException`s are delivered and propagated; another runtime exception
  from the stage body becomes `PIPELINE.STAGE_FAILED`. `Error` handling has no
  general diagnostic or failure-callback guarantee.
- Once stage-loop execution begins, `runWithOutcome` attempts final suppression
  delivery for its segment.
  `runPreparation` defers terminal delivery on success; its owner must continue
  with the returned envelope or call `finishPreparation` once. That method only
  emits the retained marker: it does not cancel work, close stage resources or
  deduplicate repeated finalization. Run scopes cover their own invocation, not
  the interval between separately invoked segments.

The precise failure and lifecycle boundaries live in
[Pipeline](src/main/java/com/iocextractor/platform/etl/Pipeline.java),
[PipelineRunner](src/main/java/com/iocextractor/platform/etl/PipelineRunner.java)
and [PipelineObserver](src/main/java/com/iocextractor/platform/etl/PipelineObserver.java)
Javadoc. Do not treat successful observation as durable completion evidence.

## Verification and related docs

`make test-module MODULE=platform/platform-etl` exercises the module and its
upstream dependencies. [PipelineRunnerTest](src/test/java/com/iocextractor/platform/etl/PipelineRunnerTest.java)
covers stage diagnostics, policy failures, bounded retention and observer scopes;
[EnvelopeTest](src/test/java/com/iocextractor/platform/etl/EnvelopeTest.java)
covers envelope transitions.

See [processing](../../docs/dev/processing.md) for the IOC stage composition and
prepare/commit boundary, and [observability](../../docs/dev/observability.md) for
diagnostic delivery and logging adapters.
