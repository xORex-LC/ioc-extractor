---
title: "DATA-IMPORT-01 P7 evidence"
version: "0.3.0"
status: "Implemented with affected-reactor verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P7 evidence

## 1. Evidence boundary

P7 activates local managed dataframe import behind the daemon recovery barrier
and completes the forward-only saga from an immutable snapshot to a protected
terminal source/report unit. SMB ownership remains fail-closed at this commit;
P8 supplies its transport adapter and qualification evidence.

## 2. Recovery and global ordering

- `CanonicalIntakeStartupCoordinator` now owns one recovery-before-intake
  barrier for ordinary ingest, lifecycle admission and managed import. A failed
  import recovery closes both intake paths.
- Startup reconciles incomplete claims and then advances only the durable
  minimum nonterminal sequence. `DataframeImportRecoveryCoordinator` and
  `DataframeImportDrainCoordinator` share one constant keyed lane, so periodic
  recovery, snapshot events and ordinary drain hints cannot overtake the head.
- Every retry persists its eligibility time and safe code. Polling and event
  callbacks submit hints without blocking worker or scheduler threads.
- The periodic full listing and durable-head reconcile remain correctness
  authorities when events are lost, duplicated, coalesced or rejected.

## 3. Finalization and retention

- `DataframeImportProcessingService` resumes staging, promotion and
  finalization from service-ledger checkpoints. A dataframe `import_commit`
  receipt advances a post-commit crash without applying canonical mutations a
  second time.
- Finalization reconstructs bounded counts, row numbers, codes, artifact names
  and slot evidence from the dataframe receipt. `LocalImportTerminalStore`
  copies the exact immutable source and a versioned JSON report into one
  private directory, forces both files and atomically publishes the directory.
- Only after that unit exists does local disposition remove the claimed
  processing object and the service ledger enter `TERMINAL`.
- Retention uses the existing `max-age`, `max-count`, `delete|archive`
  vocabulary. Successful and unsuccessful outcome sets are disjoint, selection
  is ledger-backed and bounded, and source/report move or deletion is one
  logical unit. Snapshot, stage, dataframe receipt and terminal ledger evidence
  are removed only after the terminal action succeeds.

## 4. Operator and observability surfaces

- `ioc import validate` executes the same recognizer, parser and mapping rules
  without a durable claim or canonical mutation.
- `ioc import status` exposes recovery, state counts and safe head sequence,
  state, age, retry count/backoff and diagnostic code. It provides no skip,
  reorder or force-complete mutation.
- `ioc import replay` creates a new delivery and private immutable snapshot with
  a causal link to a retained terminal occurrence.
- Actuator health is `DOWN` for an incomplete/failed recovery, `DEGRADED` for a
  safely retrying head or runtime accelerator failure, and `UP` for normal
  progress. Details and generated `IMPORT.*` diagnostics exclude filenames,
  paths, digests, raw cells and exception messages.
- Low-cardinality gauges cover recovery completion and keyed-lane queued/running
  work. Graceful shutdown stops change hints and scheduling before bounded lane
  drain.

## 5. Executable evidence

| Check | Result |
|---|---|
| shared startup order | ordinary run/source recovery, lifecycle admission and import recovery complete before either intake starts; failure closes both |
| durable head | deferred minimum sequence remains the sole due head; later deliveries are not selected |
| crash recovery | claim/stage/promotion/finalization checkpoints either resume forward or fail closed on contradictory evidence |
| report leakage | atomic terminal report retains row/code/count evidence and does not contain source IOC values, snapshot references or paths |
| retention | age and count selection are unioned per disjoint outcome target; delete and idempotent atomic archive preserve the source/report pair |
| operator UX | validate is advisory, status is read-only and replay creates a new causally linked occurrence |
| health leakage | only aggregate queue/head facts and cataloged safe codes are exposed; retrying head is `DEGRADED` |
| configuration | classpath/template/operator guides agree on local runtime, retention and shutdown settings; invalid archive policy is rejected |

The focused P7 corpus completed with `64` tests, all green, after the retention
contract correction. A clean detached worktree at committed P7 HEAD `edc0774c`
then ran the 21-project affected reactor through `clean test`; every module
passed and `ioc-app` ran `276` tests without failure. Final full-reactor
freshness is recorded after P8 so it covers the requested two-slice
implementation span.

## 6. Next boundary

P8 replaces the explicit SMB fail-closed composition branch with server-side
rename ownership, shared endpoint sessions, local immutable materialization,
orphan adoption, CHANGE_NOTIFY acceleration and remote terminal disposition.
