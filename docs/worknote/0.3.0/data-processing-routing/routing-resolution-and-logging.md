# Routing syntax, unavailable views, fallback and logging

Status: proposed runtime contract closure, 2026-09-27. P2 implements the
typed syntax and startup descriptor admission; IOC runtime execution and final
diagnostic disposition remain later slices. Source baseline `f43037ee87ef`.
Refines [configuration design](configuration-execution-design.md). This document
recommends exact v1 semantics; it does not claim operator acceptance of the new
choices, implemented configuration binding or runtime qualification.

## Syntax recommendation

Retain the existing processing/plans/views/classifications/routing structure.
Use lowercase tokens first/all/exclusive and explicit on-unmatched. Add a
single structured no-match form, replacing the earlier scalar illustration:

```yaml
routing:
  mode: first
  on-unmatched:
    action: skip
  branches:
    - id: masks-host
      artifact: masks
      default-view: host
      eligibility:
        'on': original
        predicate: type-in
        arguments: { types: [DOMAIN, URL] }
      field-views: { description: original }
```

Other no-match forms are `{ action: reject }` or
`{ action: route, branch: default-original }`. The latter references a dedicated
`routing.default-branch` object with the same id/artifact/default-view/field-views
shape as a branch but without eligibility. It is not in branches and is evaluated
only on genuine no-match. Existing artifact gates still apply: if the default's
artifact gate filters the input, return filtered; do not recurse into default
routing. An expected dependency failure in the default is a failure, not no-match.

All routing modes and on-unmatched actions are required for new named plans.
Branches is a required list; an empty list requires default-branch with action
route (default-only plan). default-branch is forbidden for skip/reject. Duplicate
branch IDs, dangling references and unreachable defaults fail startup. Artifact
names may repeat across distinct branches subject to import cardinality checks.

For example, a default-only plan uses the following routing block. Referenced
artifacts/views must still exist; this fragment does not replace plan coverage
or artifact schemas:

```yaml
routing:
  mode: first
  on-unmatched: { action: route, branch: default-original }
  branches: []
  default-branch:
    id: default-original
    artifact: address_blacklist
    default-view: original
```

Conditions have exactly one shape: a predicate leaf (`on`, `predicate`, optional
`arguments`), nonempty `all`, nonempty `any`, or single-child `not`. No null nodes,
empty groups or mixed shapes. A missing eligibility means true. Leaf `on` is
required; there is no context-sensitive implicit view in this new syntax.
`arguments` is a typed registry-validated object: unknown keys and wrong types
are errors. Initially type-in accepts only a nonempty list of existing IOC enums;
existing feature predicates accept no arguments. Do not use Map<String,Object>
as a way to bypass strict configuration admission. The shape preflight must
recognize registry-owned parameter paths and defer them to explicit metadata
validation, on YAML, environment, system-property and CLI channels alike.

Composite conditions use the same recursive shape; `not` contains one object,
while `all` and `any` contain ordered lists. This example selects network types
other than IPV4 (enum comparisons themselves do not require derived views):

```yaml
eligibility:
  all:
    - any:
        - 'on': original
          predicate: type-in
          arguments: { types: [DOMAIN, URL] }
        - 'on': original
          predicate: type-in
          arguments: { types: [IPV4] }
    - not:
        'on': original
        predicate: type-in
        arguments: { types: [IPV4] }
```

`field-views` maps existing column names to declared view names. Unknown fields
are errors; source/ID provider overrides are rejected, as proposed previously.
Each active view requiring classification has one classifications entry naming
view and policy: configured. Multiple entries for one view are rejected. Existing
column schema, transforms, write-policy and artifact-identity remain authoritative.

Add plan-level `omitted-artifacts: [ioc_aggregate]` for explicit document output
omission. A target cannot be both routed and omitted; unknown/disabled omission
entries are rejected. Each enabled artifact must be routed (including default)
or explicitly omitted. This is configuration coverage, not a promise that each
record matches every artifact. Import contract authority remains the upper bound
and does not inherit document omission rules automatically.

Retain ordered lists, matching current binder behavior: no name-based merge is
introduced. Overrides must supply whole list elements. Validate IDs against a
bounded grammar (proposed `[a-z][a-z0-9_-]{0,63}` for new IDs; existing artifact
names keep their current contract), but generate internal Camel endpoint names
through a separate encoding to avoid collisions. Preserve condition and branch
order in fingerprints. YAML is illustrative until binder/channel tests pass.

## Ordered evaluation and unavailable views

Three condition outcomes are MATCH, NO_MATCH and BLOCKED(failure reference).
NOT flips only MATCH/NO_MATCH. It preserves BLOCKED. For ordered ALL conditions,
stop on NO_MATCH or BLOCKED; for ANY stop on MATCH or BLOCKED. Earlier boolean
short-circuit results avoid evaluating later unavailable views. Do not reorder
operands for optimization, because failure visibility is observable.

A view can be available, not yet evaluated, failed or absent from an optional
input. Not-yet-evaluated invokes its compiled producer when demanded. Failed
produces BLOCKED; absent is also BLOCKED when consumed as a required view. A
registered presence predicate may explicitly test optional input without asking
for a nonexistent value. Unknown bindings fail startup, not runtime. Unexpected
exceptions abort invocation and cannot be treated as expected BLOCKED.

| Reached branch outcomes in order | FIRST | EXCLUSIVE | ALL |
|---|---|---|---|
| NO_MATCH, MATCH, MATCH | Select second; third not evaluated | Ambiguous at third; dispatch none | Select second and third |
| BLOCKED, MATCH | Stop blocked; second not evaluated | Stop blocked; second not evaluated | Record blocked, select second |
| MATCH, BLOCKED | Select first; second not evaluated | Stop blocked; dispatch none | Select first, record blocked |
| MATCH, MATCH, BLOCKED | Select first | Ambiguous at second; third not evaluated | Select first and second, record blocked |
| All NO_MATCH | Apply on-unmatched | Apply on-unmatched | Apply on-unmatched |
| NO_MATCH, BLOCKED | Stop blocked | Stop blocked | No recipients; failure evidence, no default |

EXCLUSIVE stops at the first BLOCKED or second MATCH, whichever occurs first.
It does not inspect later routes to replace an uncertainty with another error.
One MATCH is returned only after every remaining route returns NO_MATCH. This
avoids claiming uniqueness that cannot be established. The alternative of treating
unavailable as false permits priority inversion or hidden multiple matches and
is rejected. Exhaustive evaluation for explanation adds cost and changes exception
behavior; use actual trace evidence and mark the suffix not-evaluated.

Selection failure affects the current observation/row, not automatic process-wide
shutdown. FIRST/EXCLUSIVE dispatch zero branches for that input. Existing failure
policy then decides whether the wider batch proceeds/writes other valid results.
Ambiguity is a routing ERROR with no result for that input. Under ALL, independent
successful branches may prepare despite expected blockage. Import logical-row
atomicity and required primary branches still override partial row admission.

A selected branch failing during mapping never causes FIRST to try the next route.
Selection default and transformation fallback are distinct. Default requires
conclusive no-match with no unresolved blockage; it never catches exceptions.

## Fallback: explicit alternate view, not mutation of host

Recommend a separate recovery operation instead of attaching fallback: original
to a view named host. Otherwise that view could silently cease to be a host and
mislead classification and key generation. Illustrative descriptor:

```yaml
views:
  - name: host
    operation: network.host
    input: original
  - name: usable-address
    operation: view.recover
    input: host
    arguments:
      on-reasons: [unsupported-address-form]
      use-view: original
```

`view.recover` is reserved by the R3 technical adapter. Operator binding and
IOC-specific reason tokens remain integration work. One alternate per recovery
node, referencing a previously declared
view; no cycles, chained recovery nodes or catch-all reason in v1. The node
returns host unchanged on success. On an allowlisted expected failure it may
return the alternate's actual value/type with recovery provenance. It does not
pretend the original is a clean host. Reclassify the recovered view and run all
normal gates/field/key/output checks. Consumers needing a mandatory bare host
continue to bind host, not usable-address.

The compiler includes recovery edges in dependency/type validation. Output type
is the union of primary and alternate types, narrowed only by explicit gates.
Runtime missing alternate, unsupported reason or failed alternate gives failure.
Programming faults, authorization failures, missing canonical identity and DB
errors are never recoverable through this operation. An allowed fallback cannot
bypass semantic output requirements. Untrusted input cannot choose use-view.

## Diagnostic disposition and failure policy

Use typed operation outcomes first; materialize final diagnostics at the preparation
boundary after demanded recovery paths are resolved. Operations do not log or
publish diagnostics themselves. Do not emit ERROR and later rewrite it to WARN.
Retain the original reason and causality in the final evidence.

| Outcome | Proposed severity / evidence | Consequence with current built-in policies |
|---|---|---|
| Explicit fallback succeeds and all demanded consumers recover | WARN, recovery-used with original reason and alternate view | Both policies admit; successful run has COMPLETED_WITH_WARNINGS |
| Primary failure has any demanded unrecovered consumer | ERROR for that failure occurrence; trace records any sibling recovery | fail-fast rejects at checkpoint; collect-and-continue can admit independently valid results |
| Fallback cannot provide a valid alternate | ERROR with primary/alternate failure references | Failed branch has no row; same policy rule |
| Unexpected exception | Existing stopping diagnostic/exception path | No preparation result or write for failed invocation |
| Routing skip / ordinary filter / genuine default | Counters and TRACE, no warning by default | Intentional policy behavior |
| on-unmatched reject / ambiguous selection | ERROR | No dispatch for this input; batch policy remains owner |

If one shared failed host has both strict and recovered consumers, keep one ERROR
for the original failure, not a WARN per recovered branch plus repeated ERRORs.
For all-recovered consumers retain one WARN for that original failure. A separate
alternate failure is its own causal occurrence; do not add duplicate wrapper
errors for every blocked dependency. No demanded consumers means no evaluation
and no diagnostic. These rules require invocation-local failure identity and
resolution accounting, not a persistent global diagnostic dedup registry.

The WARN recommendation makes explicitly permitted fallback useful with the
shipped fail-fast policy. Keeping ERROR even after complete recovery would make
fallback unable to admit writes under that policy; silent INFO would hide degraded
quality. Do not expose arbitrary severity strings per operation in v1. If stricter
recovery admission is needed later, extend failure policy explicitly.

Source evidence: FailurePolicy.failFast stops on ERROR/FATAL; collectAndContinue
stops on FATAL; CompletionStatus derives warnings/errors from DiagnosticSummary.
BoundedNotification already preserves first ERROR/FATAL and suppressed severity
counts. Reuse that implementation after final resolution, before checkpoint, and
keep element failures budgeted (not OPERATION-impact to evade retention limits).
For large documents finalize per input and feed the existing bounded accumulator;
do not retain all per-record failure graphs until batch completion.

Processed import currently treats ImportRowIssue as rejection evidence. Recovery
warnings therefore need an accepted-row diagnostic channel separate from rejection
issues, integrated with bounded reporting/workspace summaries. Do not put WARN
into the rejection list or discard it. This is a required import contract change
before enabling recovered success there; no new DB schema is presumed until the
existing report/summary persistence is inspected. Source authority and mandatory
validation still reject regardless of warning-level recovery.

## Logging points and owners

| Point | Owner / level | Content and limits |
|---|---|---|
| Effective plan admitted | Bootstrap, INFO once per activated plan | Plan ID/fingerprint, counts, runtime version; no values or full config |
| Invalid plan / Camel startup failure | Existing CONFIG diagnostics / lifecycle owner, ERROR | Config path, stable code, failed binding; readiness remains closed |
| Outer preparation stage start/end | Existing PipelineObserver, DEBUG; ERROR on stage failure | Duration and run scope; do not emit per-record stage INFO |
| View evaluation/recovery and actual condition decisions | Extended PipelineDecisionTracer, TRACE opt-in | Input position, plan, view/branch, outcome, failure reference; no second evaluation |
| Final expected operation diagnostic | Existing diagnostic delivery owner and LoggingDiagnosticSink, WARN/ERROR | One retained occurrence, final severity, original reason, recovery disposition |
| Prepared/filtered/blocked branch | Decision tracer, TRACE and bounded counters | Counts mean candidates/preparation, not committed rows |
| Failure-policy checkpoint | Application/outer pipeline, existing failure event; DEBUG for optional accepted summary | Policy and counts; preparation success is not commit success |
| Canonical commit/projection/import receipt | Existing application/JDBC observers | Preserve their distinct durable outcome events |
| Delivery/run completion | Existing completion reporter | Extend summary with recovered/blocked/filter counts; avoid second summary logger |
| Shutdown failure | Runtime lifecycle owner, ERROR | Pending work/count, no payload dumps |

Current LoggingPipelineObserver already has DEBUG start/complete and ERROR failure.
LoggingPipelineDecisionTracer requires both config enabled and TRACE logger level;
its render failure is swallowed intentionally so tracing does not affect processing.
It currently logs sanitized values when enabled, not just a hash. Extend typed
PipelineItemDecision/LogField fields for plan/view/branch/reason; do not overload
pattern/result with unbounded serialized JSON. New routing traces should prefer
position/correlation references without raw payload; retain existing sanitizer
for any explicitly enabled value tracing. A short hash is not an anonymization
or uniqueness guarantee. Never dump Camel Exchange bodies or headers by default.

Use run/delivery ID, pinned policy fingerprint, source row/occurrence position and
branch/view IDs for correlation. They belong in structured event fields; only
bounded configured dimensions belong in metric labels. No raw URL, input ordinal,
run ID or full fingerprint as metric labels. Open/close branch MDC scopes in
try-with-resources and restore parent scope on every path. Do not store mutable
per-input state in singleton observers; sequential branches can still have
concurrent callers. No new executor or ThreadLocal payload cache is needed.

One diagnostic emission is not one total log line: the current stage-failure event
and diagnostic event represent different facts. Keep that distinction and avoid
adding a third Camel exception log or per-branch stack trace. Select a propagating
Camel error handler with no redelivery/handled-success behavior and verify logging
under the exact version. Camel exposes several handler strategies; their ownership
must be configured deliberately. Source: [Camel error handlers](https://camel.apache.org/manual/error-handler.html).
Do not suppress genuine framework startup failures by globally disabling Camel logs.

TRACE detail must have a budget/sampling extension at the existing tracer if
needed; the current enable switch alone is not a volume bound. Diagnostic budget
and trace budget are separate: suppressed traces must not lose diagnostic counts
or rejection evidence. No claim that this additional trace budget exists today.

## Verification and implementation seams

- Add binder fixtures for all shapes, unknown argument keys across channels,
  duplicate IDs, invalid no-match unions, omission coverage and recovery cycles.
- Table-drive ordered boolean and routing cases above with counters proving
  unevaluated predicates stay unevaluated. Assert zero dispatch on blocked
  FIRST/EXCLUSIVE and ambiguity; assert allowed ALL sibling preparation.
- Test primary-only success, recovered WARN, unrecovered ERROR, mixed consumers,
  failed alternate and unrelated programming exception. Assert full diagnostic
  summaries and checkpoint outcomes under both existing policies.
- Fill the diagnostic budget before a late ERROR/FATAL; verify suppression does
  not permit writes. Preserve stage boundaries in legacy compatibility fixtures.
- Test trace off/config on/log level off, trace renderer failure, sanitizer and
  MDC restoration, concurrent invocation isolation and no Camel duplicate emission.
- Add accepted-import-warning tests together with row rejection/authority tests;
  fallback must not weaken atomicity or modify imported slot authority.

No production files changed. Implementation touches the typed descriptor/compiler,
operation outcome bridge, diagnostic catalogs, PipelineItemDecision and logging
field registry, import warning channel and existing tests. Generated diagnostic
reference docs must be regenerated from catalogs, never hand-edited. No database
optimization or index change is justified by these contracts.

## Draft decision C4

**Context:** unavailable views, fallback and logs must preserve deterministic
routing and existing rejection semantics. **Proposed decision:** ordered blocked
outcomes; FIRST/EXCLUSIVE stop without dispatch when selection cannot be proved;
explicit recovery views; final WARN only when all demanded consumers recover;
reuse existing logging/diagnostic owners. **Alternatives:** unavailable=false,
catch-all original fallback, always ERROR after recovery, engine-owned diagnostics.
**Consequences:** explicit failure-resolution accounting and accepted import warning
support, with predictable policy and no duplicate diagnostic delivery. These
recommendations await design acceptance and implementation proof.
