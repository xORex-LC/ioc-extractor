---
title: "ING-10 ingestion lifecycle hardening"
version: "0.3.0"
status: "Active"
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
| `I4` | Operational closure | Health, configuration guard, restart/E2E evidence, durable docs and full reactor verification | `in-progress` |

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
