---
title: "DATA-IMPORT-01 P4 evidence"
version: "0.3.0"
status: "Implemented with focused compatibility verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P4 evidence

## 1. Evidence boundary

P4 implements durable delivery ordering/recovery and the recognition-to-sealed
stage boundary. It does not claim or monitor source files (P5), read/write
canonical import state (P6), or expose operator status/report/finalization (P7).
The existing runtime therefore remains disabled by default.

`as-is` mapping is executable. `processed` fails closed at the mapper boundary
until its dedicated framework-free preparation strategy is connected; it never
falls through to `as-is` behavior.

Implementation commits are:

- `1698320c` — service schema v9 and durable delivery ledger;
- `0e3cc22e` — restart recovery coverage through every pre-promotion state;
- `936705d8` — strict recognition/mapping and sealed SQLite staging;
- `d6a9d135` — reviewed static-analysis baseline reconciliation.

## 2. Durable ledger evidence

- Service schema v9 adds occurrence-keyed `import_delivery`, append-only
  transition audit and indexed global sequence/retry/head queries.
- Admission allocates one monotonic global sequence. Equal content remains a
  distinct delivery occurrence.
- State changes are compare-and-set on delivery ID, expected state and version;
  invalid transitions and stale writers do not mutate durable state.
- Snapshot, contract, stage and terminal checkpoints are state-compatible and
  immutable once stored.
- Restart tests reopen SQLite after each transition from `DETECTED` through
  `PROMOTING` and recover the same ordered head.
- Retry is timestamp-driven and persisted; no blocking sleep or executor order
  participates in correctness.

## 3. Recognition, mapping and staging evidence

- Recognition filters only source-allowlisted contracts, parses their declared
  charset/dialect/header signature and requires exactly one match without file
  names, priorities or scores.
- The Commons CSV adapter uses strict decoding, validates declared line endings
  and exact alias-resolved headers, and streams rows synchronously.
- Row/column plus pre-tokenization decoded field/logical-record limits stop
  parser growth. Safe failures do not expose paths, headers or cell values.
- Mapping preserves `ABSENT`, `NULL` and `VALUE`, runs only declared transforms,
  removes requested slot from business identity, resolves named record/match
  keys and rejects all branches when one branch fails.
- Workspace schema v1 stores rows, branches, tri-state cells, match keys and
  safe row errors in bounded JDBC batches. Opaque delivery-derived filenames
  prevent traversal and raw delivery identifiers leaking into paths.
- `coalesce` is set-based and commutative. Compatible cells and requested slots
  combine without a row-order winner; null/value, unequal value or requested
  slot conflicts reject one logical group. `keep-first` retains the smallest
  physical row by explicit policy.
- Sealing creates lookup indexes, finalizes counts, checkpoints WAL, runs SQLite
  integrity checking, forces the file, atomically renames it and pins SHA-256.
  Verification reopens the expected stage read-only and checks digest, metadata
  and integrity.
- Hard source-row/branch/cell/error/stage/workspace bounds and hysteretic
  pause/resume capacity state fail before unbounded growth.

## 4. Executable evidence

| Check | Result |
|---|---|
| focused P4 tests | `51` passed: `10` application, `12` CSV adapter and `29` JDBC adapter tests |
| restart matrix | durable reopen succeeds after every state from `DETECTED` through `PROMOTING` |
| deterministic duplicates | compatible coalesce produces equal accepted cells under row/column permutations; conflict groups share one logical group |
| sealed-stage recovery | an unsealed workspace requires explicit rebuild; mismatched digest fails closed |
| low-heap reference load | `100,000` staged rows pass under `-Xmx128m`; measured staging time was `4.593s` on the development host |
| sparse slot compatibility | P3 focused slot/migration tests remain included in the affected storage module |
| static analysis | affected production modules report `0 new` and `0 stale` SpotBugs baseline findings |

The final full-reactor result is recorded by repository verification evidence
after this documentation is committed; this note does not pre-claim that gate.

## 5. Next boundary

P5 may provide local claim/snapshot ownership and assemble these components,
but it must keep intake ordered by the durable head and may rebuild only from
pinned immutable snapshot evidence. P6 must attach the sealed stage read-only
and establish business truth only through one dataframe transaction and its
idempotency receipt.
