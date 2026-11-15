# platform/platform-concurrency

## Purpose and boundary

JDK-only, in-process keyed execution: asynchronous per-key FIFO and synchronous
per-key exclusion. Services own durable work, business failure handling, idempotency
and recovery. This library has no Spring, logging provider, event, IOC model,
persistence or transport dependency. It does not provide distributed locking,
deduplication, task coalescing or durable delivery.

The Maven coordinate is `io.github.xorex-lc:ioc-platform-concurrency`. The
[release record](../../.github/release-notes/ioc-platform-concurrency-0.3.0-rc.1.md) retains
publication and isolated-consumption evidence for `0.3.0-rc.1`; the reactor remains
at the shared development revision. Accepted independent-version ownership is not
yet the Maven/CI implementation; see [TOOL-9](../../docs/KNOWN-ISSUES.md#tool-9).
Public Javadoc owns exact API guarantees. The [English consumer guide](../../docs/guides/libraries/concurrency.md)
and [Russian guide](../../docs/guides/ru/libraries/concurrency.md) own working integration examples.

## Structure and entry points

| Source / directory | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Ordinary JAR, publication metadata, flattened consumer POM and optional sources/Javadoc profile |
| [Concurrent package](src/main/java/com/iocextractor/platform/concurrent/README.md) | Twelve public types, including two execution implementations, and package Javadoc |
| [Tests](src/test/java/com/iocextractor/platform/concurrent/README.md) | Local ordering, exclusion, admission, telemetry, failure and cleanup contracts |

There are no module-local resource trees. The POM adds the repository license to
`META-INF`. `KeyedSerialExecutor` / `BoundedKeyedSerialExecutor` supply async lanes;
`KeyedExecutionGuard` / `SynchronousKeyedExecutionGuard` supply calling-thread
exclusion. Admission and snapshot records expose observations, not completion
handles or a coordination authority. The package reference maps each type.

## Ownership and capacity

Equal keys exclude only within the same coordinator instance. Async tasks preserve
admission order per key; different keys can overlap when worker capacity permits.
The synchronous guard has separate state, same-thread reentrancy, non-fair and
non-interruptible acquisition, with no FIFO or timeout promise.

The async executor exclusively owns a supplied worker service that must dispatch
asynchronously or throw on rejection. Caller-runs/direct/discard policies are
unsupported. The per-key waiting bound excludes the dispatched task and does not
bound key cardinality or the backing worker queue. Services need a bounded key
catalog or separate global admission. Accepted work can still be lost after dispatch
rejection or process failure; reconcile must remain valid without observer delivery.

`close()` / `shutdown()` initiate draining without waiting. Join explicitly with
`awaitTermination` from outside the owned workers. Timeout does not cancel work.
Snapshot `running` includes work waiting in the pool; telemetry must not substitute
for admission, task completion or durable state. See the package reference for
failure callbacks and worker/guard lifecycle details.

## Dependencies and IOC consumers

Production dependencies: JDK 21 only. The parent supplies JUnit/AssertJ in test
scope; the flattened consumer POM removes the reactor parent and test dependencies.
The `library-publication` profile attaches sources and Javadoc, with doclint
`all,-missing`. It prepares local archives, not a publication by itself.
The runtime architecture suite guards framework/event/IOC outward imports.

`ioc-application` uses synchronous exclusion for ingestion and async contracts for
managed-import coordination. `adapter-ingest` uses its own guard for file-ledger
transitions. Bootstrap owns dedicated pools, service observer/health bridges and
bounded shutdown: remote fetch/publish share endpoint lanes; managed import uses
source lanes plus its global drain lane. Service ledgers/reconcile remain authority.
See [event coordination](../../docs/dev/event-coordination.md) and
[managed import](../../docs/dev/dataframe-import.md) for those cross-module flows.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=platform/platform-concurrency
```

Local suites use Surefire. `make verify` checks the full deterministic reactor;
these results are not deployment, throughput or repository-publication qualification.
The [standalone consumer](../../tools/fixtures/library-consumer/README.md) runs public
contracts and exact guide examples against resolved artifacts through the
[publication tooling](../../docs/guides/library-publication.md). Distinguish a local
packaged-JAR example run from an isolated repository-consumer qualification.
[ADR 0028](../../docs/ADR/0028-pilot-library-publication.md) and
[ADR 0029](../../docs/ADR/0029-release-branch-library-publication.md) retain publication
rationale; [test policy](../../docs/TESTING.md) and [module map](../../docs/MODULARIZATION.md)
own project-wide conventions.
