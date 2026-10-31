# Keyed execution with the concurrency library

[Русская версия](../ru/libraries/concurrency.md) · [Library catalog](README.md)

`ioc-platform-concurrency` coordinates work that shares a key within one JVM.
For example, a collector can serialize updates to one endpoint while allowing
other endpoints to progress. The same coordinator instance must be shared by
all callers that need to exclude each other.

Choose one of two tools:

| Need | Tool | Where work runs |
|---|---|---|
| Execute a short operation and return its result while excluding equal-key callers | `SynchronousKeyedExecutionGuard` | Calling thread |
| Submit tasks in order with a bound on waiting work for each key | `BoundedKeyedSerialExecutor` | Dedicated asynchronous worker pool |

The guard provides exclusion without a FIFO promise. The executor preserves
admission order within a key. Equal keys do not deduplicate or coalesce tasks.
Neither tool provides distributed locking, persistence, retries or crash recovery.

## Dependency and version

The published release is available from Maven Central and GitHub Packages:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-concurrency</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Use JDK 21 or later. The qualified baseline is Java 21. There are no third-party
production dependencies, Spring beans, logging providers or required reactor
parent. Central needs no additional repository declaration; GitHub Packages
requires authenticated Maven settings as described in the
[publication guide](../library-publication.md#consumption).

The reactor remains at `0.3.0-SNAPSHOT`. The production Java sources reviewed for
this guide match component tag `ioc-platform-concurrency-v0.3.0-rc.1`. The
[release record](../../../.github/release-notes/ioc-platform-concurrency-0.3.0-rc.1.md)
retains the immutable source and publication evidence. Product and library
versions currently follow the same version line.

## Public tools

All twelve public types belong to `com.iocextractor.platform.concurrent`.

| Type | Purpose |
|---|---|
| `WorkKey` | Exact nonblank string identity for a lane or lock |
| `KeyedExecutionGuard` | Synchronous `execute(key, Supplier<T>)` contract and aggregate snapshot |
| `SynchronousKeyedExecutionGuard` | Reentrant, non-fair per-key exclusion with idle-state cleanup |
| `KeyedExecutionGuardSnapshot` | Approximate `activeKeys`, `executing` and `waiting` counts |
| `KeyedSerialExecutor` | `submit`, `snapshot`, `shutdown`, `awaitTermination` and `close` contract |
| `BoundedKeyedSerialExecutor` | Per-key FIFO queue over an exclusively owned worker service |
| `WorkAdmission` | Key, status and waiting depth at the admission decision; `accepted()` is a convenience check |
| `WorkAdmissionStatus` | `ACCEPTED` or `REJECTED` |
| `KeyedSerialExecutorObserver` | Synchronous completion, failure, admission-rejection and dispatch-rejection callbacks |
| `NoopKeyedSerialExecutorObserver` | Silent observer, selected by the two-argument executor constructor |
| `KeyedSerialExecutorSnapshot` | Immutable list of lane snapshots |
| `KeyedWorkSnapshot` | One lane's key, `queuedDepth`, `running` and `oldestAge` |

Choose keys from a finite service-owned catalog where possible. `WorkKey.of`
preserves its text: `"endpoint-a"` and `" endpoint-a "` are different keys. Include
the relevant namespace when different operations must not share a lane.
Snapshot and admission records expose keys; use identifiers safe for telemetry.

## Execute synchronously

Save this as `KeyedGuardExample.java`. The guard protects the complete
read/increment/write operation for one endpoint, even when other callers use
that same key. The concurrent map also permits unrelated keys to change safely.

```java
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.concurrent.WorkKey;

import java.util.concurrent.ConcurrentHashMap;

public final class KeyedGuardExample {
    public static void main(String[] args) {
        var guard = new SynchronousKeyedExecutionGuard();
        var revisions = new ConcurrentHashMap<WorkKey, Integer>();
        var key = WorkKey.of("collector:endpoint-a");

        int revision = guard.execute(key, () -> {
            int next = revisions.getOrDefault(key, 0) + 1;
            revisions.put(key, next);
            return next;
        });

        if (revision != 1 || guard.snapshot().activeKeys() != 0) {
            throw new IllegalStateException("Revision or idle cleanup differs");
        }
        System.out.println("Guarded revision: " + revision);
    }
}
```

Expected output: `Guarded revision: 1`.

`execute` returns the supplier's result, including null, and propagates its
`RuntimeException` or `Error`. The guard releases the key after either outcome.
If cleanup also fails, that failure is suppressed onto the original work failure.

Same-thread nesting with the same key is supported. Lock acquisition is
non-interruptible and non-fair: there is no FIFO, timed acquisition or cancellation
API. Never wait inside guarded work for another thread that needs the same key.
If an operation acquires several different keys, use a consistent order across
all callers to avoid deadlocks. The guard owns no threads and needs no close.

## Execute asynchronously

Save this as `KeyedExecutorExample.java`. Two accepted operations for the same
endpoint execute in submission order. The example waits explicitly for shutdown
before checking the result.

```java
import com.iocextractor.platform.concurrent.BoundedKeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class KeyedExecutorExample {
    public static void main(String[] args) throws InterruptedException {
        var workers = Executors.newFixedThreadPool(2);
        var executor = new BoundedKeyedSerialExecutor(workers, 1);
        var operations = new CopyOnWriteArrayList<String>();
        var key = WorkKey.of("collector:endpoint-a");

        try {
            var fetch = executor.submit(key, () -> operations.add("fetch"));
            var publish = executor.submit(key, () -> operations.add("publish"));
            if (!fetch.accepted() || !publish.accepted()) {
                throw new IllegalStateException("Work was rejected; schedule recovery");
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(Duration.ofSeconds(5))) {
                    throw new IllegalStateException("Drain timed out; recover pending work");
                }
            } finally {
                // Emergency cleanup only after normal draining did not finish.
                if (!workers.isTerminated()) {
                    workers.shutdownNow();
                    if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Worker pool did not terminate");
                    }
                }
            }
        }

        if (!operations.equals(List.of("fetch", "publish"))) {
            throw new IllegalStateException("Per-key operation order differs");
        }
        System.out.println("Executed: " + String.join(", ", operations));
    }
}
```

Expected output: `Executed: fetch, publish`.

The queue bound `1` permits one dispatched task plus one waiting task **per key**.
If the first task has finished before the second submission, the second starts
a new lane instead. A bound of zero permits one outstanding task for each key.
There is no global key-count or worker-queue bound. Different keys can progress
concurrently only when worker capacity is available. Concurrent submitters are
ordered by admission, which can differ from their call start times.

The two-argument constructor uses a silent observer and UTC system clock.
Overloads accept a `KeyedSerialExecutorObserver` and an explicit `Clock` for
telemetry. All instances coordinate only their own accepted work.

### Worker ownership and shutdown

Supply a dedicated asynchronous `ExecutorService` whose `execute` either
dispatches or throws `RejectedExecutionException`. The default fixed pool in the
example meets this requirement. Caller-runs, direct execution, silent discard
and discard-oldest policies are unsupported. The library cannot reliably detect
an arbitrary executor that violates this precondition.

The keyed executor takes exclusive lifecycle ownership of the worker pool.
Do not share, reconfigure or stop it during normal operation. Stop producers,
call `shutdown()` or `close()`, then call `awaitTermination(timeout)` from a
thread outside the owned pool. Both close methods initiate draining without
waiting; `awaitTermination` does not initiate shutdown or cancel on timeout.

The example retains the pool handle solely for emergency cleanup after draining
fails or is interrupted. Forced shutdown can lose accepted in-memory work and
does not guarantee that non-cooperative tasks stop. A service must define its
bounded task operations, interruption policy and durable recovery path.

## Rejections and failures

Check every `submit` result. Acceptance records in-memory admission, not task
success or durable delivery. A process crash can lose the entire queue.

| Outcome | Executor behavior | Service responsibility |
|---|---|---|
| Key's waiting queue is full, or admission is shut down | Returns `REJECTED`; calls `observer.rejected` | Retain durable work and arrange a later reconcile; avoid an immediate retry loop |
| Initial worker dispatch throws `RejectedExecutionException` | Returns `REJECTED`; calls `dispatchRejected`, not `rejected`; removes the lane | Observe the reported abandoned count and recover from durable state |
| Dispatch of a queued task is rejected | Abandons that lane's outstanding work; reports it through `dispatchRejected` | Previously accepted submissions have no result update; reconcile is required |
| Task returns normally | Calls `completed`, then advances the lane | Decide whether the business operation itself completed successfully |
| Task throws `RuntimeException` | Calls `failed` with the original failure, then advances the lane | Persist business failure/retry state inside the task's own error boundary |
| Task throws `Error` | Error escapes the worker runnable; lane advancement still runs in `finally`; no `failed` callback | Apply the service's fatal-error policy |

Observer methods run synchronously on submitting or worker threads outside the
lane-state lock. Several keys/callers can invoke the observer concurrently.
Keep callbacks prompt and thread-safe. Their runtime exceptions are contained;
their `Error`s propagate. Use observers for telemetry; durable business recovery
must remain valid even if a callback fails. No default logger reports failures.

## Reading snapshots

Executor snapshots are immutable and sorted by key text. `queuedDepth` excludes
the dispatched task. `running=true` means a task has been dispatched; it may
still be waiting in the backing pool's queue. `oldestAge` includes waiting time
since the oldest outstanding task's submission. It uses the supplied wall clock,
so clock changes affect it; negative values are clamped to zero.

Guard snapshots count keys with active or waiting callers. Same-key nested
execution counts once in `executing` and does not count its nested frames as
waiting callers. Aggregate counts are approximate during concurrent entry/exit.
Idle guard state is removed when all callers leave.

Use these views for health and diagnostics. Never use a snapshot to decide
whether a submission will be accepted, an operation has completed or a lock is
available. `WorkAdmission.queuedDepth` is likewise a decision-time observation.

## Invalid inputs

- `WorkKey` rejects null, empty and whitespace-only text with
  `IllegalArgumentException`; it does not trim or normalize accepted text.
- Null keys, work, worker services, observers and clocks are rejected with
  `NullPointerException` at their respective API boundaries.
- A negative queue bound or negative termination timeout is rejected with
  `IllegalArgumentException`; a null timeout causes `NullPointerException`.
  Waiting can throw `InterruptedException`. Use ordinary bounded durations
  representable in milliseconds; conversion overflow can throw `ArithmeticException`.
- Admission/snapshot records reject negative counts. Snapshot lists are copied;
  null elements are rejected. A negative `KeyedWorkSnapshot.oldestAge` is clamped
  to zero rather than rejected.

## Sources and verification

- [Module reference](../../../platform/platform-concurrency/README.md) and
  [package Javadoc sources](../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/).
- [Executor implementation](../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/BoundedKeyedSerialExecutor.java)
  and [guard implementation](../../../platform/platform-concurrency/src/main/java/com/iocextractor/platform/concurrent/SynchronousKeyedExecutionGuard.java).
- [Executor tests](../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/BoundedKeyedSerialExecutorTest.java),
  [guard tests](../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/SynchronousKeyedExecutionGuardTest.java)
  and [key tests](../../../platform/platform-concurrency/src/test/java/com/iocextractor/platform/concurrent/WorkKeyTest.java)
  cover ordering, exclusion, reentrancy, admission, telemetry, failures and cleanup.
- [Standalone consumer](../../../tools/fixtures/library-consumer/README.md)
  compiles and runs the two exact examples above against a resolved JAR. The
  [publication contracts](../../../tools/tests/library-publication-test.py)
  also require identical Java and Maven dependency blocks in both language versions.
- [ADR 0028](../../ADR/0028-pilot-library-publication.md) and
  [ADR 0029](../../ADR/0029-release-branch-library-publication.md) fix publication
  and compatibility policy. [IOC event coordination](../../dev/event-coordination.md)
  explains the service-owned durable backstop.

Run `make test-module MODULE=platform/platform-concurrency` for the library
tests and `make library-publication-test` for guide extraction/parity contracts.
For the copied packaged consumer, use the bundle/consumer commands in the
[publication guide](../library-publication.md#local-packaging-qualification).
Those checks do not establish a second deployed service or a changed JDK baseline.
Update both languages and their examples whenever these public behaviors change.
