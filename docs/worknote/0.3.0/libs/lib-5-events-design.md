# LIB-5: local control-event library proposal

Status: implementation resumed by the owner, 2026-10-24. E0 technical analysis
is complete; E1 qualifies the existing JDK contracts. E2 bridge extraction and
its three corrections are implemented; E3 packaging and E4 isolated consumers
are described below. E5 and publication remain pending.
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

At the pre-extraction assessment, production sources and POM in
`platform/platform-events`, and `bootstrap/.../SpringControlEventPublisher.java`,
had no changes relative to
the original assessment baseline `edca8d69`. Router and storage work therefore
do not invalidate those particular implementation observations. This is not a
claim that all callers, listeners or integration behavior stayed unchanged.

Composition remains explicit in `EventCoordinationConfig`: the service
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
| E2: extract Spring bridge | Implemented 2026-10-24: reactor-local adapter and IOC composition migration, corrected Error/evidence/logging boundaries, unit and real-context contracts; qualification below |
| E3: qualify publication tooling | Implemented locally 2026-10-24: two standalone POMs, explicit packaging selectors, exact dependency contracts, real archives and manifest/recovery checks; consumer/signing/publication admission remains blocked until E4 |
| E4: independent consumers | Implemented 2026-10-25: isolated JDK/Spring fixtures, exact guide sources, both own manifests/origins, complete graphs, dependency negatives/mediation, missing-target and missing-core routing probes/controls; qualification below |
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


## E2 explicit Spring adapter — 2026-10-24

The owner authorized E2 at clean `6d00753e` on `r030-libraries`. The publisher
moves from bootstrap to `adapters/adapter-events-spring`, package
`com.iocextractor.adapter.out.events.spring`. It exposes one public class and
one public two-argument constructor. The package-private logger constructor is
only a deterministic test seam; it is not another public logging API.
[ADR 0047](../../../ADR/0047-explicit-spring-control-event-adapter.md) records
the accepted ownership and failure boundary without changing ADR 0013.

The E2 coordinate remains `com.iocextractor:ioc-events-spring:${revision}`;
contracts retain their existing reactor coordinate. E3 must qualify both
`io.github.xorex-lc` coordinates, parent-independent POMs and publication
selection together. No event publication selector is added in E2.

The main dependency tree was refreshed: contracts, Spring Context 7.0.9 and
SLF4J API 2.0.18 are required compile dependencies; Context brings AOP, Beans,
Core and Expression 7.0.9, Micrometer Observation/Commons 1.16.7, Commons Logging
1.3.6 and JSpecify 1.0.1. There is no Boot or logging provider in the main
closure. The test-only TCK supplies the integration annotation. This is a
closure audit of the existing baseline, not a new vulnerability or latest-version
qualification.

`EventCoordinationConfig` explicitly constructs the extracted adapter with the
IOC observer. Listeners, `ControlEventObservations`, payloads, durable state,
executors and recovery stay service-owned. No multicaster, bean registration,
error handler, asynchronous dispatcher or lifecycle is added by the adapter.

The corrections close ARCH-4, OBS-7 and OBS-8:

- Direct Error and Error immediately wrapped in Spring's
  `UndeclaredThrowableException` escape as the same Error. Other runtime wrappers
  keep their identity; no arbitrary causal-chain traversal is performed.
- Runtime failure observation retains the original publication failure as
  primary WARN evidence. A distinct observer failure is suppressed when supported;
  the same instance and suppression-disabled primary are handled safely.
- Success observation follows the delivery try/catch. The last WARN contains
  runtime logger failure without retries or recursive logging, so telemetry
  failure cannot become publication failure. Direct Error remains visible.

The new production JAR is included in all four report manifests, report ordering
and CPD/PMD roots. Reactor topology is now 31 projects, 26 functional JARs,
25 production coverage groups and 24 local reports. Moving the stub suite keeps
234 fast suites; one new IT suite raises integration to 83 and the deterministic
union to 312. Five property-gated external shells are counted in the integration
inventory and excluded from that deterministic union. No existing
ratchet or fixed floor is lowered. Local regression tests and code examples
qualify the implementation; E4 still owns cold packaged consumers, and E5 owns
release admission. A second deployed service remains unqualified.

Evidence is retained in `.dev/lib5-e2-*.log`; the ignored handoff records final
verification outcomes and exact freshness. Next is E3, then E4/E5. E2 changes
are not automatically committed, pushed or published.


E2 verification record:

- Adapter tests: 16 Surefire cases and 4 Failsafe cases passed, with no skips.
  Module coverage is 34/34 lines, 6/6 branches and zero missed instructions;
  all existing module ratchets and release floors remain intact.
- Strict public Javadoc and both exact guide examples passed. The Spring example
  runs with only shared event/adapter classes and reviewed third-party JARs;
  IOC application/bootstrap, Boot and a logging provider are absent.
- Full reactor checks passed for 31 projects. The initial run's freshness was
  invalidated by documentation edits during execution; its log is retained as
  `.dev/lib5-e2-verify-initial.log`. The final stable-tree run and context are
  recorded separately in `.dev/lib5-e2-verify.log` and the E2 evidence receipt.
- Aggregate coverage: 25 groups and 24 local reports, 27145/29440 lines and
  10301/12009 branches. Test-lifecycle reconciliation: 234 fast suites,
  83 integration suites, five external shells, 312 deterministic-offline suites.
  Skipped external shells are not live external evidence.
- Raw SpotBugs: 137 accepted identities, zero visible findings, analyzer errors
  or missing classes. CPD remains 22/22 groups. Adopted PMD has zero blocking
  findings and 19 unchanged advisory findings (7 CognitiveComplexity,
  3 NPathComplexity, 2 UnusedAssignment, 7 ExcessiveParameterList), no analyzer
  errors and no changed-Java intersections. No finding/count baseline is edited.
- The exception-ownership change triggers `make pmd-watchlist`: 63 CloseResource,
  9 PreserveStackTrace and 2 NcssCount diagnostics, without analyzer errors.
  Its one publisher intersection is PreserveStackTrace on `throw fatal`.
  Disposition: intentional original-Error unwrapping, not a new exception losing
  its origin. Both direct/wrapped identity assertions and the actual annotated
  Spring listener regression require this behavior. Do not retain the reflection
  wrapper instead or attach it back onto its own cause. No suppression or rule
  change is introduced; the watchlist remains advisory.

Final command outcomes, worktree hashes and freshness are retained in
`.dev/lib5-e2-evidence/receipt.json` and its context, after all tracked edits.
No public registry action, live deployed consumer or new vulnerability scan is
claimed by these deterministic offline checks.


## E3 standalone publication packaging — 2026-10-24

Both artifacts now use `io.github.xorex-lc` throughout the reactor. Their source
POMs keep the private parent for development; flattened consumer POMs remove it
and resolve all versions. Existing Java packages and public contracts are
unchanged. The four report POMs, application/bootstrap consumers and both build
verifiers use the new coordinates. Synthetic verifier fixtures admit each new
coordinate and reject its old `com.iocextractor` ordering dependency. No report
scope, floor, ratchet or suite inventory is weakened. The testing map now also
records E2's 83 integration suites (234 fast, five external shells, 312 offline).

Explicit `events` and `events-spring` descriptors prepare separate bundles, each
with its own component identity and four primary hashes. Core remains JDK-only
with no consumer dependencies. The adapter requires exactly the same-version core,
Spring Context 7.0.9 and SLF4J API 2.0.18 as non-optional compile JARs. The same
checks run at preparation and recovery, including embedded-POM identity, MIT
license, Java 21 bytecode and public class/source/Javadoc markers. Extra/private
parents, BOMs, repositories, build configuration, unresolved versions, optional
APIs, a different core version and foreign classes are rejected. Existing four
components retain their exact dependency and immutable-bundle contracts.

There is no multi-artifact upload format change: contracts and adapter each keep
an independent bundle and eventual component tag. Future release order is core
first, adapter second at the same version, qualified separately in each selected
repository. E4 must explicitly assemble the local two-artifact repository and
verify both own artifacts' bytes/origins; third-party APIs still use the reviewed
public routes. No fallback to another own-artifact repository is admitted here.

Until E4 supplies the cold consumers, signing, remote writes and consume calls
for either new selector fail before credential access or network side effects.
An empty fixture list cannot become a successful consumer result. The protected
workflow exposes only the four previously consumer-qualified selectors; its
option list is regression-tested against the admitted descriptors. Regular CI
adds one credentials-free job that builds both modules together and prepares
the two bundles; the existing library gate requires it alongside the four
consumer jobs. This job does not claim cold events consumption. E4 must extend
this admission together with dependency/routing qualification. E5 and actual
registry publication remain separate. No tag, signature, deployment or remote
write was created by E3.

Local checks built both real four-file bundles at `0.3.0-SNAPSHOT` and a
`0.3.0-rc.1` packaging preview. Strict Javadoc passed for both modules; the preview
is not a release candidate publication or a cold consumer result. Main dependency
inspection confirms that core has none and the adapter's only project dependency
is core, plus the documented Context/SLF4J family. There is no Boot, provider,
IOC application/TCK or private parent in either distribution POM. Dependency
versions are unchanged; this is a closure audit, not a refreshed CVE/update scan.

The offline publication/recovery suite has 167 passing tests, including the
existing signing/recovery cases, new packaging boundaries, version matching,
wrong-coordinate recovery, pending-consumer rejection and workflow admission.
Logs and unsigned bundles are retained under `.dev/lib5-e3-*`. Final full-reactor
verify/PMD identities, report disposition and freshness are recorded in the local
handoff and receipt after all source/document edits are complete. E4 is next.

## E4 isolated packaged consumers — 2026-10-25

Two standalone projects under `tools/fixtures` now consume the ordinary JARs
outside the checkout, with empty Maven caches and user/global settings. The JDK
fixture exercises service-defined collector metadata, recorder snapshots and
no-ops, with no third-party runtime graph. The Spring fixture's only main library
dependency is the adapter: its POM must supply core, Context and SLF4J. Provided
sources/Javadoc dependencies exclude those APIs and cannot repair missing classes.
Both execute the exact Java blocks extracted from the consumer guide. Explicit
checks throw without requiring `-ea`; the tool also invokes plain Java outside
Maven's plugin classloader and rejects checkout paths in the resolved classpath.

The Spring fixture uses unrelated payloads and real ordered annotated listeners.
It qualifies partial delivery without retry, original direct/Spring-wrapped Error
identity, null rejection before publication, runtime observation containment and
primary/suppressed failure evidence. The service's multicaster/error handler and
inline executor retain ownership; the adapter registers no beans. Contexts close
on every path; no worker or asynchronous guarantee is introduced. Boot runtime,
logging providers, IOC bootstrap and TCK classes are absent. E2 retains the
throwing-fallback-logger seam test; E4 uses only the public constructor and does
not publish or reflect into that test seam.

`consume --component events-spring` requires an explicit `--dependency-bundle`
for core. Before credential access or Maven execution it validates the bundle's
component, exact version and hashes. Local consumption assembles a temporary
selected repository from both immutable bundles; it never installs into the
usual cache. All four files for each own artifact must match its manifest and
record only `library-target` as origin. The remote read path compares both
bundles at the same selected endpoint, without claiming a live registry result.

Whole-graph checks admit only core for the JDK fixture, and same-version core
plus ten public JARs for the adapter. Default and application-owned Boot 4.0.8 BOM
runs compile and execute the fixtures and guides with Context 7.0.9/SLF4J 2.0.18.
Only Commons Logging/JSpecify change from 1.3.5/1.0.0 to 1.3.6/1.0.1. Six independent
offline negatives exclude or make unavailable core, Context and SLF4J; a separate
upper-bound negative selects incompatible Spring Core 5.3.39. Failures must name
the expected API/coordinate, not an unrelated network or tooling error.

Routing probes use distinct cold caches and an alternate containing exact own
bytes. First the selected component is missing; for the adapter a second probe
keeps the adapter available but removes core. Filtered resolution must fail for
the missing own coordinate. Unfiltered controls must compile and match all four/
eight hashes and their expected origins, including the selected adapter plus
alternate core. Existing own-group restrictions and the public allowlist remain
unchanged. An initial development run correctly failed qualification because an
unsupported wildcard in the new fixture's Enforcer include skipped the negative;
exact Spring coordinates replaced it. The failed log is retained separately;
no retry, suppression or acceptance-baseline change conceals that defect.

A later offline publication-test run hit one existing temporary GPG key-generation
failure (exit2 in the logging fixture); its original stderr was not surfaced.
A separate diagnostic generation passed. The fixture now retains safe stderr
context, with a regression ensuring input/exported key bytes stay hidden. The
original failed log remains evidence and the unexplained instability remains
open as [TOOL-7](../../../KNOWN-ISSUES.md); a later green run does not close it.
No retry mechanism or test exclusion was added.

Regular CI replaces the packaging-only events job with both consumers in one
credentials-free job; the existing named library gate requires it together with
the four older component jobs. Consumer availability and release admission now
have separate explicit checks: events may be consumed locally, while signing and
remote writes remain blocked for E5 before secrets or network writes. Protected
workflow choices remain unchanged. No production Java, API, dependency version,
reactor/analyzer scope, suite inventory or coverage threshold changes in E4.

Final command outcomes, bundle/tooling/fixture hashes and verification identities
are retained in `.dev/lib5-e4-evidence/receipt.json` after all tracked edits. Local
file-repository success qualifies packaging independence, not a second deployed
service or public publication. Other Spring/Boot baselines, a refreshed CVE scan,
E5 release admission and registry publication remain outside this checkpoint.
