# ioc-platform-events

## Purpose and boundary

Java 21 control-event metadata and publish-only contracts, with no production
dependencies beyond the JDK. Maven identity: `io.github.xorex-lc:ioc-platform-events`.
Services own concrete events, payload validation, delivery adapters, handlers,
durable state and recovery.

Events announce facts or nudge recoverable work. The core supplies no broker,
subscriber registry, queue, executor, wire format, transaction, acknowledgement,
deduplication or retry. It neither generates IDs nor installs correlation context.
An ordinary publication return is not proof that any handler ran or completed;
a failure can follow partial delivery. Ledger/state and reconciliation belong to
the service, not this port.

## Structure

| Location | Responsibility |
|---|---|
| [Production package](src/main/java/com/iocextractor/platform/events/README.md) | Seven public contracts/helpers, metadata validation, no-ops and recording fixture |
| [Tests](src/test/java/com/iocextractor/platform/events/README.md) | Metadata boundaries, no-op validation, reference ownership, snapshots and concurrent recording |
| [pom.xml](pom.xml) | Published identity, flattened consumer POM, license resource and publication profile |

Public Javadoc and `package-info.java` own exact operation/package guarantees;
the package reference maps entry points and local ownership.

## Dependencies and publication

Keep Spring, SLF4J, IOC catalogs/domain/application, serialization, transport and
keyed execution outside this module. JUnit Jupiter and AssertJ are inherited test
dependencies. Bootstrap's
[ArchitectureTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
checks the framework/business/broker boundary, non-serializable control events
and separation of keyed execution. The POM and packaged consumer checks establish
the dependency-free production closure.

The flattened consumer POM removes the reactor parent. The main/source artifacts
carry the MIT license; `library-publication` adds sources and strict Javadoc
attachments, with doclint warnings treated as failures. The
[permanent release record](../../.github/release-notes/ioc-platform-events-0.3.0-rc.1.md)
retains immutable `0.3.0-rc.1` source, signed hashes and separate Central/GitHub
qualification. Historical repository checks do not qualify a later rebuild.

The [Spring adapter](../../adapters/adapter-events-spring/README.md) is a separate
artifact requiring the exact same core version. Both form one release family.
Current source versions still follow the shared reactor version; independent
version activation remains pending as [TOOL-9](../../docs/KNOWN-ISSUES.md#tool-9) under
[ADR 0048](../../docs/ADR/0048-independent-library-versions-and-reuse.md). Metadata's
`eventVersion` is a service payload version, not this Maven version.

## Entry points and service integration

Implement `ControlEvent`, construct `ControlEventMetadata` explicitly, and inject
`ControlEventPublisher`. Concrete events should carry bounded work references
and stable payloads. The interface adds no copying or delivery synchronization.
`ControlEventObserver` reports publication/individual dispatch telemetry; it is
not a subscription SPI and must not perform business work.

The supplied no-ops validate arguments and discard them. The recorder retains
original event references, duplicates included, in an unbounded copy-on-write
list. It is a small test fixture, not production history. Neither publisher
inspects metadata, dispatches handlers or invokes an observer. See the
[package reference](src/main/java/com/iocextractor/platform/events/README.md)
for validation, snapshot, failure and concurrency limits.

IOC's [EventCoordinationConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/EventCoordinationConfig.java)
explicitly composes the Spring adapter with a service-owned observer; application
event classes and bootstrap listeners remain outside core. The adapter's timing
and exception behavior are its own contract, not guarantees of this interface.
[Event coordination](../../docs/dev/event-coordination.md) owns ledger/reconcile
backstops and admission policy. [ADR 0047](../../docs/ADR/0047-explicit-spring-control-event-adapter.md)
records the two-artifact boundary.

## Verification and related docs

From the repository root:

```bash
make test-module MODULE=platform/platform-events
make docs
make verify
```

Use the [English consumer guide](../../docs/guides/libraries/events.md) or
[Russian consumer guide](../../docs/guides/ru/libraries/events.md) for released dependency
setup and an executable example. The [test reference](src/test/java/com/iocextractor/platform/events/README.md)
separates core behavior from delivery-adapter/service qualification.

For packaged checks, follow the
[publication procedure](../../docs/guides/library-publication.md#events-packaging-preparation):
prepare a new `LIBRARY_BUNDLE` with `make library-bundle LIBRARY_COMPONENT=events`,
then run `make library-consumer` with the same selector/directory. The
[standalone JDK consumer](../../tools/fixtures/events-consumer/README.md) compiles the
actual bilingual guide source outside the checkout with an empty cache and
checks the dependency graph, four primary hashes/origins and routing negatives.
Core-first admission and exact-version adapter qualification are separate steps.
Local examples/package checks are not a new publication or deployed-service test.
Update public Javadoc, package/module references, meaningful tests and paired
guides with contract changes; preserve immutable release records.
