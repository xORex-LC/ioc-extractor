# Concurrency tests

## Responsibility and suites

Three Surefire suites exercise the JDK-only execution primitives. There are no
local Failsafe suites, resource fixtures or test-support dependencies on application,
adapters or bootstrap. Synthetic observers/clocks/executors live inside the suites.

| Source | Contract covered |
|---|---|
| [BoundedKeyedSerialExecutorTest](BoundedKeyedSerialExecutorTest.java) | Same-key exclusion/FIFO, cross-key progress, queue overflow/zero bound, orderly shutdown, work failures, observer isolation, dispatch rejection and age/depth snapshots |
| [SynchronousKeyedExecutionGuardTest](SynchronousKeyedExecutionGuardTest.java) | Exclusion/waiter telemetry, cross-key overlap, same-thread nesting/failure, idle cleanup and primary/suppressed cleanup failures |
| [WorkKeyTest](WorkKeyTest.java) | Nonblank key creation and blank rejection |

## Fixture ownership and evidence limits

Async and guard suites have class-level `@Timeout(30)` and bounded latch/future/
termination waits. Async teardown releases owned blocking latches, initiates drain
and asserts termination; emergency cleanup then stops the pool if needed. Guard
tests release latches and stop their owned virtual-thread executors in `finally`.
No sleep/retry conceals a failure.

The async rejection fake accepts its first dispatch and explicitly rejects later
ones, so the test can observe abandoned work. Another case intentionally stops
the pool before admission to exercise first-dispatch rejection. This fault injection
is not supported normal lifecycle ownership. Observer runtime-failure fakes check
that telemetry cannot block FIFO/rejection progress; they do not prove arbitrary
observer `Error` containment or custom worker-service compatibility.

The adjustable clock checks age including queue wait. Guard reflection-based cases
intentionally corrupt tracked state to exercise cleanup error precedence; they do
not represent a supported operation or ordinary state transition. Local stress and
coordination examples do not prove distributed locking, fairness, throughput,
whole-process capacity or a live service's recovery protocol.

## Running and integration evidence

Run from the repository root:

```bash
make test-module MODULE=platform/platform-concurrency
make test-one MODULE=platform/platform-concurrency TEST=BoundedKeyedSerialExecutorTest
```

`make verify` also checks downstream consumers and architecture. The
[standalone consumer](../../../../../../../../../tools/fixtures/library-consumer/README.md) qualifies packaged
public use and exact guide examples through separate publication tooling; it is
not executed merely by running these three local suites. Follow
[TESTING](../../../../../../../../../docs/TESTING.md) for bounded coordination, assertions and report ownership.
The [production package reference](../../../../../../main/java/com/iocextractor/platform/concurrent/README.md)
owns the source map; exact operation guarantees remain in Javadoc.
