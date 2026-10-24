# ioc-events-spring

Explicit Spring delivery adapter for the JDK-only
[control-event contracts](../../platform/platform-events/README.md). Requires
Java 21; the qualified reactor baseline is Spring Context 7.0.9 and SLF4J API
2.0.18. The current coordinate is
`com.iocextractor:ioc-events-spring:0.3.0-SNAPSHOT`; independent publication and
its parent-free POM remain a separate qualification step.

## Layer and dependencies

Main dependencies are `ioc-platform-events`, Spring Context and SLF4J API.
There is no Boot starter, logging provider, IOC application/domain dependency or
auto-configuration. The test-only application TCK supplies `@IntegrationTest`.

The only public type is
`com.iocextractor.adapter.out.events.spring.SpringControlEventPublisher`.
Construct it with the service's `ApplicationEventPublisher` and
`ControlEventObserver`. It creates no Spring beans or owned resources. Services
own listeners, multicaster/error-handler settings, executors and shutdown.

## Publication and failure behavior

A null event is rejected before delivery or observation. Otherwise the original
reference is handed to Spring once, without reading metadata. A normal return
calls `published`; a runtime publication failure calls `publishFailed` with the
original exception and returns normally. Partial delivery may already have
occurred; there is no retry or all-handler completion guarantee.

Direct `Error` and an `Error` directly wrapped in Spring's
`UndeclaredThrowableException` propagate as the original Error. Other runtime
wrappers retain their identity; the adapter does not search arbitrary cause
chains. Runtime observer failures use a best-effort WARN. After publication
failure, that WARN uses the publication failure as primary and attaches a
distinct observer failure as suppressed when supported. Self-suppression is
avoided; exceptions with suppression disabled retain only primary evidence.

Runtime failure of the fallback logger is contained without retry or recursive
logging. Errors from observers or the logger propagate. Exception text may
reach the logging backend; services own safe exception content and disclosure.

Default synchronous Spring dispatch stops at the first uncaught listener
failure. A service error handler can choose to continue. Failures already
handled there, or arriving after an asynchronous hand-off, are outside the
outer publisher's observation. Concurrent callers need thread-safe publisher
and observer implementations; the adapter has no mutable per-call state.

## Contents and verification

| Path | Responsibility |
|---|---|
| `src/main/java/com/iocextractor/adapter/out/events/spring/` | Publisher and package Javadoc |
| `src/test/java/com/iocextractor/adapter/out/events/spring/` | Stub-based failure contracts and real Spring-context integration |
| `pom.xml` | Reactor dependencies; publication preparation remains pending |

`SpringControlEventPublisherTest` checks input rejection, reference ownership,
publication/observation failures, suppression boundaries, direct/wrapped Errors
and a deliberately throwing logger. `SpringControlEventPublisherIT` exercises
ordered annotated listeners, partial delivery, fatal propagation and
service-owned dispatch configuration in a real Spring context.

See the [integration guide](../../docs/guides/events-library.md),
[IOC coordination flow](../../docs/dev/event-coordination.md) and
[architecture decision](../../docs/ADR/0047-explicit-spring-control-event-adapter.md).
