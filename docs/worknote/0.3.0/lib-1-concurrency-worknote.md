---
title: "LIB-1 concurrency pilot hardening"
version: "0.3.0"
goal_id: "R030-LIB"
status: "Verified (hardening only)"
document_type: "Execution worknote"
source_of_truth: false
language: "en"
---

# LIB-1 concurrency pilot hardening

## Scope and admission boundary

Work item: `0.3.0 / R030-LIB / platform/platform-concurrency / hardening /
LIB-1-HARDEN-01`. Review base: `8444ea92e3d21895935ef0f417edc0b81c18f9f1`.
Implementation authorized on 2026-09-07, including logical commits. This is an
existing module's bounded hardening step, not full publication admission.

Owner: Platform coordination. Category: candidate cross-service platform.
Responsibility: in-process keyed serialization and synchronous exclusion.

Current direct Maven consumers are `ioc-application`, `adapter-ingest` and
`ioc-app`. Actual uses are ingestion exclusion, file-ledger transitions,
endpoint-scoped fetch/publish, and managed-import source/global lanes. These
are multiple module consumers inside one deployed service, not independent
external services.

The user confirmed Java + Spring Boot for the planned standalone
`feeds-collector`. Its same-key FIFO/coalescing requirements are undecided.
It is a prospective consumer, not evidence that both services need the same
execution policy. A synthetic consumer can validate packaging and declared
contracts, but cannot establish those future business requirements.

## Alternatives and decision

| Alternative | Fit and remaining work |
|---|---|
| JDK `ReentrantLock` | Provides reentrant exclusion. A keyed registry still needs safe lifetime accounting, idle cleanup and aggregate telemetry. The synchronous guard is that small composition. |
| JDK single-thread executor | Serializes all work; one per key adds thread/lifecycle ownership and idle cleanup. A shared pool alone does not serialize equal keys. |
| Guava `Striped` | Provides bounded lock striping; unequal keys may share a stripe. This trades memory for unrelated-key contention and does not match exact per-key isolation. |
| Guava `MoreExecutors.newSequentialExecutor` | Provides one serial lane. A keyed registry, per-key bounded admission, rejection reporting, snapshots and shutdown coordination would still be needed. |

Retain the existing JDK composition for this hardening step. No new runtime
dependency, umbrella module or API/implementation split is justified. The
comparison supports retaining these mechanics for current consumers; it does
not admit speculative feeds requirements.

Primary references reviewed on 2026-09-07:
[JDK thread pools](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html),
[Guava Striped](https://guava.dev/releases/33.4.8-jre/api/docs/com/google/common/util/concurrent/Striped.html),
[Guava sequential executor](https://guava.dev/releases/33.4.8-jre/api/docs/com/google/common/util/concurrent/MoreExecutors.html#newSequentialExecutor(java.util.concurrent.Executor)).

## API, dependency and compatibility disposition

Keep the existing 12 public types in `com.iocextractor.platform.concurrent`:
two execution interfaces and their two implementations, `WorkKey`, admission
record/status, observer/no-op observer, and three snapshot records. Constructor
and method signatures stay unchanged. The no-op enum remains public for source
compatibility; splitting it into another artifact has no dependency benefit.

Runtime dependency closure is JDK 21 only. JUnit and AssertJ are inherited
**test** dependencies. Root parent resolution is still a publication concern;
JDK-only bytecode does not make the current POM independently resolvable.

Proposed first-pilot coordinates retain
`com.iocextractor:ioc-platform-concurrency` with the product's lockstep version.
This step adds no independent version train, BOM, Spring starter or JPMS module.
Public FIFO/exclusion, queue admission, exception and shutdown semantics must
be contract-tested before a public release. Business schemas, configuration,
events, transport, durable retries and distributed coordination are excluded.
Diverging future consumer policies belong in their own coordinators until a
shared need justifies an explicit versioned API extension.

## Findings and dispositions

| ID | Finding | Disposition and evidence |
|---|---|---|
| `LIB-1-01` | `oldestAge` resets when queued work becomes dispatched | Fixed: retain its submission timestamp. Deterministic clock regression checks age 61 seconds before advancing the lane and 60 seconds after advancement. Health schema stays unchanged; values now include queue wait. |
| `LIB-1-02` | Nested synchronous execution clears the outer execution flag and reports the outer caller as waiting | Fixed: publish lock hold depth while owning the key lock. Normal nested return and caught nested failure preserve `(1,1,0)` until outer exit; existing exclusion and cleanup-failure contracts remain tested. |
| `LIB-1-03` | Synchronous Javadoc promises earlier-work ordering despite a non-fair lock | Corrected: exclusion, same-thread reentrancy and approximate telemetry are explicit; no FIFO, interruption or timeout guarantee is added. |
| `LIB-1-04` | Arbitrary worker pools can silently discard tasks or execute recursively on submitting threads | Explicit unsupported configuration: dedicated async execute-or-throw pool, exclusive lifecycle ownership. Current production pools use default abort policy. Initial and drain-time explicit rejection are tested. This documentation cannot detect or repair arbitrary executors violating the precondition. |
| `LIB-1-05` | Per-key bound and `running` can be mistaken for global capacity or active worker count | Documented: dispatched tasks may wait in the backing pool; keys are not globally bounded. Zero-bound test demonstrates independent admission with one busy worker. New consumer key-space control remains its responsibility. |
| `LIB-1-06` | Observer, close and error ownership are underspecified | Documented synchronous nonblocking telemetry, runtime-exception containment, nonwaiting close, drain/join ownership and caller-owned durable recovery. Regression tests exercise every throwing observer callback. |
| `LIB-1-07` | Waiting suites lack containing timeout and failure-safe worker joins | Fixed: both suites have `@Timeout(30)`; releases occur before cleanup and every owned worker pool has a bounded termination assertion. Suite names/lifecycle remain unchanged. |

The two existing SpotBugs `VO_VOLATILE_INCREMENT` identities (`I4-SB-02/03`)
remain semantically reviewed: `users` mutations are inside same-key
`ConcurrentHashMap.compute`; depth updates hold the per-key lock and derive from
`ReentrantLock.getHoldCount()`. No new volatile compound mutation is introduced.
No analyzer suppression, coverage floor or test-lifecycle exception is added.

## Validation

- Focused module run: 23 tests, zero failures/errors/skips (three existing
  Surefire suites; seven additional test methods).
- Full deterministic offline `make verify`: 25/25 projects passed on the
  implementation worktree, completed 2026-09-07T11:48:19Z. Surefire/Failsafe
  reports contain 1,493 cases, zero failures/errors and eight skipped cases.
  Lifecycle integrity remains 196 fast suites, 66 integration suites and five
  external shells (257 deterministic offline suites); no registry change.
- Coverage: module 199/218 lines (91.28%), 48/64 branches (75.00%); aggregate
  20,104/22,390 lines (89.79%), 6,507/8,123 branches (80.11%). All existing
  ratchets pass without edits.
- Raw/filtered SpotBugs: 116 accepted identities, zero visible findings, zero
  analyzer errors or missing classes. Both guard identities retain their
  hashes/methods/fields; only displayed source lines move by two. No baseline
  refresh or suppression change is needed.
- CPD: unchanged 21 groups; none touches this module. Adopted PMD: unchanged
  21 findings in 16 files, zero blocking findings and none in this module.
  Explicit `make pmd-watchlist`: unchanged 29 findings in 19 files, none in
  this module. Corresponding raw SpotBugs, CPD and both PMD reports reviewed.
- `make docs`: 836 links checked, zero errors. `git diff --check`: clean.
- Committed-HEAD freshness is recorded by `make context` after the final
  `make verify` and `make pmd-analysis`; the qualification above identifies the
  pre-commit worktree run rather than claiming publication or stand evidence.
- External publication and provisioned service evidence: not performed.

## Remaining publication work

`R030-LIB` and `TEST-PUBLICATION-08` remain open. Full admission still needs a
concrete independent-consumer contract, repository/access choice and the ADR
covering coordinates, ownership and compatibility. Then configure a
consumer-resolvable POM, sources/Javadoc artifacts, protected snapshot/release
publication, immutable artifact identity and a standalone consumer that resolves
from the chosen repository. Local `install` is not publication evidence.

Authoritative mechanics: [module reference](../../../platform/platform-concurrency/README.md)
and [event coordination](../../dev/event-coordination.md).
Release tracking: [shared-code inventory](evidence/shared-code-inventory.md),
[review ledger](evidence/review-ledger.md), [status matrix](status-matrix.md).
