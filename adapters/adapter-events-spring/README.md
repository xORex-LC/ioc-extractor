# ioc-events-spring

Explicit Spring adapter for the JDK-only
[local event contracts](../../platform/platform-events/README.md). Requires Java 21.
Published coordinate: `io.github.xorex-lc:ioc-events-spring:0.3.0-rc.1`;
the reactor remains at `0.3.0-SNAPSHOT`. Separate Central/GitHub consumers passed
in [run 40162675262](https://github.com/xORex-LC/ioc-extractor/actions/runs/40162675262).

## Layer and dependencies

Required main dependencies are `ioc-platform-events` at exactly the same version,
Spring Context 7.0.9 and SLF4J API 2.0.18. The standalone POM has no private parent,
Boot, logging provider, starter, auto-configuration or IOC application/domain
coupling. The test-only application TCK supplies integration annotations.

The only public type is
`com.iocextractor.adapter.out.events.spring.SpringControlEventPublisher`.
Its public constructor accepts an application-owned `ApplicationEventPublisher`
and `ControlEventObserver`; `publish(ControlEvent)` attempts delivery once and
observes its result. No bean, listener, multicaster, error handler, executor or
shutdown resource is created. Services own dispatch configuration and recovery.

Ordinary runtime publication failures are observed and contained; direct Error
and an immediately Spring-wrapped Error propagate. Observer and final logger
runtime failures have explicit containment/suppression rules. No all-handler,
nonthrowing, durable-completion or retry guarantee is added. Concurrent callers
require a thread-safe delegate and observer. The maintained guide and public
Javadoc define the complete failure/disclosure boundary.

## Contents and verification

| Path | Contents |
|---|---|
| `src/main/java/com/iocextractor/adapter/out/events/spring/` | Publisher and package Javadoc |
| `src/test/java/com/iocextractor/adapter/out/events/spring/` | Stub failure contracts and real-context listener/configuration integration |
| `pom.xml` | Reactor build, parent-independent POM and publication archives |

Start with the [English guide](../../docs/guides/libraries/events-spring.md) or
[Russian guide](../../docs/guides/ru/libraries/events-spring.md).
[ADR 0047](../../docs/ADR/0047-explicit-spring-control-event-adapter.md) records
ownership; [IOC coordination](../../docs/dev/event-coordination.md) explains the
service's durable backstops and composition.

Run `make test-module MODULE=adapters/adapter-events-spring` and
`make test-integration-module MODULE=adapters/adapter-events-spring`.
The [standalone Spring consumer](../../tools/fixtures/events-spring-consumer/README.md)
executes both actual guide examples on JARs, checks default/BOM mediation,
dependency/routing negatives and eight primary hashes/origins. EN/RU Java/XML
blocks must match before compilation. The
[release record](../../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md)
retains original signed pair/repository evidence; the
[publication procedure](../../docs/guides/library-publication.md#events-api-and-version-ownership)
requires the published/qualified same-version core before protected adapter
publication. Local consumers do not authorize a release or qualify another
framework baseline, async all-handler completion or second deployed service.
