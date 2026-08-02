---
title: "ING-10 ingestion lifecycle hardening"
version: "0.3.0"
status: "Verified"
document_type: "Execution worknote"
source_of_truth: false
language: "en"
---

# ING-10 ingestion lifecycle hardening

This temporary worknote records the evidence and implementation sequence for the
approved `ING-10` / SpotBugs `IR-03` queue predecessor. Durable behavior belongs
in [ingestion.md](../../dev/ingestion.md); release state belongs in
[status-matrix.md](status-matrix.md).

## 1. Confirmed failure model

The pre-fix daemon has two independent startup branches:

1. Spring Integration starts the inbound flow as an auto-starting
   `SmartLifecycle` during context refresh.
2. `IngestionRecoveryRunner` invokes source-ledger recovery later as an
   `ApplicationRunner`.

A fresh inbox source can therefore be claimed by the poller and immediately
appear to recovery as an incomplete `CLAIMED` record. Both branches may execute
the same `SourceKey`. Canonical row-key deduplication protects business rows, but
does not prevent duplicate extraction, duplicate diagnostics, competing run
records, terminal-state overwrite, or false dead-letter failures.

The configured `ioc.ingestion.concurrency=1` does not close this race: it is a
reserved single-poller seam and is not a shared execution boundary between the
poller and startup recovery.

## 2. Required invariants

The implementation is complete only when all of these properties are executable
and documented:

1. **Recovery before intake:** the inbound flow remains stopped until run-ledger
   and source-ledger recovery complete successfully.
2. **Fail closed:** a recovery failure never opens intake.
3. **One execution per source key:** every ingest, recovery and rejection entry
   point uses the same synchronous per-`SourceKey` execution boundary.
4. **Fresh state after admission:** recovery re-reads the ledger after entering
   the key boundary and never acts on a stale scan result.
5. **Monotonic durable state:** source-ledger transitions use expected-state/CAS
   semantics; competing terminal transitions cannot overwrite one another.
6. **Observable lifecycle:** health exposes whether recovery is pending, running,
   complete or failed and whether intake is running.

The supported 0.3.0 deployment remains a single daemon process. Cross-process
ownership would require a lease/fencing design and is not implied by this work.

## 3. Checkpoints

| Checkpoint | Scope | Exit evidence | State |
|---|---|---|---|
| `I0` | Failure model and executable characterization | A deterministic latch-based test proves that the current runner and poller paths overlap; no timing sleeps | `completed` |
| `I1` | Startup lifecycle barrier | Explicit coordinator recovers both ledgers before starting the non-auto-start flow; failure leaves it stopped | `completed` |
| `I2` | Per-key synchronous execution | Same keys serialize, different keys can progress, and recovery re-reads state after admission | `completed` |
| `I3` | Source-ledger state machine | File and JDBC adapters implement expected-state/CAS transitions and share concurrent TCK coverage | `completed` |
| `I4` | Operational closure | Health, configuration guard, restart/E2E evidence, durable docs and full reactor verification | `completed` |

Only one checkpoint is implementation-active at a time. Each checkpoint is
committed independently.

## 4. I0 evidence

`IngestionStartupRaceCharacterizationTest` starts the production
`IngestionRecoveryRunner`, blocks inside recovery, then invokes the production
`FileSourceMessageHandler` as the poller path. The handler enters before recovery
is released. Latches make the ordering deterministic and the assertion captures
the actual missing coordination boundary rather than relying on scheduler luck.

This is deliberately a characterization of unsafe behavior, not the final
contract. `I1` must replace it with coordinator tests that assert intake remains
closed during recovery.

## 5. I1 evidence

The Spring Integration source endpoint now has `autoStartup=false`.
`IngestionStartupCoordinator` is the sole startup owner and executes, in order:

1. `IngestRunRecoveryService.recover()`;
2. `RecoverIngestionUseCase.recoverIncomplete()`;
3. `Lifecycle.start()` on the complete `iocIngestionFlow`.

The former eager run-recovery bean and independent source-recovery runner no
longer exist. Coordinator tests hold recovery open with a latch and prove that
intake remains stopped; separate failure tests prove that either recovery error
prevents `start()`. Runtime-mode context tests prove that the manually controlled
flow is running after successful application startup.

## 6. I2 evidence

`SynchronousKeyedExecutionGuard` was added to the framework-free concurrency
platform rather than adapting the asynchronous executor. It preserves the
ingestion method's synchronous result and exception contract, removes idle key
state, allows different keys to execute concurrently and exposes only aggregate
contention counts.

All three source entry points (`ingest`, recovery and rejection) now enter the
same guard with the source content hash as key. Recovery treats
`findIncomplete()` as discovery only: once admitted for that key it re-reads the
ledger and acts on the current status. Processing-orphan reconciliation uses the
same boundary.

Deterministic tests cover same-key waiting, concurrent different-key execution,
failure cleanup, two concurrent ingestion calls producing only one extraction,
and a stale `CLAIMED` scan whose current state is already terminal.

## 7. I3 evidence

The `IngestionLedger` port now reports `APPLIED`, `ALREADY_APPLIED`, `CONFLICT`
or `MISSING` for every state change. Its state machine is explicit:

```text
ABSENT ──claim──> CLAIMED ──archive──> SOURCE_ARCHIVED
   │                  └────fail──────> FAILED
   └────────────────pre-claim fail───> FAILED
```

Terminal states are monotonic. Repeating the same target is idempotent and does
not rewrite its context; requesting the opposite target reports a conflict.

`JdbcIngestionLedger` implements claim with `INSERT ... DO NOTHING`, archive
with `UPDATE ... WHERE status=CLAIMED`, and failure with one conditional SQLite
upsert. The unused transaction helper and its invalid single-writer rationale
were removed. `FileIngestionLedger` performs read/decide/atomic-replace inside a
per-key synchronous critical section; this guarantee is instance-local, matching
the supported single-daemon deployment.

The reusable adapter TCK now runs a latch-released archive-versus-fail race and
requires exactly one `APPLIED` result and one `CONFLICT`. The same contract runs
against file and JDBC adapters, alongside missing, idempotent and opposite-state
cases. Legacy import accepts only a completed target transition.

## 8. Scope boundaries

- `ING-11` retry versus run-ledger resume remains separate.
- `ING-13` full pre-claim file-fate handling remains separate.
- `ioc.ingestion.concurrency` is not turned into parallel execution here.
- Control events may report facts or accelerate work, but are not the startup
  correctness mechanism; direct lifecycle ownership establishes the ordering.
- No production behavior changes are introduced in `I0`.

## 9. I4 implementation evidence

`IngestionLifecycleState` records `PENDING`, `RECOVERING`, `RUNNING` or
`FAILED`. The bootstrap health contributor reports `UP` only when state is
`RUNNING` and the actual integration flow is running. It includes recovery
timestamps/counts and aggregate keyed-guard counts, never source keys. A startup
failure stops the flow fail-closed and is rethrown. Normal Spring Boot readiness
cannot become `ACCEPTING_TRAFFIC` before the coordinator's `ApplicationRunner`
returns.

Semantic configuration preflight now requires
`ioc.ingestion.concurrency=1`; a value that cannot change runtime behavior is no
longer silently accepted. Parallel intake remains future work rather than a
misleading knob.

Operational regression evidence combines three levels:

- coordinator latch tests hold recovery open and prove intake ordering/failure;
- a restart test reuses durable terminal source state and proves no second run
  starts;
- daemon E2E atomically renames a completed `*.part` source into the watched
  inbox and waits for poller-driven archive plus canonical provenance.

The focused 21-project Maven run passed 50 selected tests across application,
ingest adapter and bootstrap, including both file/JDBC daemon contexts.
`make docs` passed with 448 checks and no errors. The first full-reactor run
correctly rejected an enum accidentally placed in the port package; moving the
transition result into the application package restored the interfaces-only
port boundary. The final `make verify` then passed all 24 reactor projects in
`01:30`, including both build-quality integrity gates and the two expected
external SMB skips.

The refreshed SpotBugs aggregate no longer contains `SB04-116`. Four findings
were introduced by the hardening code: two `VO_VOLATILE_INCREMENT` reports are
false positives because every counter mutation is serialized by same-key
`ConcurrentHashMap.compute`; `UL_UNRELEASED_LOCK_EXCEPTION_PATH` is contradicted
by the lock `finally` and the failure-cleanup regression; and the startup
coordinator's `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` is a deliberate fail-closed
rethrow after stopping intake and recording failed lifecycle state. They remain
visible without suppression for `BUILD-SPOTBUGS-04/C3`. The resulting raw count
is 121, a net change of three after removal of `SB04-116`.

## 10. Observability follow-up

The initial I4 implementation exposed recovery state through health but did not
provide a typed operation timeline. The follow-up closes that operational gap
without introducing control events or changing the startup barrier:

- `ingest_recover` records start (`unknown`) and exactly one terminal outcome;
  success carries duration and recovered run/source counts, while failure
  carries duration and only the exception class in `error.type`;
- a duplicate keeps the existing successful terminal `source_ingest` action and
  adds `ioc.ingest.disposition=duplicate` instead of inventing a separate event;
- an unexpected ledger result remains the exact
  `INGEST.STATE_TRANSITION_CONFLICT` root diagnostic; a generic
  `INGEST.RECOVERY_FAILED` is created only for an otherwise untyped startup or
  recovery failure;
- a recovery diagnostic already delivered by `IngestionService` is not emitted
  again by the startup observer.

Focused tests pin callback ordering, structured field types, message safety and
the exactly-once diagnostic boundary. The generated logging and diagnostic
catalogs are the public machine-readable vocabulary; neither operational record
participates in startup ordering or durable correctness.

The four focused classes passed 39 tests. A clean 24-project reactor passed in
`01:37`, and the immediate incremental repeat passed in `01:31`; both produced
all 19 module XML/HTML pairs plus the same 122-finding aggregate with `errors=0`
and `missingClasses=0`. The only follow-up finding is a P3
`THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` on
`IngestionService#ingestGuarded`: the method performs required source-failure
cleanup and deliberately preserves the original typed exception for the final
diagnostic boundary. It is classified as the same false-positive exception-flow
contract already established by `C2-EX-B` and remains visible without
suppression for C3.
