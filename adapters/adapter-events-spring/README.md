# adapters/adapter-events-spring

## Purpose and boundary

Explicit Spring adapter for the framework-free
[local event contracts](../../platform/platform-events/README.md), published as
`io.github.xorex-lc:ioc-events-spring`. The only public type,
`SpringControlEventPublisher`, implements `ControlEventPublisher` using a
service-supplied `ApplicationEventPublisher` and `ControlEventObserver`.

The adapter owns one publication attempt and its publication-level observation.
Services own listener registration, dispatch configuration, isolation, execution,
durable state and recovery. There is no broker, queue, retry, serialization or
subscription framework here.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Reactor identity, dependencies, flattened consumer POM and publication archives |
| [Production package](src/main/java/com/iocextractor/adapter/out/events/spring/README.md) | Publisher, package Javadoc and exact failure/disclosure boundaries |
| [Test package](src/test/java/com/iocextractor/adapter/out/events/spring/README.md) | Stub-based failure contracts and real Spring-context integration |

One production package has a matching test package. Synthetic events, observers
and logger fixtures are inline; there is no module-local test-resource tree.
Packaging copies the repository LICENSE into `META-INF`.

## Dependencies

Main dependencies are `ioc-platform-events`, Spring Context and SLF4J API.
The qualified baseline is Java 21, Context 7.0.9 and SLF4J API 2.0.18. The
parent manages reactor versions; packaging resolves a parent-independent POM.
The core/adapter publication family requires the same version. Current source
versions remain lockstep; the accepted independent-version transition is still
[TOOL-9](../../docs/KNOWN-ISSUES.md), not a runtime compatibility checker.

There is no production Boot dependency, logging provider, starter,
auto-configuration or IOC application/domain coupling. The application TCK is
test-scoped for integration annotations. Keep service events and IOC logging
policy in their owning modules rather than adding them to this adapter.

## Entry points and contracts

Construction is explicit; the adapter installs no bean, listener, multicaster,
error handler or executor and owns no shutdown resource. Ordinary runtime
publication failures are observed and contained; direct Errors and immediately
Spring-wrapped Errors propagate. Publication can fail after partial delivery,
and normal return does not establish any/all-handler or durable completion.

Observer and fallback-logger failures have separate containment rules, detailed
in the production package. Concurrent use requires a thread-safe delegate and
observer. No metadata inspection, payload copy or MDC propagation is added.

IOC wires the publisher and its logging observer in
[EventCoordinationConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/EventCoordinationConfig.java).
Its [coordination model](../../docs/dev/event-coordination.md) keeps events as latency
hints and durable ledgers/reconciliation as the correctness backstop. Replacing
that policy or configuring a different multicaster belongs to the service.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-events-spring
make test-integration-module MODULE=adapters/adapter-events-spring
```

The first command runs Surefire; the second runs the Failsafe Spring-context
contract and upstream modules. Full reactor acceptance uses `make verify`.
The test README distinguishes inline executor checks from asynchronous-worker
qualification. Public Javadoc and the [consumer guide](../../docs/guides/libraries/events-spring.md) ([RU](../../docs/guides/ru/libraries/events-spring.md))
are the API and usage references; [ADR 0047](../../docs/ADR/0047-explicit-spring-control-event-adapter.md)
records ownership.

Normal packaging produces `target/publication.pom`; the `library-publication`
profile adds source and strict Javadoc archives. The
[standalone consumer](../../tools/fixtures/events-spring-consumer/README.md)
uses prepared core/adapter bundles to execute both guide examples and check
mediation, routing and eight primary artifact hashes/origins. A reactor pass
alone does not establish repository consumption or another framework baseline.

Published `0.3.0-rc.1` has separate Central/GitHub consumer evidence in the
[release record](../../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md)
and protected run 40162675262. The [publication procedure](../../docs/guides/library-publication.md)
requires the published/qualified same-version core before adapter admission.
Those historical identities do not qualify a changed bundle, asynchronous
all-handler completion or a second deployed service.
