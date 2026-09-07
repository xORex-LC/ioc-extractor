# platform/platform-concurrency

## Purpose and boundary

JDK-only, in-process keyed execution. The module has no runtime dependencies on
Spring, events, IOC types, persistence or transport. Its Maven coordinates in
this reactor are `com.iocextractor:ioc-platform-concurrency:0.3.0-SNAPSHOT`;
external publication is a separate release step.

## Tools and contracts

| Tool | Consumer contract |
|---|---|
| `KeyedSerialExecutor` / `BoundedKeyedSerialExecutor` | Asynchronous FIFO execution per key, per-key waiting-queue bound, explicit admission, orderly shutdown |
| `KeyedExecutionGuard` / `SynchronousKeyedExecutionGuard` | Mutual exclusion per key in the calling thread, preserving return values and thrown failures |
| `WorkKey` | Exact, nonblank string identity; no trimming or normalization |
| `WorkAdmission` / `WorkAdmissionStatus` | Acceptance or rejection of one submission, with queue depth at admission |
| `KeyedSerialExecutorObserver` / `NoopKeyedSerialExecutorObserver` | Optional completion, runtime-failure, admission-rejection and dispatch-rejection telemetry |
| `KeyedSerialExecutorSnapshot` / `KeyedWorkSnapshot` | Immutable per-lane telemetry |
| `KeyedExecutionGuardSnapshot` | Approximate aggregate guard telemetry without key values |

### Asynchronous execution

Equal keys serialize only within the same executor instance. FIFO follows
admission order, which need not match concurrent callers' start times. Every
accepted submission is a separate task; keys do not deduplicate or coalesce it.
Different keys can run concurrently when worker capacity is available.

`maxQueuedPerKey` excludes the dispatched task. Zero permits one outstanding
task per key. There is no global key-count or worker-queue bound. Current sync
and managed-import consumers use finite configured endpoint/source catalogs;
other consumers must bound their key space or own global admission control.

The supplied `ExecutorService` transfers exclusive lifecycle ownership to the
keyed executor. Use a dedicated asynchronous pool, such as
`Executors.newFixedThreadPool(workerCount)` with its default abort policy.
`execute` must dispatch or throw `RejectedExecutionException`. Silent discard,
discard-oldest, caller-runs and direct execution are unsupported. Generic
executor implementations cannot be validated for this contract automatically.
Do not reconfigure, stop or share the supplied pool with unrelated work.

Acceptance is not a completion guarantee. A dispatch rejection abandons the
lane's outstanding in-memory work and reports its count through
`dispatchRejected`. An initial dispatch rejection also returns `REJECTED`;
`rejected` is reserved for admission/shutdown rejection. Runtime failures in
work are reported through `failed`, and the next task proceeds. Errors are not
reported by that callback; lane progression still runs in `finally`.

Observers execute synchronously outside the lane-state lock and must return
promptly. Their runtime exceptions are ignored; errors are not contained.
The default observer is silent. Retry, durable idempotency, reconcile,
cancellation, task results and context propagation belong to consumers.

`shutdown()` and `close()` stop admission and initiate draining without waiting.
Once lanes drain, the executor shuts down its worker service. Call
`awaitTermination(timeout)` separately and handle `false`; it neither starts
shutdown nor cancels tasks. Never wait for termination from an owned worker.
Unbounded or stuck work can prevent termination.

Snapshot `running` means the lane has a dispatched task, including one waiting
in the worker queue; it is not a count of threads executing user code.
`queuedDepth` excludes that task. `oldestAge` includes the outstanding head
task's queue wait and uses the supplied wall clock; negative ages are clamped
to zero. Snapshots are telemetry, not admission or completion predicates.

### Synchronous exclusion

The guard owns no threads. It uses a non-fair, non-interruptible per-key lock:
there is no FIFO, timeout or cancellation promise. Same-thread, same-key nesting
is supported. Consumers nesting different keys must use a consistent ordering
to avoid deadlock. Different keys do not share a work-execution lock, and idle
key state is removed after all callers leave.

Snapshots count a nested same-key execution once and exclude its nested frames
from waiting callers. Counts are approximate during concurrent entry/exit.
The user count changes inside same-key `ConcurrentHashMap.compute`; `volatile`
provides snapshot visibility, not mutation atomicity. If cleanup fails after
work throws, cleanup failure is suppressed onto the original failure.

## Structure and consumers

| Path | Responsibility |
|---|---|
| `pom.xml` | Plain Maven JAR; inherited test dependencies only |
| `src/main/java/com/iocextractor/platform/concurrent/` | Execution interfaces, implementations and value types |
| `src/test/java/com/iocextractor/platform/concurrent/` | Observable concurrency contracts with bounded coordination and worker cleanup |

`ioc-application` uses the synchronous guard for ingestion and the async
interface for managed-import coordination. `adapter-ingest` guards file-ledger
transitions. `bootstrap/ioc-app` composes dedicated worker pools for remote
sync and managed import, and bridges observations into service health.

See [event coordination](../../docs/dev/event-coordination.md) for the
consumer-owned durable recovery model and [module map](../../docs/MODULARIZATION.md)
for dependency direction. No broker, distributed locking or service business
policy belongs in this module.
