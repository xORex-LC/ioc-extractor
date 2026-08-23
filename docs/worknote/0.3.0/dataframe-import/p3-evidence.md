---
title: "DATA-IMPORT-01 P3 evidence"
version: "0.3.0"
status: "Implemented with focused compatibility verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P3 evidence

## 1. Evidence boundary

P3 extends the existing ADR-0021 export-slot registry with bounded sparse free
space and preferred import requests. It does not create an import delivery
ledger, parse or stage a CSV delivery, or write canonical import mutations.
Those capabilities begin in P4 and P6.

The implementation change is `4ecab025`. The final committed-HEAD
`make verify` gate is scheduled after P4 so it covers the complete authorized
P3-P4 span. The checks below are the focused P3 evidence.

## 2. Delivered invariants

- Dataframe format v8 coalesces legacy `export_slot_free` rows into closed
  ranges without changing any assignment, durable high-water, generation or
  policy state.
- The same connection-scoped registry serves ordinary export reconciliation
  and preferred import allocation. The caller owns the surrounding canonical
  transaction; registry changes therefore roll back with canonical mutations.
- A free requested slot is assigned exactly. An occupied requested slot uses
  the existing smallest-free-positive rule. A survivor keeps its stable slot by
  default or fails under explicit `reject-mismatch`; automatic renumbering is
  absent.
- Every duplicate requested-slot group is validated before registry mutation.
  Range operations split at the start, middle or end and merge adjacent or
  overlapping releases.
- Allocation cost depends on affected lifecycles and coalesced ranges, not the
  numeric magnitude of a requested slot. Slot `1_000_000_000` produces one
  preceding free range instead of one billion rows.

## 3. Executable evidence

| Check | Result |
|---|---|
| complete affected-module verification | `149` JDBC adapter tests passed; all `11` projects in the focused reactor were successful |
| migration compatibility | v7-to-v8 fixture verifies coalescing while assignments, state and ordering remain unchanged |
| ADR-0021 regression | all `16` snapshot/slot tests remain green, including the `100k` lifecycle streaming scenario |
| preferred-slot policies | `5` dedicated registry tests cover exact sparse assignment, occupied fallback, survivor preserve/reject, duplicate groups and range split/merge |
| bounded sparse request | slot `1_000_000_000` completes under the focused five-second guard with exactly one stored free range |
| query plan | containment lookup uses the range index and the regression rejects a full `export_slot_free_range` scan |
| atomicity | forced caller rollback restores canonical rows, assignments, ranges and registry state together |
| static analysis | affected-module SpotBugs gate completed with no new visible finding |

## 4. Transaction and complexity evidence

`JdbcExportSlotRegistry` accepts a caller-owned JDBC connection and never
commits it. Temporary request/release/pending tables are connection-local. The
set-based ordinary allocator maps pending lifecycles to cumulative range
capacity and advances high-water only for the remainder. Preferred requests
remove one point from a containing range, splitting it at most into two rows.

The schema has a primary key on `(profile, artifact, range_start)` and a second
index beginning with `(profile, artifact, range_end)`. The tested lookup plans
therefore remain bounded for both smallest-range and point-containment access.

## 5. Compatibility and next boundary

P3 changes no public CSV schema or canonical identity. Existing survivor/reuse
semantics and slot policy version remain unchanged; only the internal free-space
representation changes. P4 may now persist ordered delivery occurrences and
sealed disk-backed stages, but must not promote staged rows into canonical truth.
