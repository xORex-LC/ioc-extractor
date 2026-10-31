# platform/platform-concurrency

## Purpose and boundary

JDK-only, in-process keyed execution. The module supplies asynchronous per-key
FIFO and synchronous per-key exclusion; services own durable state and recovery.
It has no Spring, events, IOC model, persistence or transport dependency.

Release `io.github.xorex-lc:ioc-platform-concurrency:0.3.0-rc.1` is published to
Maven Central and GitHub Packages. The reactor continues at `0.3.0-SNAPSHOT`.
The consumer POM has no reactor parent or test dependencies. Publication details
and historical qualification are in the
[release record](../../.github/release-notes/ioc-platform-concurrency-0.3.0-rc.1.md).

Start with the [English consumer guide](../../docs/guides/libraries/concurrency.md)
or [Russian consumer guide](../../docs/guides/ru/libraries/concurrency.md) for
working examples, key selection, capacity, errors, telemetry and shutdown.
Public Javadoc is the exact API reference.

## Public entry points

All types are in `com.iocextractor.platform.concurrent`.

| Types | Responsibility |
|---|---|
| `KeyedSerialExecutor`, `BoundedKeyedSerialExecutor` | Async per-key FIFO, waiting-queue bound, admission and orderly shutdown |
| `KeyedExecutionGuard`, `SynchronousKeyedExecutionGuard` | Calling-thread per-key exclusion with same-thread reentrancy |
| `WorkKey` | Exact nonblank string identity |
| `WorkAdmission`, `WorkAdmissionStatus` | Admission result and decision-time queue depth |
| `KeyedSerialExecutorObserver`, `NoopKeyedSerialExecutorObserver` | Optional synchronous telemetry |
| `KeyedSerialExecutorSnapshot`, `KeyedWorkSnapshot`, `KeyedExecutionGuardSnapshot` | Immutable telemetry values |

The supplied worker pool belongs exclusively to the keyed executor and must
execute asynchronously or throw on dispatch rejection. The queue bound excludes
dispatched work and does not bound the number of keys. `close()` starts draining
without waiting; joining is explicit. The synchronous guard uses a non-fair,
non-interruptible lock and has no FIFO or timeout promise. These ownership and
failure rules are explained with examples in the consumer guide.

## Structure

| Path | Responsibility |
|---|---|
| `pom.xml` | Plain JAR, standalone consumer POM, sources and strict Javadoc archives |
| `src/main/java/com/iocextractor/platform/concurrent/` | Twelve public types, two implementations and package Javadoc |
| `src/test/java/com/iocextractor/platform/concurrent/` | Ordering, exclusion, admission, failure, telemetry and bounded cleanup contracts |

## Dependencies and consumers

Production dependencies: JDK 21 only. JUnit and AssertJ are test dependencies.
No logging provider or framework is installed by this module.

`ioc-application` uses the guard for ingestion and the async interface for managed
import. `adapter-ingest` guards ledger transitions. `bootstrap/ioc-app` supplies
dedicated pools for remote sync/import and bridges observations into health.
[Event coordination](../../docs/dev/event-coordination.md) explains IOC's durable
backstop; the [module map](../../docs/MODULARIZATION.md) defines dependency direction.

Run `make test-module MODULE=platform/platform-concurrency` for focused tests.
The [standalone consumer](../../tools/fixtures/library-consumer/README.md) compiles
and executes the guide examples against a resolved JAR. Packaging and isolated
consumer commands are in the [publication guide](../../docs/guides/library-publication.md).
[ADR 0028](../../docs/ADR/0028-pilot-library-publication.md) and
[ADR 0029](../../docs/ADR/0029-release-branch-library-publication.md) record the
accepted publication and compatibility decisions.
