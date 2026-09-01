---
title: "DATA-TTL-01 release-note input"
version: "0.3.0"
status: "Draft — lifecycle packaged qualification complete"
document_type: "Release-note input"
source_of_truth: false
language: "en"
---

# DATA-TTL-01 release-note input

This is the curated lifecycle section for the eventual complete 0.3.0 release
notes. It is not a release candidate and intentionally contains no guessed tag,
artifact checksum or whole-release verification claim.

## Added

- Canonical IOC records can use a fixed validity period. Successful canonical
  confirmation renews an active record; an observation after expiry creates a
  new lifecycle with a new internal lifecycle identity.
- Expired rows leave active SQLite membership and mutable CSV projections, move
  to typed bounded history, and are later removed by independent retention.
- Aggregate lifecycle health reports activation, clock safety, due/history
  counts and projection backlog without exposing IOC or source identifiers.

## Changed

- Fresh installations made from the production packaging template enable fixed
  validity with a 12-hour lifetime. Embedded/classpath and existing-installation
  defaults remain disabled for compatibility.
- Expiry does not advance the insert-driven artifact revision and does not
  create an immutable export slice. The next new-row-driven export contains
  only records active at its snapshot time.
- For artifacts with an external `id`, 0.3.0 preserves
  each surviving record's stable export slot, releases slots of records absent
  from the active set, and assigns the smallest free slots to new lifecycles.
  Slots are not canonical identities and existing rows are never renumbered to
  close gaps. P7 migration, race, 100k, immutable-slice and packaged
  qualification evidence has passed.
- A newly accepted lifecycle produces a new immutable slice and delivery when
  its covered revision advanced, even if reusable slots make the resulting CSV
  byte-identical to an older slice. Equal plan, bytes and covered revisions may
  still be skipped.
- The five-second lifecycle backstop now refreshes only the indexed nearest
  deadline until work is due. Reconciliation keeps one bounded checkpoint,
  history/receipt cleanup runs on an independent hourly cadence, and successful
  no-op reconciliation/projection checks no longer emit INFO events.
- Public CSV/export columns remain unchanged. `time_first_seen` and
  `time_last_seen` remain `NULL`; internal `valid_until` is not exported.

## Upgrade notes

1. Back up the matching binary, complete configuration, dataframe SQLite and
   service SQLite as one recovery point.
2. Upgrade once with lifecycle validity still disabled and verify health.
3. Stop intake, then explicitly select `mode: fixed`, a positive `fixed-ttl`
   and `existing-records: expire`.
4. Restart and accept that all legacy rows are archived before new intake; the
   active set and mutable projections may be empty.

Activation is one-way for that dataframe database. After it starts, rollback
requires restoring the pre-activation binary, configuration and both SQLite
databases together; switching the YAML back to `disabled` is rejected.

The P6 candidate stand exercised the compatibility start, explicit activation,
activation rollback, complete release rollback and a clean fresh installation.
Its monotonic exported-ID result remains historical characterization. The P7
slot migration and P8/P9 delivery/runtime corrections are implemented and
covered by automated replacement evidence; affected packaged assertions and a
fresh final gate must still pass before these notes are release-ready.

## Known operational boundary

- Expiry updates mutable projections but does not push an empty immutable slice
  to downstream systems. Delivery/removal semantics remain the responsibility
  of a separately versioned downstream integration contract.

## Technical references

- [ADR-0020](../../../ADR/0020-canonical-record-expiration-lifecycle.md)
- [ADR-0021](../../../ADR/0021-stable-reusable-export-slots.md)
- [ADR-0022](../../../ADR/0022-revision-significant-identical-export.md)
- [ADR-0023](../../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md)
- [export-slot correction](export-slot-correction.md)
- [operator guide](../../../guides/canonical-record-lifecycle.md)
- [capability documentation](../../../dev/canonical-record-lifecycle.md)
- [P6 packaged execution evidence](evidence.md#privileged-packaged-systemd-stand-2026-08-1819)
- [P6 load evidence](evidence/p6-load-profile.md)
- [P7 automated evidence](evidence.md#p7--reusable-export-slot-correction)
- [P8 identical-delivery evidence](evidence.md#p8--revision-significant-identical-export-delivery)
- [P9 bounded-runtime evidence](evidence.md#p9--bounded-idle-lifecycle-runtime)
