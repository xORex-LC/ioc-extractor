# LIB-5: local control-event library proposal

Status: implementation resumed by the owner, 2026-10-24. E0 technical analysis
is complete; E1 qualifies the existing JDK contracts. E2 bridge extraction and
its three required corrections, E3–E5 and publication remain pending.
Baseline: `r030-libraries`, `d98bf415377bbcd86e43f04dcbd21c5fefe96b34`.

Workflow checkpoint, 2026-10-18: the owner closed the current design stage and
selected diagnostics (LIB-2) as the next implementation target. Events E1–E5
are deferred until that work is complete; E1 is not the next active task.
The boundary and E0 findings form the retained implementation plan. This is
neither an accepted numbered ADR nor artifact/API or publication admission.

Workflow update, 2026-10-24: after configuration publication, the owner selected
events for reuse in future services and revoked the earlier deferral. Resume the
existing two-unit plan with E1. ETL remains deferred. The pending configuration
publication-tool corrections stay separate; integrate them before extending
publication tooling for events. This selection authorizes implementation, not an
immediate registry publication or a claim of a second deployed consumer.

## Purpose and confirmed requirements

Share the existing local-event metadata and publication tools across services.
The [assessment](lib-5-events-analysis.md) and
[discussion](shared-library-exploration.md) record confirmed owner requirements:

- Services define their own concrete events and payload invariants.
- Shared metadata carries event identity, type/version, occurrence time,
  operation correlation and optional immediate causation.
- Best-effort notification is useful: a notification failure need not turn a
  completed producer operation into failure. This does not require every future
  adapter to hide every kind of error.
- Reuse the existing Spring publisher while keeping the contracts framework-free.
- Each service owns listeners, recovery and authoritative durable state.
- Each service configures isolation between its listeners with Spring tools.
  The shared adapter does not guarantee that all handlers are attempted.

The purpose is local coordination. No broker, subscriber registry, delivery
ledger, wire format, retries, automatic tracing or Spring starter is proposed.

## Refresh against the current project

The production sources and POM in `platform/platform-events`, and
`bootstrap/.../SpringControlEventPublisher.java`, have no changes relative to
the original assessment baseline `edca8d69`. Router and storage work therefore
do not invalidate those particular implementation observations. This is not a
claim that all callers, listeners or integration behavior stayed unchanged.

Current composition remains explicit in `EventCoordinationConfig`: the service
provides its logging observer and constructs the Spring adapter. A local helper,
`ControlEventObservations`, also contains failure-observation protection for
handlers. Its existence does not automatically admit another public library API.

As of 2026-10-24 the publication script supports concurrency, diagnostics,
logging and configuration with exact component-specific dependency contracts.
Events still needs explicit admission for both units. The Spring artifact adds
a required project-owned events dependency: E3/E4 must qualify the two-artifact
closure and missing-own-dependency routing, not just change the artifact name.

[E0 qualification](lib-5-events-e0-analysis.md) records the refreshed caller and
dependency inventory, 18 characterization observations and three required bridge
corrections. The [proposed ADR draft](lib-5-events-adr-draft.md) records the
architecture trade-offs for review without changing accepted ADR-0013.

## Proposed modules and public types

Names below are proposals, not admitted coordinates. Retain product/library
lockstep versions and Java 21.

| Unit | Proposed coordinate / location | Main dependencies |
|---|---|---|
| Existing framework-free contracts | `io.github.xorex-lc:ioc-platform-events`, `platform/platform-events` | JDK only |
| Extracted Spring adapter | `io.github.xorex-lc:ioc-events-spring`, `adapters/adapter-events-spring` | Event contracts, Spring Context, SLF4J API |

Keep `com.iocextractor.platform.events` for the existing contracts. Propose
`com.iocextractor.adapter.out.events.spring` for the adapter; this replaces the
initial package sketch so existing ArchUnit adapter-layer rules apply. Avoid
split packages and a dependency on bootstrap. Both are ordinary JARs. No logging
backend or product observability dependency belongs in either published artifact.

The first proposed dependency baseline is Spring Context 7.0.9 and SLF4J API
2.0.18, resolved under IOC's Boot 4.0.8 parent. Consumers do not need Boot or the
IOC parent. Do not claim other Spring versions without consumer qualification.

Retain the seven existing public types: `ControlEvent`, `ControlEventMetadata`,
`ControlEventPublisher`, `ControlEventObserver`, both no-op implementations and
`RecordingControlEventPublisher`. Keep recording support in the same small JAR:
it introduces no extra dependency. Explicitly document it as an unbounded,
in-memory test double holding event references, not a journal or deep snapshot.
Its sequential recording behavior does not promise a global concurrent order.

The Spring unit exposes `SpringControlEventPublisher` with explicit constructor
injection of Spring's publisher and an observer. Service bean composition and
`LoggingControlEventObserver` remain local. No separate generic observer wrapper
or handler-execution API is justified yet.

## Proposed contracts

- Metadata retains existing validation and factory/accessor behavior. IDs are
  supplied by the service; the library generates or propagates none. A positive
  schema version does not establish schema compatibility or CloudEvents support.
- The publication port requires a non-null event. General delivery, exception,
  ordering and timing guarantees must be documented by the implementation.
  Existing IOC wiring must retain ADR-0013's producer-neutral delivery policy.
- The Spring implementation rejects a null event before attempting delivery.
  It contains ordinary `RuntimeException` from publication and observes the
  failure. E0 reproduced Spring wrapping a listener `Error` in
  `UndeclaredThrowableException`; E2 must rethrow that original Error before
  runtime containment, as well as preserve direct Error propagation. Do not
  promise recovery of failures already handled by a service error handler or
  executor. Invalid input is distinct from delivery failure.
- Ordinary return after a Spring publication attempt does not prove delivery
  to any or all handlers, durable completion, or safe repetition. Do not add an
  automatic retry after a partially dispatched event.
- `published` means the underlying publication call returned normally.
  `publishFailed` means that call threw; some handlers may already have acted.
  Correct the existing misleading "before accepting" observer documentation.
- Publication observer callbacks are observational. Preserve containment of
  their runtime failures and the SLF4J fallback. Preserve the publication failure
  as primary fallback evidence, with a distinct observer failure suppressed when
  supported; guard against self-suppression. Runtime failure of fallback logging
  must not escape or trigger recursive logging. Separate publication and success
  observation so telemetry failure cannot become a delivery failure. Do not
  promise a library-wide nonthrowing guarantee for service-owned dispatch callbacks.
- The adapter inherits execution timing from the supplied Spring publisher.
  It owns no executor or shutdown lifecycle. Services own listener thread safety,
  transactions, asynchronous error reporting and recovery.

## Confirmed boundary: isolation between handlers

Producer isolation is already agreed. Handler isolation is a separate property.
With the default synchronous `SimpleApplicationEventMulticaster`, an uncaught
listener exception stops the current multicast. Catching that exception in the
outer Spring publisher cannot resume listeners that were not reached.

Primary references checked 2026-10-17:
[Spring multicaster](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/event/SimpleApplicationEventMulticaster.html#setErrorHandler(org.springframework.util.ErrorHandler))
and [publication hand-off](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/ApplicationEventPublisher.html).
Spring already offers an `ErrorHandler`; behavior depends on whether it returns
or rethrows, as well as executor/listener configuration. An application-wide
multicaster also handles framework and unrelated service events.

Owner-confirmed boundary for the first extraction: retain publication containment and
leave listener-isolation configuration to each service using existing Spring
tools. The library should not silently replace the application's multicaster.
Do not claim that all independent handlers are attempted.

The owner accepted this boundary on 2026-10-17. No shared multicast configuration,
custom dispatcher or per-listener continuation helper is included in v1. Services
may require stronger behavior and configure it explicitly; publication containment
alone does not establish that behavior or replace service-owned recovery.

## Execution and qualification outline

The owner resumed this sequence on 2026-10-24. E1 source refresh found no changes
to the event module or Spring publisher between E0's `d98bf415` baseline and
current HEAD `0764e711`; existing caller/integration evidence remains dated E0.
Refresh dependency and integration evidence again for E2–E4.

| Slice | Result and acceptance evidence |
|---|---|
| E0: finalize contracts | Analysis complete: dependency/caller inventory and 18 characterization observations; three bridge corrections required before E2 admission; see the E0 worknote |
| E1: qualify JDK contracts | Implemented 2026-10-24: seven public types documented, 43 tests, strict Javadoc and exact local guide example; final gate evidence retained below |
| E2: extract Spring bridge | Add the separate adapter and migrate composition; correct wrapped Error propagation, primary failure evidence and fallback logging containment; qualify real Spring context behavior and architecture boundaries |
| E3: qualify publication tooling | Select components explicitly; publish a parent-independent POM with necessary dependencies, sources and Javadoc; retain the existing concurrency release path unchanged |
| E4: independent consumers | Resolve both artifacts outside the reactor with an isolated Maven cache; use unrelated event payloads, a JDK-only consumer and a Spring consumer; verify intended dependency closure and exception boundaries |
| E5: release admission | Record API/version/dependency ownership and supported baseline, final repository gates and documentation; qualify the release destination before any explicit publication action |

E2 must test a listener that acts before another throws, an unreached later
listener under default synchronous settings, observer failures after success
and failure, null input before delivery, direct and Spring-wrapped Error
propagation, same-instance/suppression-disabled failures, and a throwing fallback
logger. Put real Spring context tests in a Failsafe-discoverable integration
suite with the repository's integration tag; keep stub-based tests unit-owned.
Do not introduce async delivery solely to satisfy these tests. Any supported async case
must state that late listener failures cannot be observed by the outer call.
Verify that constructing the adapter does not register or replace a multicaster,
change application-wide error handling, or configure an executor.

No further owner requirement question is currently blocking this proposal.
E0 has resolved the current dependency baseline, inventoried callers/listeners
and specified the corrected observation contracts. E1/E2 must implement and
regression-test these requirements; E4 must qualify the published dependency
closure independently. No configurable failure-policy framework is required.

At implementation time update module/dependency maps, event-coordination docs,
architecture tests and build/test/report scopes. Run focused tests and then
the repository's required verify/PMD gates; review resource ownership if the
event scope grows. A synthetic consumer qualifies independence, not a second
production use case. Existing September test results are historical evidence.

This proposal is based on source/test inspection and primary documentation review.
E0 ran 11 events tests, 3 existing publisher tests and 18 local characterization
scenarios, including four gap observations across three required corrections.
Those observations are not passing API admission. `make docs` and
`git diff --check` passed after the E0 update. No production Java or build
configuration was changed; full verify/PMD evidence remains stale.

## E1 JDK contract qualification — 2026-10-24

The owner resumed extraction on `r030-libraries` at HEAD `0764e711`. The existing
seven public types, method signatures and validation behavior remain intact.
The recorder's existing public no-argument constructor is now explicit so its
documentation is included. No Maven coordinate, dependency, publication selector,
analyzer membership or coverage floor changes in E1.

Javadoc now states the concrete metadata rules: non-blank required text, nullable
causation, unchanged surrounding whitespace, positive service-chosen version and
non-null timestamp without clock validation. IDs and correlation remain supplied
by the producer. Publication outcomes do not imply all-handler delivery;
`publishFailed` may follow handlers that already acted. Observer interfaces do
not promise callback containment or listener isolation.

The no-op contracts specify argument validation and no metadata reads. The
recording fixture explicitly retains duplicates and original event references,
returns immutable shallow snapshots, has no capacity bound and promises no
invocation-time order for overlapping calls. The concurrent test uses a timed
barrier, bounded future waits, a containing timeout and asserted worker shutdown.

Verification:

- `make test-module MODULE=platform/platform-events`: 43 tests passed, no skips;
  `.dev/lib5-e1-tests.log` retains the focused result.
- JDK 21 `javadoc -Werror -Xdoclint:all`: passed for all seven public types and
  package documentation. `.dev/lib5-e1-javadoc/` contains the generated result.
- The exact Java block from the new
  [consumer guide](../../../guides/events-library.md) compiled with
  `--release 21 -Xlint:all -Werror` and ran against the locally compiled module,
  printing `collector.refresh-requested`. Log: `.dev/lib5-e1-documentation.log`.
  This is local example evidence, not a packaged/cold repository consumer.
- Final worktree commands are `make verify`, `make pmd-analysis`, `make docs`
  and `git diff --check`; retain their outcomes in `.dev/lib5-e1-verify.log`,
  `.dev/lib5-e1-pmd.log` and `.dev/lib5-e1-docs.log`. The ignored handoff records
  results and freshness separately from historical E0/RC evidence.

Next is E2: extract `ioc-events-spring`, migrate explicit IOC composition, and
fix/qualify ARCH-4, OBS-7 and OBS-8 with real Spring context tests. None of these
Spring fixes is claimed by E1. E3–E5 must still admit coordinates, dependency
closure, standalone POMs and outside-reactor consumers before publication.
