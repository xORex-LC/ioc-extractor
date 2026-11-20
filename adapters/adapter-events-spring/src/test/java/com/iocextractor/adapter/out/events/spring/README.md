# Spring control-event publisher tests

## Purpose and structure

Contracts for [the production publisher](../../../../../../../../main/java/com/iocextractor/adapter/out/events/spring/README.md).
Synthetic refresh events and observers use no IOC domain/configuration or remote
service. Contexts are test-owned and close through try-with-resources. There are
no local resource fixtures or logging-provider requirements.

| File | Responsibility |
|---|---|
| [SpringControlEventPublisherTest.java](SpringControlEventPublisherTest.java) | Surefire: null rejection, original-reference delivery, runtime/Error distinctions, observer suppression and throwing fallback logging |
| [SpringControlEventPublisherIT.java](SpringControlEventPublisherIT.java) | Failsafe with `@IntegrationTest`: real annotated listeners, partial synchronous dispatch, Spring-wrapped Error propagation, service-owned continuation and no-listener behavior |
| [PublisherFixture.java](PublisherFixture.java) | Synthetic event, recording observer, callback failure hooks and a proxy fallback logger |

## Assertions and evidence limits

The fixture observer rejects per-handler dispatch callbacks; the publisher must
observe publication only. The logger proxy rejects unexpected methods and records
exact primary/suppressed throwables. Preserve exception identity, one delegate
attempt, callback selection and absence of fallback logging when observation
succeeds. A contained observer failure must not turn a successful publication
into a delivery failure or trigger a retry.

The IT uses real `@EventListener` methods and ordered handlers. Default synchronous
dispatch demonstrates that earlier handlers may finish before a failure stops
later handlers. An application-supplied error handler demonstrates continuation,
while the outer publisher observes normal return. No listeners also yields normal
publication. Construction/publication must not add beans or replace the existing
multicaster.

The custom executor in the IT records tasks and runs them inline. It creates no
worker and does not qualify asynchronous scheduling, context propagation,
all-handler completion, cross-thread cleanup or concurrent delegate safety.
These remain service responsibilities. No local test establishes durable delivery
or rollback of handler effects.

## Verification and related docs

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-events-spring
make test-integration-module MODULE=adapters/adapter-events-spring
```

The module has one Surefire suite and one Failsafe suite. Full acceptance uses
`make verify`; [TESTING.md](../../../../../../../../../../../docs/TESTING.md) owns test lifecycle and worker rules.
The separate [standalone consumer](../../../../../../../../../../../tools/fixtures/events-spring-consumer/README.md)
checks actual packaged guide examples, core/adapter dependency mediation, routing
and artifact identity. Local source tests do not substitute for repository or
second-service qualification; original publication evidence stays in the release
record linked by the module README.
