# Event-driven coordination

[Русская версия](ru/event-coordination.md)

Control events reduce latency between capabilities over durable ledgers and
periodic reconciliation. They do not turn the core into an in-process broker.
This document owns service composition; reusable event and executor APIs keep
precise contracts in their module references and Javadoc.

## When an event fits

Use an event when all of the following hold:

1. A durable fact in one capability should accelerate another.
2. The consumer can reread authoritative state.
3. Lost or repeated hints are safe because a non-event backstop exists.
4. The publisher does not need a synchronous business response.

If losing a message loses work that cannot be rediscovered, introduce a durable
ledger, outbox or delivery protocol. The current control-event mechanism does
not supply that guarantee.

## Architecture boundary

`platform-events` owns framework-free event values/metadata, publish-only
`ControlEventPublisher` and delivery observation. It contains no Spring events,
queue, subscriber registry, routing framework, wire serialization, retries,
ordering, durable delivery, outbox, DLQ or keyed execution.

The [module reference](../../platform/platform-events/README.md) owns the JDK
contract and recording-publisher limits; the [consumer guide](../guides/libraries/events.md)
shows a standalone event. Metadata preserves supplied values without normalization.
A successful publisher return does not acknowledge every handler. Publication can
fail after partial delivery: `published` records normal return and `publishFailed`
records failure, not absence of completed handlers.

The [Spring bridge](../../adapters/adapter-events-spring/README.md) delegates to
Spring application events. Bootstrap `EventCoordinationConfig` explicitly creates
it with the IOC observer. It does not replace the multicaster, error handler or
executor. Dispatch therefore inherits the supplied Spring configuration; the
bridge does not promise asynchronous or durable delivery.

Original `Error` propagates, including one directly wrapped by Spring in
`UndeclaredThrowableException`. On observer failure, publication failure stays
primary and a distinct observer failure is suppressed when the throwable allows
it. Runtime failure in the final WARN does not change the producer's delivery
outcome; observation/logging `Error` still escapes. See
[ADR 0047](../ADR/0047-explicit-spring-control-event-adapter.md).
Isolated JDK/Spring consumers test packaged JAR boundaries without IOC bootstrap,
with a separate Maven cache and both owned artifacts checked; packaging details
belong to the [publication guide](../guides/library-publication.md#events-packaging-preparation).

Listeners validate/observe the hint and admit work to a consumer-owned executor
or scheduler. Heavy use cases do not run on the publisher thread. Post-commit
RuntimeException handling belongs to each producer: several producers deliberately
retain the committed result and rely on the backstop. This does not make every
possible exception harmless; a propagating `Error` can follow a durable commit.

## Correctness model

```text
durable state transition
  |-- publish event hint --> listener --> consumer-owned admission --> work
  \-- periodic reconcile -------------------------------------------> work

work -> durable idempotency ledger / authoritative state
```

- Metadata provides correlation/causation and contract version, not a wire protocol.
- Consumer ledger/identity provides idempotency; an event id does not.
- A consumer's fast path and backstop converge at one admission path.
- Remote sync uses endpoint-keyed execution; ingest-to-export uses
  `DaemonExportScheduler.nudge()` and its own cadence/coalescing policy.
- Bounded admission may shed a hint; reconciliation must rediscover eligible work.
- Local WatchService and SMB `CHANGE_NOTIFY` for managed import feed source-scoped
  detection admission. Signals are not delivery identity, order or ownership evidence.

A backstop depends on durable state and retained inputs. It cannot recover an
unlisted remote file deleted before the next scan or repair a wrong idempotency
key. Current fetch namespace/collision defects are `OPS-11`/`OPS-12` in
[known issues](../KNOWN-ISSUES.md); the event architecture does not resolve them.

## Keyed execution contract

The exact API belongs to [platform-concurrency](../../platform/platform-concurrency/README.md).
Async execution serializes accepted tasks of one key in admission order; it does
not coalesce duplicate hints. Queue bounds apply per key, with no global bound on
key count. Bootstrap supplies a dedicated worker pool with explicit rejection and
transfers its shutdown ownership to the executor. `close()` starts drain without
joining; bounded waiting belongs to the lifecycle owner.

Health `running` means an occupied lane, including waiting for a worker thread;
`oldestAge` includes the running task's prior queue wait. The synchronous guard
used by ingestion/file ledger runs on the caller thread with no FIFO guarantee;
nested same-key calls count as one execution. Snapshots observe activity, not
admission or durable recovery authority.

Keys are local to one executor instance. Two JVMs do not share exclusion, and
different endpoint keys do not protect a shared inbox filename. A manual CLI
operation must be coordinated with the daemon rather than assumed to acquire
its in-memory lane.

## Implemented flows

### Remote detection to fetch

Periodic/startup/SMB doorbells enter one single-flight detection path per source.
Bounded batches are published as `RemoteChangeBatchDetected`; the listener admits
fetch by endpoint. `remote_fetch_ledger` and subsequent listing recover lost or
repeated hints for eligible retained objects. In-flight claims are process-local
and released when execution or admission fails.

### Export completion to publish

Normal export completion and recovery publish `SliceCompleted` after durable
completion. The listener accelerates that slice's publication;
catalog/`publish_ledger` reconciliation remains the backstop. A completed local
slice does not itself prove a remote delivery.

### Canonical change to export

Completed document ingestion and committed managed import report public mutations
through `CanonicalArtifactsChanged(operationId, affectedArtifacts)`. Import emits
its fact only after the cross-artifact dataframe transaction. The listener calls
`DaemonExportScheduler.nudge()`; the scheduler rereads current revisions and
applies quiet/max-cap cadence. Public-field mutation can advance revision without
creating a new canonical lifecycle. Periodic export polling covers restart,
duplicate/no-op operations and lost hints; an event never forces a slice on its own.

### Lifecycle to deadline and projection

After lifecycle-aware confirmation, `CanonicalDeadlineScheduleChanged` nudges the
aggregate deadline schedule and `MutableArtifactProjectionRequired` nudges mutable
CSV convergence. Expiry emits at most one projection hint per affected artifact.
Both are lossy: the scheduler rereads indexed deadlines/projection generations,
with a `5s` backstop for lost hints and restart.

Expiry and TTL-only renewal do not advance public-data revision and do not force
immutable export. Insertion, reappearance and supported public-field mutations
can advance it. The deadline/projection hint cannot bypass the export plan/revision
gates. See [artifact export](artifact-export.md).

### Managed-import detection

Local filesystem and SMB signals accelerate the source detector; listing,
capability gates and the delivery ledger own recognition and ordered admission.
A signal contains no accepted CSV rows and cannot grant transport ownership.
Import receipt recovery is separate from document/fetch idempotency.

## Invariants

1. Publish a control fact after its authoritative transition, not before commit.
2. Carry identity or a claim check, not large mutable business state.
3. Keep event type/version stable and create metadata consistently.
4. The publisher does not depend on a specific consumer.
5. Consumers handle duplicates and concurrent triggers through their own authority.
6. Every event path has a testable non-event backstop and retention assumptions.
7. Observe emission/dispatch failure without claiming rollback of committed state.

ArchUnit protects framework-free/non-serializable platform events and the separate
keyed-concurrency boundary. Those rules do not prove every operational flow or
external target has been qualified.

## Adding a flow

1. Identify the durable fact and its owner.
2. Establish idempotency identity, retained state and a reconciliation backstop.
3. Declare an application-owned `ControlEvent` with stable type/version.
4. Publish through `ControlEventPublisher` after the authoritative transition.
5. Hand off from a bootstrap listener to bounded consumer-owned admission.
6. Observe publish, dispatch, shed and work outcomes separately.
7. Test lost/duplicate hints, restart and concurrent triggers, including a failure
   after commit. Assert bounded worker cleanup.

A flow fitting the existing publish contract does not require changing the library.

## When a broker or outbox is needed

Cross-process delivery or at-least-once delivery of an event that reconciliation
cannot reconstruct triggers a separate design. Durable outbox, broker, redelivery
and DLQ belong in adapters behind an explicit delivery protocol. Current events
have no wire schema; do not acquire one implicitly.

## Sources of truth

- [Publisher composition](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/EventCoordinationConfig.java)
  and [Spring publisher](../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/SpringControlEventPublisher.java).
- [Detection coordinator](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/RemoteFetchDetectionCoordinator.java),
  [fetch listener](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/RemoteChangeFetchListener.java)
  and [publish listener](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SliceCompletedPublishListener.java).
- [Import post-commit decorator](../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/EventPublishingCanonicalImportWriter.java)
  and [canonical confirmation decorator](../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/lifecycle/EventPublishingCanonicalArtifactWriter.java).
- Boundary tests and [BOUNDARIES.md](../BOUNDARIES.md) cover dependency constraints.

## When to update

Update this document and its translation when delivery guarantees, event ownership,
metadata, admission/backstop doctrine or external delivery infrastructure changes.
A new event following unchanged rules can be documented in its package/module reference.

## Related documents

- [Sync](sync.md) — fetch/publish consumers.
- [Ingestion](ingestion.md) — canonical-change producer.
- [Observability](observability.md) — event observation.
- [ADR-0013](../ADR/0013-event-driven-coordination.md) and
  [ADR-0014](../ADR/0014-event-driven-ingest-to-delivery.md).
