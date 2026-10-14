# LIB-5: local control-event contract assessment

Status: initial technical assessment; owner confirms local-event reuse interest;
Spring adapter reuse confirmed; technical design pending, no extraction or
publication admission. Date: 2026-09-16.
Baseline: `edca8d696423d7c2b1fd266666a37fa5f5108286`.
Branch: `r030-events-analysis`, based on the remote release branch. ETL work stays
on its separate pushed branch; no merge was performed to start this analysis.

## Assessment

`platform-events` is a credible candidate for sharing a small application-facing
contract. It has no declared runtime dependencies, IOC event payloads or Spring
imports. Its value is standardizing metadata and keeping publication behind a
framework-free port, not implementing delivery. Small size does not disqualify a
library if multiple services intentionally share its contract.

The owner confirms that the described local-event scenario is useful in future
services. A second implementation remains absent; the owner accepts best-effort
notification as a useful scenario, without requiring it for all adapters. The inventory's formal `deferred-second-consumer` status is not publication
admission; this worknote now records confirmed reuse interest. Do not promise reliable
inter-service messaging or grow a broker abstraction to make the module useful.

## Existing instruments and ownership

| Type | Existing behavior | Limit |
|---|---|---|
| `ControlEvent` | Common event interface exposing metadata | Event payload and invariants are service-owned; the interface cannot enforce bounded/immutable data |
| `ControlEventMetadata` | Immutable id, type, positive schema version, time, correlation id, optional causation id; validates required text/time/version | No id generation, uniqueness check, schema registry, wire format or trace-context propagation |
| `ControlEventPublisher` | Functional publish-only port | No subscribe, acknowledgement, retry, result or durability API |
| `ControlEventObserver` | Publication and handler-dispatch observation hooks | No subscriber registration or handler execution; callbacks must avoid business side effects |
| No-op publisher/observer | Validate basic arguments and perform no work | Successful return does not prove delivery |
| `RecordingControlEventPublisher` | Thread-safe list recording with immutable list snapshots | Test double; unbounded references and copy-on-write costs, no deep event snapshot or durable history |

Sources: [module](../../../../platform/platform-events/README.md),
[contracts](../../../../platform/platform-events/src/main/java/com/iocextractor/platform/events/ControlEventPublisher.java),
[metadata](../../../../platform/platform-events/src/main/java/com/iocextractor/platform/events/ControlEventMetadata.java),
[recording double](../../../../platform/platform-events/src/main/java/com/iocextractor/platform/events/RecordingControlEventPublisher.java).

The two direct consuming modules are `ioc-application` and `ioc-app` bootstrap.
Unlike ETL's single extraction assembly, six current application event records
support several coordination flows: `SliceCompleted`, `RemoteChangeBatchDetected`,
`CanonicalArtifactsChanged`, `CanonicalDeadlineScheduleChanged`,
`MutableArtifactProjectionRequired`, and `ImportDeliverySnapshotPinned`.
These show intra-project reuse, not six independent external consumers.

The Spring publisher, listeners, logging observer, work admission, ledgers and
periodic recovery are outside the module. In particular, the library itself
neither catches publication failures nor runs a recovery loop.

## Actual execution and loss semantics

[ADR-0013](../../../ADR/0013-event-driven-coordination.md) and the
[capability document](../../../dev/event-coordination.md) explain the live flow. The existing policy is fire-and-observe: delivery failures
must not reverse a completed producer operation. Periodic reconciliation reads
authoritative state and recovers work when hints are lost or duplicated.

[SpringControlEventPublisher](../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SpringControlEventPublisher.java)
validates a non-null event, calls Spring, observes publication and catches runtime
delivery failures. Observation failures are also guarded. Successful return says
nothing about whether the downstream work has completed. Spring listeners such
as [SliceCompletedPublishListener](../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/SliceCompletedPublishListener.java)
submit work to consumer-owned executors; rejection may shed a hint to reconcile.

For example, completed export storage is the fact; the event accelerates delivery.
The publish ledger and completed-slice reconciliation recover a lost acceleration.
This does not implement a durable command such as “apply this firewall rule.”
Even if future broker adapters use a similar Java port, transaction boundaries,
wire compatibility, deduplication and delivery guarantees require separate review.

## Contract seams before any external API is frozen

1. **Delivery-failure behavior is underdocumented in the Java port.** ADR-0013
   defines producer-neutral publication, but `ControlEventPublisher.publish`
   Javadoc merely says it publishes through an adapter. An external adapter could
   propagate delivery failures and change post-commit producer behavior. Carry
   the existing behavioral requirement into API docs and contract tests before
   admission; do not silently equate it with rejection of invalid arguments.
2. **“Published” and “failed before acceptance” need precise meanings.** The
   observer's `publishFailed` comment describes failure before acceptance. Spring
   synchronous dispatch can run one listener successfully and then fail in
   another. The adapter cannot prove that no listener acted. Therefore failure
   is not evidence that a retry is side-effect-free, and publication success is
   not durable consumer completion. Existing publisher unit tests use a stub;
   they do not qualify partial multi-listener delivery.
3. **Metadata validation is not schema validation.** A positive version is not a
   supported-version check. The interface permits implementations with null
   metadata or mutable payloads. Event constructors/factories must own invariants.
   For example, `SliceCompleted.from` supplies its fixed type/version, whereas
   its public constructor accepts supplied metadata without checking that pair.
   Do not advertise generic schema validation or global event uniqueness.
4. **The observer is an optional integration concern.** It has publication and
   dispatch hooks; publishers need only a subset. That is not by itself grounds
   for multiple artifacts, but its nonthrowing/containment expectations need an
   explicit contract. The Spring publisher protects its callbacks; not every
   listener callback has identical protection. A custom throwing observer can
   prevent a fast-path handler from reaching work, leaving recovery to reconcile.
5. **Test support is not an event journal.** Decide whether the recording double
   belongs in the supported public API. Its concurrent appends are safe, but it
   does not define global producer order, bound memory or clone event payloads.

No critical production incident was established. These are admission limits and
adapter semantics, not authorization to change runtime behavior. Retain the
anti-broker boundary and the framework-free dependency direction.

## Comparison with existing tools

Primary references checked 2026-09-16:

- [Spring ApplicationEventPublisher](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/ApplicationEventPublisher.html)
  already publishes arbitrary objects to matching listeners. Publication is a
  hand-off, not a guarantee of execution timing. Our port adds application-owned
  metadata and decoupling; it does not replace the Spring dispatcher.
- [Spring SimpleApplicationEventMulticaster](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/SimpleApplicationEventMulticaster.html)
  defaults to calling-thread listeners; without an error handler, a listener
  failure stops the current multicast and propagates. This supports the partial
  delivery caveat above, not an assertion that every adapter must be synchronous.
- [CloudEvents 1.0.2](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md)
  standardizes event context for interoperability. It requires `id`, `source`,
  `specversion`, and `type`; source plus id scopes uniqueness. Our record has no
  source or CloudEvents specversion, and its eventVersion is a payload schema
  version. It is not CloudEvents-compatible by naming similarity. CloudEvents
  itself does not supply reliable delivery. Consider a deliberate mapping only
  if a real wire boundary needs it; do not add it to the local module now.

For Spring-based services, a local publication port is still reasonable when
application code must remain framework-free. Whether those ports should be the
same shared Java artifact depends on common metadata and failure semantics.
Direct Spring use in adapter-owned code is also viable; a new library must earn
its compatibility cost rather than merely wrap `publishEvent`.

## Possible boundary and next discussion

If reuse is confirmed, the existing event interface, metadata and publish-only
port are the cohesive starting point. Review observer/test-double admission
separately within that same candidate; do not add subscriber APIs or queues.
Keep concrete IOC events, Spring wiring, logging fields, executors, ledgers and
recovery local. Existing publication scripts still select concurrency explicitly;
JDK-only dependency closure reduces packaging work but does not make the current
module automatically publishable. Artifact naming and API admission remain open.

**Owner question:** Should future services use a common way to announce local
facts and connect them to an operation (for example, “prepared data is available;
other components may react”), while each service defines its own event payloads
and any recovery mechanism? Or is the intended need specifically delivery of
commands/events between separate services? These needs must be distinguished
before deciding on the current module's value; both may coexist.

## Verification

Read all eight production Java files (including package-info), all three module
test classes, the POM, Spring publisher/test, event configuration, observer and
representative application events/listener, ADR-0013 and event-coordination docs.
`make test-module MODULE=platform/platform-events` passed: 11 tests, zero
failures/errors/skips. Spring publisher tests were inspected, not rerun, and no
broker/external-delivery test was performed. No production/build changes; full
verify/PMD were not run and their existing evidence remains stale.

## Confirmed scope and proposed dependency boundary

The owner confirmed:

- Shared local event conventions are useful in future services.
- Notification failure need not turn a completed producer operation into failure;
  best-effort is a supported scenario, not mandatory behavior for every adapter.
- Both operation correlation and optional immediate causation are useful.
- The existing Spring publisher adapter belongs in the reusable candidate scope.

Propose two dependency units for subsequent design, not an arbitrary
API/implementation split:

| Unit | Existing code to assess for admission | Allowed main dependencies |
|---|---|---|
| Framework-free event contracts | Event, metadata, publish port, observer, no-op implementations; decide separately whether recording test support is public | JDK only |
| Spring publication adapter | Existing `SpringControlEventPublisher`, including contained observation of publication failures | Event contracts, Spring Context and SLF4J API |

The adapter's only implementation imports are event contracts, Spring's
`ApplicationEventPublisher`, SLF4J and JDK. It has no IOC logging fields or concrete
application event imports. Its fallback `log.warn` calls report an observer that
itself failed; preserve or explicitly replace that behavior during design. Do
not add the product observability module, LIB-3 or a logging backend dependency.

Service-owned code remains concrete events and their factories, metadata value
assignment/propagation, `LoggingControlEventObserver`, bean wiring, listeners,
work admission, recovery and durable state. There is no new starter, subscriber
registry, broker or automatic history/correlation propagation requirement.

The confirmed scope is sufficient for a technical proposal. Remaining work is
precise failure/partial-dispatch semantics, observer and invalid-input contracts,
minimal public API and test-support disposition, IOC migration, dependency-aware
publication qualification and independent consumer tests. Existing stub-based
Spring tests do not establish all those guarantees. Coordinates, versions of
supported Spring dependencies and release ownership are not yet admitted.
No production extraction or publication is authorized by these scope answers.
