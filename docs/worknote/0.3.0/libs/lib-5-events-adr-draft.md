# Draft ADR: extract local event contracts and an explicit Spring bridge

## Status

Proposed, 2026-10-17; unnumbered library worknote for review. The owner confirmed
the local-event/Spring scope and service ownership of handler isolation. API,
coordinates, implementation and release admission remain proposals. Promote to
the next available numbered ADR only when the extraction decision is accepted.
Existing ADR-0013 is not edited or superseded by this draft.

Workflow checkpoint, 2026-10-18: events design is closed for the current
exploration stage; implementation is deferred while diagnostics is extracted.
This draft remains proposed and unnumbered.

Workflow update, 2026-10-24: the owner resumed the existing extraction plan.
E1 qualifies the JDK contracts; E2–E5 and publication admission remain pending.
This draft is still unnumbered; implementation selection does not promote it.

Workflow update, E2 on 2026-10-24: the owner authorized the separate Spring
adapter and the three corrections. The implemented ownership and failure
boundary is recorded in accepted [ADR 0047](../../../ADR/0047-explicit-spring-control-event-adapter.md).
This draft remains historical design context; publication admission is pending.

## Context

IOC Extractor already separates local event contracts from Spring delivery.
Future services need the same metadata and producer-neutral notification tools
while owning their events and recovery. The published concurrency pilot proves
the packaging path, but its dependency-free checks cannot qualify Spring
artifacts unchanged. [E0](lib-5-events-e0-analysis.md) documents the refreshed
dependency closure, integration inventory and three bridge corrections.

## Proposed decision

Publish two cohesive dependency units: JDK-only `ioc-platform-events` with its
existing seven public types, and `ioc-events-spring` with the explicitly
constructed publisher. Keep Java 21 and lockstep versions. Use the existing
contracts package and an adapter-layer package for the Spring bridge.

Keep payloads, IDs, concrete logging/diagnostics, listeners, executors, durable
state and recovery service-owned. The Spring bridge contains ordinary runtime
delivery and telemetry failures; preserve fatal Error propagation including
Spring's direct reflection wrapper. Correct observer failure evidence and
fallback containment before admitting the bridge.

Services use existing Spring facilities to choose handler-isolation behavior.
The library neither replaces the multicaster nor introduces a subscriber API,
broker, serialization, retry loop, starter or auto-configuration.

## Alternatives considered

- **Publish the current contracts alone.** Smallest packaging change, but does
  not share the specifically requested Spring containment behavior.
- **One Spring-dependent JAR.** Fewer coordinates, but forces Spring onto callers
  that need only framework-free contracts. Dependency separation justifies two
  artifacts without splitting every interface from its implementation.
- **Direct Spring publication everywhere.** Avoids a shared API, but introduces
  Spring into application code or duplicates the existing bridge and metadata.
- **Keep all code internal.** Avoids public compatibility obligations; remains
  appropriate until independent packaging and API admission checks pass.
- **Shared multicast configuration or a custom dispatcher.** Could offer broader
  handler isolation, but changes application-wide event behavior or grows a
  subscriber framework. The owner accepted service-owned Spring configuration.

## Consequences and trade-offs

Application consumers stay independent of Spring, and services can reuse a
small existing integration. Two artifacts require dependency-aware publication,
coordinated versions, support ownership and external consumer checks. The first
proposed Spring baseline is 7.0.9/SLF4J 2.0.18; wider compatibility is not inferred.

Partial or lost notifications remain possible. Synchronous listeners may block
publication; asynchronous failures may occur after it returns. Services must
choose optional reactions or recover from authoritative state. No queue capacity,
throughput, durability or transactional fan-out guarantee is introduced.

Observation and fallback logging can expose exception text and cannot guarantee
delivery during a logging outage. Services own safe exception construction and
their logging backend. Recording support is shallow and unbounded test support,
not an operational history store. These costs remain explicit in the API docs.

## References

- [Events proposal](lib-5-events-design.md)
- [E0 qualification](lib-5-events-e0-analysis.md)
- [ADR-0013](../../../ADR/0013-event-driven-coordination.md)
- [Concurrency publication guide](../../../guides/library-publication.md)
