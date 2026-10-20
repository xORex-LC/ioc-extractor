# ADR draft: reusable SLF4J logging tools and a small ECS profile

Status: **Proposed**, L3-0 contract fixed on 2026-10-19. This unnumbered draft is
an implementation target, not a published accepted ADR. Assign the next available
ADR number and promote after implementation qualification; do not reserve a
number or edit accepted ADR-0018/0019 to describe this future implementation.

L3-1 now implements the proposed eight-type API locally, including its Javadoc,
README and executed consumer guide. L3-2 now wires IOC through delegating facades
and qualifies the selected producer wire corrections; this draft stays
Proposed until integration qualification and ADR promotion in L3-3.

## Context

Future project services need the same way to restore MDC, emit typed structured
fields and prepare selected sensitive values for logs. The IOC implementation
already supplies these mechanics but couples them to an IOC field/action enum
and an ETL observer. Publishing that module would export service dependencies
and vocabulary.

The owner also requested a common ECS approach. A generic key/value facade alone
cannot provide that: the inspected service writes invalid `event.type` values,
and an explicit error type combined with a cause can make the actual Boot
encoder drop a record. Backend metadata, MDC and dotted paths have ownership
constraints that a scalar normalizer does not enforce. The code-backed
[assessment](lib-3-logging-ecs-analysis.md) and
[L3-0 probe](l3-0-probe/README.md) retain this evidence.

## Decision

Create `io.github.xorex-lc:ioc-platform-logging-slf4j`, a Java 21 library with only
SLF4J API as a required main dependency. Keep product/library versions lockstep.
Use package `com.iocextractor.platform.logging` with `ecs` and `redaction`
subpackages. The repository owner maintains and releases the artifact.

The [L3-0 contract](lib-3-logging-contract.md) fixes the eight public types and
observable rules. Services supply immutable custom field descriptors. Event
identity is the field key, with one declared type retained even after null
omission. Strict representation errors still throw before disabled-level
filtering. Scopes restore touched keys on the same thread in LIFO order; workers
reconstruct context explicitly. Resource acquisition precedes scope population.

Include five shared ECS scalar descriptors, outcome wire vocabulary and
action/outcome/nanosecond-duration conveniences in the same artifact. Exclude
optional type/category arrays until a consumer justifies their additional API.
Generic fields validate representation, while helpers and service schema tests
establish the supported semantics. This is a small profile, not full ECS
validation or a universal field catalog.

Include a no-input constant full mask and the existing precisely documented URL
component transformation. The service selects values to hide; there is no
automatic scrubbing, severity bypass, generic fingerprint facility or promise
that messages/causes are sanitized.

Leave the IOC field/action catalog, diagnostic policy, ETL observer and backend
configuration local. Initial Boot/Logback integration is a qualified application
recipe with explicit metadata/dataset/error ownership. A separate backend
artifact requires a real second consumer. Do not add a provider, starter,
tracing/context framework or new dependency in core business modules.

During L3-2 move internal event names to `ioc.event.type`, represent the two
informational skipped operations with `ioc.operation.disposition` and no outcome,
and let the encoder own `error.*` when a Throwable is supplied. These are explicit
service wire corrections, documented and tested separately from mechanical
delegation. OBS-9/10 close only after implementation regressions pass.

## Alternatives considered

| Alternative | Reason for rejecting/deferment |
|---|---|
| Publish `platform-observability` unchanged | Exports IOC vocabulary, ETL coupling and unqualified standard-field behavior |
| Use raw SLF4J only | Does not supply restoring scopes, field representation checks or shared field meanings |
| Generic facade without ECS conventions | Leaves each service to rediscover standard semantics and encoder collisions |
| Full ECS model and separate profile/backend artifacts now | No demonstrated need for a universal schema, array surface or extra artifacts; adds compatibility obligations before a second consumer |
| Share Boot/Logback as main dependencies | Forces a provider/framework into consumers of otherwise generic mechanics |
| Treat null as masking or silently stringify every type | Ambient MDC may expose an omitted value; coercion changes documented JSON field types |
| Handle every logging error automatically | Changes the owner's explicit strict-error decision and OBS-6 scope |

## Consequences and acceptance

New custom fields require service catalog changes, not edits to the library.
Applications retain ownership of schemas, reserved paths, disclosure and provider
selection. The selected baseline is SLF4J 2.0.18 with Logback 1.5.38 / Boot 4.0.8;
broader compatibility must be evidenced rather than inferred.

Local IOC wrappers may delegate for compatibility but must not retain independent
implementations. The new descriptor/storage contract and String-only MDC API need
actual module tests. Documentation is part of L3-1: useful English Javadoc,
package docs, README and executed examples, followed by cross-module integration
documentation in L3-3.

L3-0's 62-case characterization passes include expected defects and do not qualify
the future library. L3-1–L3-3 must supply actual API/migration tests and final
verify/PMD evidence. L3-4 must qualify a resolved standalone POM with only SLF4J,
retain existing zero-dependency publication guards, consume from a cold cache
and obtain publication admission. Existing published versions/tags remain immutable.
