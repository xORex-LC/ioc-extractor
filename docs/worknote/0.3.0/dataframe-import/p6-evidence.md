---
title: "DATA-IMPORT-01 P6 evidence"
version: "0.3.0"
status: "Implemented with affected-reactor verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P6 evidence

## 1. Evidence boundary

P6 implements the atomic dataframe promotion boundary and its forward-only
receipt replay. It does not activate managed import at daemon startup: P7 still
owns the shared recovery-before-intake barrier, global drain, report generation
and terminal disposition. The production composition therefore shares writer
admission with existing canonical paths now, while import writer activation is
deferred until that barrier exists.

## 2. Atomic promotion

- `JdbcCanonicalImportWriter` checks an exact `import_commit` receipt before it
  requires staging bytes. A post-commit crash can therefore resume even if the
  sealed stage is no longer available, while any receipt identity mismatch
  fails closed.
- A new attempt verifies the opaque delivery-owned stage reference, SHA-256,
  SQLite integrity, schema version and all snapshot/contract/policy pins before
  fair writer admission. The stage is attached read-only and immutable.
- Worst-case public and lifecycle ID ranges are reserved before the dataframe
  transaction. Unused IDs remain safe monotonic holes.
- One sampled effective time and one dataframe transaction cover active-only
  alias/record-key matching, bounded tri-state merge, logical-row fan-out,
  failure policy, lifecycle mutation, provenance, aliases, preferred slots,
  artifact revision, projection generation, receipt and safe evidence.
- Connection-scoped temporary match/plan tables are dropped and recreated on
  each pooled connection use. The sealed staging database is never modified.
- Public revision and projection generation advance once per affected artifact
  only when public bytes change. TTL-only confirmation changes deadline state
  without creating public export work; an unchanged row is renewed only when
  its pinned policy enables renewal.

## 3. Requested slots and reporting evidence

- Requested slots remain scoped by `(profile, artifact)`: the same numeric slot
  may be assigned independently to different artifacts.
- A free request is exact; an occupied request uses the existing smallest-free
  positive allocator. Existing survivors are never renumbered.
- `preserve-existing` applies the business merge and persists a safe
  `SURVIVOR_MISMATCH_PRESERVED` resolution. `reject-mismatch` rejects the whole
  logical row before any branch mutation.
- Dataframe schema v9 owns `import_commit`, `import_commit_artifact`,
  `import_row_rejection` and `import_slot_resolution`. Evidence contains IDs,
  row numbers and stable codes/outcomes, never imported IOC values.

## 4. Concurrency and events

- `JdbcWriterAdmission` uses an interruptible fair lock. One composition-root
  instance covers ordinary canonical confirmation, expiry and export-slot
  reconciliation; P7 will inject the same instance into import promotion.
- Database transaction ownership remains the correctness boundary. Stage hash,
  integrity and other file work happen before admission.
- `EventPublishingCanonicalImportWriter` publishes deadline, projection and
  artifact-change hints only after a newly committed delegate result. Receipt
  replay emits no duplicate hints, and event failure cannot change the durable
  commit outcome because existing reconciliation paths are authoritative.

## 5. Executable evidence

| Check | Result |
|---|---|
| SQL failure matrix | every injected phase before commit leaves both tested artifacts, aliases and receipt unchanged |
| post-commit crash | canonical effects and receipt survive; replay advances from receipt without stage access or reapplication |
| service saga seam | `PROMOTING` survives the crash and advances to `CANONICAL_COMMITTED` from the receipt |
| row atomicity | a conflict in one branch rejects the complete multi-artifact logical row |
| lifecycle semantics | authoritative clear advances public revision once; configured renewal extends TTL; disabled no-op renewal changes neither deadline nor revision |
| requested slots | exact, occupied fallback, artifact scoping, survivor preserve and strict rejection pass |
| post-commit events | new commit emits only applicable hints; exact replay emits none; publisher failure is non-fatal |
| compatibility | existing lifecycle, slot snapshot, workspace and schema-upgrade suites pass with dataframe schema v9 |
| static analysis | four actionable JDBC findings were fixed; one config-driven, grammar-validated and quoted identifier finding is narrowly accepted as `DATA-IMPORT-P6-SB-001` |

The focused contract corpus completed with `68` tests before the final slot
evidence additions; the expanded canonical writer/schema corpus completed with
`26` tests and then the writer contract with `11` tests, all green. The final
full-reactor result is recorded after the P6 commit so verification freshness is
measured against the exact committed P5/P6 tree.

## 6. Next boundary

P7 must compose the writer, event decorator, staging service and delivery ledger
behind one startup recovery barrier. It must then implement the authoritative
global drain, deterministic report reconstruction from v9 evidence, terminal
source/report publication, retention, status/health and graceful shutdown.
