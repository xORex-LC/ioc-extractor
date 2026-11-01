# Publish local control events through Spring

[Русская версия](../ru/libraries/events-spring.md) · [JDK contracts](events.md) ·
[Library catalog](README.md)

`ioc-events-spring` adapts `ControlEventPublisher` to a service-supplied
`ApplicationEventPublisher`. It attempts publication once, observes the result
and contains ordinary runtime publication/telemetry failures under explicit
rules. Delivery can already be partial when publication fails.

The service owns the context, listeners, multicaster, error handler, executor,
logging provider, transactions and recovery. The adapter registers no beans,
installs no execution policy and owns no resource or shutdown operation.

## Dependency and qualification

Use Java 21 and the same core/adapter version:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-events-spring</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

The parent-independent POM requires `ioc-platform-events` at exactly the same
version, Spring Context 7.0.9 and SLF4J API 2.0.18. It supplies no Boot dependency,
starter, logging provider or auto-configuration. A provider is needed if fallback
WARN output must be visible. The qualified isolated consumer checks default
mediation and a Boot 4.0.8 application BOM; the BOM adds no Boot runtime.
The adapter itself does not enforce dependency versions at runtime.

Release `0.3.0-rc.1` is published in Central and GitHub Packages. Separate cold
consumers passed for each in
[run 40162675262](https://github.com/xORex-LC/ioc-extractor/actions/runs/40162675262),
including both artifacts' hashes/origins. See the
[release record](../../../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md)
and [repository settings](../library-publication.md#consumption). Other Spring/Boot
baselines and a second deployed service remain outside qualification.

The Java implementation matches tag `ioc-events-spring-v0.3.0-rc.1`;
the reactor remains at `0.3.0-SNAPSHOT`. Current POMs/publication stay lockstep;
the accepted [development policy](development.md) gives the events family an
independent version after technical activation, preserving exact core/adapter
version equality.
The public package remains `com.iocextractor.adapter.out.events.spring`.

## Construct the adapter explicitly

The sole public type is `SpringControlEventPublisher`, with two public operations:

| Operation | Contract |
|---|---|
| `SpringControlEventPublisher(ApplicationEventPublisher, ControlEventObserver)` | Retain the supplied delegate/observer without configuring them; null arguments cause `NullPointerException` |
| `void publish(ControlEvent)` | Reject null before side effects; pass the same reference to Spring once without reading/copying metadata or payload |

The logger-injection constructor used by tests is package-private, not public API.
A service chooses its observer; `NoopControlEventObserver.INSTANCE` is suitable
when these observations are intentionally discarded. Handler-level
`dispatching`/`dispatchFailed` callbacks are not invoked by this adapter.

Save `SpringEventsExample.java` in `src/main/java/` of a Java 21 consumer with
the dependency above, then run its `main` method. It registers an unrelated
service listener and closes the context it owns.

```java
import com.iocextractor.adapter.out.events.spring.SpringControlEventPublisher;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.NoopControlEventObserver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.time.Instant;

public final class SpringEventsExample {
    public record RefreshRequested(ControlEventMetadata metadata, String sourceId)
            implements ControlEvent { }

    public static final class RefreshListener {
        @EventListener
        public void refresh(RefreshRequested event) {
            System.out.println(event.sourceId());
        }
    }

    public static void main(String[] args) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RefreshListener.class);
            context.refresh();
            var publisher = new SpringControlEventPublisher(context, NoopControlEventObserver.INSTANCE);
            publisher.publish(new RefreshRequested(ControlEventMetadata.withoutCausation(
                    "refresh-17", "collector.refresh-requested", 1, Instant.EPOCH, "poll-6"), "feed-a"));
        }
    }
}
```

Expected standard output is `feed-a`. In this default synchronous context the
listener runs before the call returns. The service owns the concrete event's
payload validation; the adapter cannot validate the sample record for it.
IOC constructs the same adapter in its composition root with a logging observer.
No publisher bean or listener is installed merely by calling the constructor.

## Read the failure boundary precisely

| What the delegate does | Adapter behavior |
|---|---|
| Returns normally | Invoke `observer.published(event)`; this also applies with no matching listeners or after asynchronous hand-off |
| Throws an ordinary `RuntimeException` | Invoke `observer.publishFailed(event, originalFailure)` and normally return; no retry |
| Throws an `Error` directly | Propagate that original Error; no publication callback |
| Throws `UndeclaredThrowableException` whose immediate undeclared throwable is an `Error` | Propagate the original Error, not the Spring wrapper; no publication callback |
| Throws another runtime wrapper | Preserve its identity as a runtime publication failure; do not search arbitrary cause chains |

An observed runtime failure is contained, not converted into a reliable success
or a safe retry signal. Listeners may already have committed effects. A normal
return also does not prove that any/all handlers completed or durable work exists.
Keep idempotency, transactions, durable ledgers and reconciliation in the service.

## Keep telemetry failure separate from delivery

- If `published` throws a runtime exception after normal delegate return, issue
  one best-effort WARN for that observer failure. Do not call `publishFailed`
  or retry the already completed publication.
- If `publishFailed` throws a runtime exception, use the original publication
  failure as the WARN's primary throwable. Add the distinct observer failure as
  suppressed when enabled; avoid self-suppression. A suppression-disabled
  throwable retains only primary evidence.
- A runtime failure of the final fallback logger is contained with no recursive
  logging or retry. Direct Errors from observation or fallback logging propagate.

Observer callbacks execute on the publishing caller's thread. The underlying
publisher may dispatch work elsewhere. Exception objects/text can reach the
logging backend and suppressed evidence mutates the original throwable; the
service owns safe exception content and disclosure. There is no guarantee that
an observer or broken backend actually records the failure.

## Choose listener execution in the service

With the qualified default synchronous
[Spring multicaster](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/context/event/SimpleApplicationEventMulticaster.html),
an uncaught listener failure stops that multicast, so later listeners may not
run. Configure an application-owned error handler when continuation is required.
If that handler consumes a failure, the outer publisher can only observe normal
return. Errors occurring after asynchronous submission are likewise outside the
outer call. This is a deliberate observation boundary, not handler isolation.

The adapter has final dependencies and no mutable per-call state. Concurrent
callers still require a thread-safe delegate and observer; listener safety,
ordering, blocking, cancellation and worker cleanup depend on service settings.
Metadata correlation does not automatically propagate MDC or tracing context.

## Sources, tests and maintenance

- [Module reference](../../../adapters/adapter-events-spring/README.md),
  [source/package Javadoc](../../../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/)
  and [ADR 0047](../../ADR/0047-explicit-spring-control-event-adapter.md) define
  ownership. The [JDK guide](events.md) documents metadata and callback inputs.
- [Unit and real-context tests](../../../adapters/adapter-events-spring/src/test/java/com/iocextractor/adapter/out/events/spring/)
  cover nulls, original identities, suppression limits, observer/logger failures,
  partial annotated-listener delivery, wrapped Error and service configuration.
  Run `make test-module MODULE=adapters/adapter-events-spring` and
  `make test-integration-module MODULE=adapters/adapter-events-spring`.
- [Standalone Spring consumer](../../../tools/fixtures/events-spring-consumer/README.md)
  compiles this Java block and the JDK example on packaged JARs. It qualifies
  both dependency graphs, excluded/unavailable dependencies, incompatible Spring,
  missing adapter/core routing and eight exact primary hashes/origins.
- [Publication contracts](../../../tools/tests/library-publication-test.py)
  enforce EN/RU Java/XML parity for both guides: `make library-publication-test`.
  Run `make docs` for links. [IOC coordination](../../dev/event-coordination.md)
  explains service-owned backstops and composition.

Update both languages and independent consumers when these behaviors change.
Core-first protected publication and immutable two-artifact recovery are covered
by the [publication procedure](../library-publication.md#events-api-and-version-ownership).
No package relocation, wider framework baseline, all-handler async completion
or second-service deployment is qualified by this guide.
