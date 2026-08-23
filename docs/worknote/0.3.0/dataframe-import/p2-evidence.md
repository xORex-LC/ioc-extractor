---
title: "DATA-IMPORT-01 P2 evidence"
version: "0.3.0"
status: "Implemented with focused compatibility verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P2 evidence

## 1. Evidence boundary

P2 establishes the canonical identity, active-match and mutation foundation
used by ordinary ingest and by later dataframe-import promotion. It does not
start an import worker, create a delivery ledger, assign requested export slots
or expose an operator import command. Those capabilities remain P3+.

The implementation was split into four logical changes:

- `b4814055` introduced framework-free versioned record/match keys, collision-
  safe key material, zero/one/multi plans, mutation outcomes and identity TCKs;
- `79be64ea` added dataframe format v7, transactional shadow backfill,
  collision preflight and the set-based JDBC active-match planner;
- `fbb5cc36` made ordinary lifecycle writes use the shared connection-scoped
  mutation kernel and added update/clear/no-op behavior for later promotion;
- `97259f09` recorded only the exact reviewed dynamic-SQL analyzer identities
  after identifier validation, quoting and value-binding evidence was checked.

The full committed-HEAD `make verify` gate is executed after this evidence file
is committed. Repository verification evidence is authoritative for that final
gate; the checks below are the focused P2 evidence.

## 2. Delivered invariants

- A record key and each alternative match key have immutable names,
  fingerprints and an explicit identity epoch. Indexed SHA-256 material is
  always paired with canonical-material equality, so a digest hit alone is not
  a match.
- Dataframe v7 stores named definitions and active lifecycle aliases next to
  canonical truth. `address_blacklist` and `hashes` move to compound v2 record
  keys without changing public bytes, canonical IDs, lifecycle IDs, revisions
  or export-slot assignments.
- Startup stages every pending row key and alias in temporary shadow tables.
  All artifacts pass collision preflight before any durable identity is
  changed; one collision rolls back the complete migration and prevents the
  identity-validation dependency from admitting writers.
- Active matching is set-based for a request batch, snapshot-consistent on one
  JDBC connection and uses the strict lifecycle predicate
  `_valid_until_epoch_ms > asOf`. Zero, exact-one and multi remain explicit
  decisions; history is never searched.
- `JdbcCanonicalLifecycleWriter` and later import promotion share
  `JdbcCanonicalMutationEngine`. Ordinary ingest retains keep-first semantics,
  but now renews through configured aliases even when a producer supplies a
  different row key and maintains aliases transactionally on insert, renewal,
  restart and archive.
- Public mutation exposes `UPDATED`, `CLEARED`, `NO_OP` and `TTL_CONFIRMED`
  outcomes. Any record-key change, including clearing every key value, is
  rejected as an in-place mutation; it must become a new record.

## 3. Executable evidence

| Check | Result |
|---|---|
| focused key/match/lifecycle/writer tests | `22` tests passed: `3` application key-resolver tests plus `19` JDBC match, lifecycle TCK and writer tests |
| complete affected module verification | `218` application tests and `143` JDBC adapter tests passed with all upstream modules successful |
| schema and identity migration | dataframe migration/reconciliation tests include v7; `9` identity-store tests cover idempotency, immutable names, epoch backfill, identity preservation and whole-transaction collision rollback |
| active matching | zero/one/multi, exact-expiry exclusion and digest-without-canonical-equality rejection pass |
| shared ordinary writer | alternate producer row keys matching one configured alias renew one active lifecycle and cannot create an import-only duplicate |
| public mutation | update, explicit clear, no-op, optional TTL confirmation, alias rebuild and empty-record-key rejection pass |
| projection compatibility | committed golden resources retain aggregate SHA-256 `39de4ecde3b5501cc0313f734deb43662ae90c691e60bbaae66922a53bdab1c2` |
| analyzer boundary | affected JDBC module reports `0` visible SpotBugs findings after exact reviewed selectors; baseline schema validation reports `110` accepted identities and `106` selectors |

## 4. SQL trust evidence

`P2-SQL-TRUST`: every dynamic table or column fragment originates from a
validated immutable dataframe schema or versioned key definition and is passed
through `DataframeColumn.requireSqlIdentifier` before quoting. IOC values,
canonical material, row keys, IDs, lifecycle cutoffs, source keys and migration
selectors remain prepared-statement parameters. `SqlTrustBoundaryTest`, schema
reconciliation tests and the P2 collision fixtures exercise the rejection and
rollback boundaries. The accepted-finding entries are exact analyzer
identities, not wildcard package exclusions.

## 5. Compatibility and next boundary

P2 does not change public CSV schemas or enable import. Ordinary ingest keeps
the existing lifecycle, ID reservation, revision and projection semantics while
adding alias consistency. The next planned slice is P3 sparse preferred export
slots; it requires separate user authorization and must reuse the connection-
scoped transaction boundary rather than introduce an import-only allocator.
