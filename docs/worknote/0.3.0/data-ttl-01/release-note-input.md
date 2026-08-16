---
title: "DATA-TTL-01 release-note input"
version: "0.3.0"
status: "Curated input"
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
  new lifecycle and a new service-owned public ID.
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

## Known operational boundary

- Expiry updates mutable projections but does not push an empty immutable slice
  to downstream systems. Delivery/removal semantics remain the responsibility
  of a separately versioned downstream integration contract.

## Technical references

- [ADR-0020](../../../ADR/0020-canonical-record-expiration-lifecycle.md)
- [operator guide](../../../guides/canonical-record-lifecycle.md)
- [capability documentation](../../../dev/canonical-record-lifecycle.md)
- [P6 load evidence](evidence/p6-load-profile.md)
