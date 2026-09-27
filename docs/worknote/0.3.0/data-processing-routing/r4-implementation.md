# R4 runtime lifecycle and observability hooks

Status: implemented on `module/platform/router`, 2026-09-27. No IOC plan is
registered in the production composition root yet; document extraction and
managed import still use their existing preparation path.

## Delivered boundary

- `RouterPlanRegistration` supplies already admitted neutral descriptors,
  registered operations and a policy fingerprint. Bootstrap conditionally
  compiles and starts one isolated `CamelRouteRuntime` when such a registration
  exists. No registration means no Camel context or router health indicator.
  Invalid registered plans fail context startup before the runtime is ready.
- Spring owns the runtime bean and calls `close()` on shutdown. Readiness checks
  the context and generated routes; a daemon-only health indicator exposes the
  result. New calls are rejected after shutdown begins. Active calls can drain
  within the configured bound, after which Camel is stopped with its own bounded
  shutdown strategy using the remaining millisecond budget. The runtime owns
  no worker pool or durable state.
- Neutral `RoutingExecutionScopes` and `RoutingTraceSink` contracts keep MDC and
  application types out of the Camel adapter. Bootstrap scopes registered view
  operations and destinations with plan/view/branch MDC fields and restores the
  parent on success or exception. A value-free trace event reports view,
  condition, recovery and branch decisions through the existing
  `PipelineDecisionTracer` with the pinned policy fingerprint; a failing
  observer cannot change a routing result.
  Malformed dynamic rule IDs and reason codes are redacted in trace events
  without changing the returned failure evidence.
  The existing application diagnostic sink remains the only diagnostic owner.
- Plan admission logs one structured INFO event with plan ID, fingerprint,
  view/branch counts and embedded Camel version. No raw IOC values, Exchange
  headers or bodies enter these new events. Generated routes use a propagating
  error handler with no redelivery; unexpected exceptions remain caller-visible.

## Verification and remaining boundary

Timed tests exercise concurrent caller isolation, bounded close with active
work, rejection after close, Spring activation and shutdown, daemon readiness,
value-free traces, observer failure, and parent MDC restoration after successful
and failing operations. R5 still owns synthetic load qualification and the
document/import integration fixtures after IOC P3/P4. Those slices also own
the typed IOC operation bindings and final diagnostic severity; R4 does not
claim customer host-cleanup acceptance.

Deterministic offline evidence on the R4 worktree:

- Focused Camel module `verify`: 47 tests, 0 failures, 0 visible SpotBugs
  findings; local JaCoCo 712 covered/2 missed lines and 313 covered/0 missed
  branches. The Spring `RouterRuntimeConfigurationIT` also passed in isolation.
- `make verify`: 26 reactor projects passed; test inventory 206 fast, 68
  integration, 5 external shells and 269 deterministic offline suites. Aggregate
  JaCoCo: 22,961/25,474 lines (90.14%); two complete passes observed
  7,665–7,667/9,463 branches (81.00–81.02%) across the reactor.
  SpotBugs: 120 reviewed baseline findings, 0 visible. CPD: 24/24 duplication
  groups. Local Camel coverage remains above its R4 ratchet without raising a
  missed allowance.
- `make pmd-analysis`: 0 blocking, 22/22 advisory findings. The separate
  `make pmd-watchlist` completed with 30 advisory findings; neither report has
  a finding in the changed router classes. A named condition-lookup adapter
  also avoids a PMD 7.26.0 type-resolution error on an anonymous implementation.
- `make docs`: 1,183 links checked, 0 errors. The bootstrap dependency tree
  resolves all Camel artifacts at 4.22.1. Offline `make security-scan` completed
  with 131 analyzed dependencies and 0 reported vulnerabilities; this uses the
  installed local Dependency-Check data rather than refreshing the NVD feed.

No provisioned external-system qualification or diagnostic load pilot was run
for R4; R5 and IOC P3/P4 retain those acceptance boundaries.
