# ioc-platform-events

JDK-only local control-event metadata and publish-only contracts. Requires Java 21.
Services own concrete payloads, delivery adapters, handlers and durable recovery.
Published coordinate: `io.github.xorex-lc:ioc-platform-events:0.3.0-rc.1`;
the reactor remains at `0.3.0-SNAPSHOT`. The parent-independent POM has no
production dependency. Separate Central/GitHub consumers passed in
[run 40160627532](https://github.com/xORex-LC/ioc-extractor/actions/runs/40160627532).

## Layer and public API

No Spring, IOC domain/application dependency, serialization, transport, keyed
executor or delivery infrastructure belongs here. The seven public types live
in `com.iocextractor.platform.events`:

| Type | Responsibility |
|---|---|
| `ControlEvent` | Service-owned event exposing metadata |
| `ControlEventMetadata` | Producer-supplied identity, type/version, time, correlation and optional causation |
| `ControlEventPublisher` | Functional publication port; implementation defines delivery/failure behavior |
| `ControlEventObserver` | Publication and individual dispatch telemetry, not a subscriber SPI |
| `NoopControlEventPublisher` | Stateless validating discard |
| `NoopControlEventObserver` | Stateless validating observation discard |
| `RecordingControlEventPublisher` | Unbounded copy-on-write fixture retaining original references |

There is no queue, listener registry, wire format, durable delivery, retry or
automatic ID/context propagation. Ordinary return is not all-handler completion;
failure can follow partial delivery. The recorder is a small test fixture,
not production history. Exact validation, snapshot and concurrency contracts
are in public/package Javadoc and the maintained guides.

## Contents and verification

| Path | Contents |
|---|---|
| `src/main/java/com/iocextractor/platform/events/` | Public contracts, no-ops, recorder and package Javadoc |
| `src/test/java/com/iocextractor/platform/events/` | Metadata, input, shallow snapshot, duplicate and bounded concurrency tests |
| `pom.xml` | Managed reactor build and standalone publication POM/JAR/source/Javadoc |

Start with the [English guide](../../docs/guides/libraries/events.md) or
[Russian guide](../../docs/guides/ru/libraries/events.md). The separate
[Spring adapter](../../adapters/adapter-events-spring/README.md) requires the same
core version; [ADR 0047](../../docs/ADR/0047-explicit-spring-control-event-adapter.md)
records that boundary. [IOC coordination](../../docs/dev/event-coordination.md)
owns the service integration.

Run `make test-module MODULE=platform/platform-events`. The
[standalone consumer](../../tools/fixtures/events-consumer/README.md) compiles the
actual bilingual guide example on JARs, checks dependency absence and four-file
hash/origin/routing contracts. The
[permanent release record](../../.github/release-notes/ioc-platform-events-0.3.0-rc.1.md)
retains immutable publication identities and historical gates. The
[publication procedure](../../docs/guides/library-publication.md#events-api-and-version-ownership)
owns API/version policy and core-first release admission. Local checks alone
are not a new publication or a second deployed-service qualification.
