# com.iocextractor.platform.concurrent

## Responsibility and source map

Framework-free execution primitives with invocation-independent, process-local
coordination state. [package-info.java](package-info.java) separates concurrency
from events and durable recovery. All production collaborators are JDK types or
other types in this package.

| Sources | Responsibility |
|---|---|
| [WorkKey](WorkKey.java) | Exact nonblank string identity; no trimming, case folding or namespacing |
| [KeyedSerialExecutor](KeyedSerialExecutor.java), [BoundedKeyedSerialExecutor](BoundedKeyedSerialExecutor.java) | Async admission/FIFO and exclusive worker-pool lifecycle |
| [KeyedExecutionGuard](KeyedExecutionGuard.java), [SynchronousKeyedExecutionGuard](SynchronousKeyedExecutionGuard.java) | Synchronous per-key exclusion and idle-lock cleanup |
| [WorkAdmission](WorkAdmission.java), [WorkAdmissionStatus](WorkAdmissionStatus.java) | Accepted/rejected decision and captured waiting depth |
| [KeyedSerialExecutorObserver](KeyedSerialExecutorObserver.java), [NoopKeyedSerialExecutorObserver](NoopKeyedSerialExecutorObserver.java) | Optional synchronous telemetry and default silent implementation |
| [KeyedSerialExecutorSnapshot](KeyedSerialExecutorSnapshot.java), [KeyedWorkSnapshot](KeyedWorkSnapshot.java) | Immutable lane observations |
| [KeyedExecutionGuardSnapshot](KeyedExecutionGuardSnapshot.java) | Approximate guard counts without key values |

## Async lane lifecycle

Admission reserves one dispatched task for an idle key; further accepted work
waits in FIFO order for that key. A zero waiting bound still allows one outstanding
task per key. Concurrent submitters are ordered by admission, not caller start
time. Equal-key submissions are distinct tasks. Submitted `Runnable` references
and their captured objects are retained as supplied, with no context/MDC capture.

The implementation changes lane state under one internal lock and invokes work/
observers outside it. A lane remains occupied through work and completion/failure
telemetry before advancing. Idle lanes are removed. Different keys can execute
concurrently, so shared captured state and observers need their own thread safety.

| Outcome | Implementation behavior |
|---|---|
| Waiting bound reached or shutdown admission closed | Returns rejection and invokes `rejected` |
| First dispatch rejected by the worker service | Abandons the lane, invokes `dispatchRejected`, returns rejection without a duplicate `rejected` callback |
| Queued dispatch rejected | Removes outstanding work for that lane and reports abandoned count through `dispatchRejected`; earlier admission results are not updated |
| Work returns normally | Invokes `completed`, then advances the lane |
| Work throws `RuntimeException` | Invokes `failed`, then advances; business failure handling belongs to work |
| Work throws `Error` | Error escapes; `finally` still attempts lane advancement; no `failed` callback |

Observer methods run synchronously on submitting/worker threads and can overlap
across callers/keys. They must return promptly and contain no business recovery
protocol. Observer runtime exceptions are ignored; errors are not contained.
The default observer is silent, with no implicit logging of task failures.

The supplied worker service must dispatch asynchronously or explicitly reject;
caller-runs, inline and silent discard policies violate the contract. Do not share,
reconfigure or externally stop it during normal operation. Stop producers, initiate
shutdown, then await termination outside the pool. Workers are shut down after all
occupied lanes drain. Waiting neither initiates shutdown nor cancels on timeout;
joining from a task that prevents its own drain can deadlock.

## Synchronous guard lifecycle

The guard retains per-key non-fair `ReentrantLock` state while holders or waiters
exist. Work runs on the calling thread; it returns the supplier's result and
propagates `RuntimeException`/`Error`. Release removes idle state; a cleanup failure
is suppressed onto an existing work failure rather than replacing it.

Same-thread nesting of the same key is supported. Lock acquisition is not
interruptible and has no timeout or FIFO guarantee. Cross-key nesting needs a
consistent acquisition order; do not await another thread needing the same key.
The guard owns no workers and needs no shutdown. Its keys do not coordinate with
a separate guard or async executor, even when key strings match.

## Observations and validation boundaries

Executor snapshots copy lane records and sort them by key text. Waiting depth
excludes the dispatched task; `running=true` includes pool-queued work. Age starts
at submission and includes queue wait, uses the supplied wall clock and clamps
negative values to zero. It is not a monotonic elapsed-time/SLO authority.
Admission depth is a captured observation, not a live lane measurement; rejection
after shutdown reports zero even when previously admitted work is draining.

Guard counts are approximate during concurrent transitions. Reentrant frames
count once as executing and do not count as waiting. Neither snapshot can prove
that a task completed, a lock is free or a future submission will be accepted.
Lane snapshots and admission expose exact key text; callers choose telemetry-safe
keys and their namespace/cardinality.

Null/blank key text and negative bounds/counts are rejected. API null checks,
timeout conversion limits and record invariants are described in public Javadoc
and the [consumer guide](../../../../../../../../../docs/guides/libraries/concurrency.md). These primitives do
not validate a caller's business identity or provide distributed fencing.

See [local tests](../../../../../../test/java/com/iocextractor/platform/concurrent/README.md)
for evidence and the [module README](../../../../../../../README.md) for
Maven/publication boundaries. IOC recovery policy belongs to its own services.
