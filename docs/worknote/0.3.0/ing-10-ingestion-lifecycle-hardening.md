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
| `I1` | Startup lifecycle barrier | Explicit coordinator recovers both ledgers before starting the non-auto-start flow; failure leaves it stopped | `in-progress` |
| `I2` | Per-key synchronous execution | Same keys serialize, different keys can progress, and recovery re-reads state after admission | `not-started` |
| `I3` | Source-ledger state machine | File and JDBC adapters implement expected-state/CAS transitions and share concurrent TCK coverage | `not-started` |
| `I4` | Operational closure | Health, configuration guard, restart/E2E evidence, durable docs and full reactor verification | `not-started` |

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

## 5. Scope boundaries

- `ING-11` retry versus run-ledger resume remains separate.
- `ING-13` full pre-claim file-fate handling remains separate.
- `ioc.ingestion.concurrency` is not turned into parallel execution here.
- Control events may report facts or accelerate work, but are not the startup
  correctness mechanism; direct lifecycle ownership establishes the ordering.
- No production behavior changes are introduced in `I0`.
