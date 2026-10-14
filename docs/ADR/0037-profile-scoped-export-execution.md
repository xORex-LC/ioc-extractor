# ADR-0037: Profile-scoped export execution

Status: Accepted
Date: 2026-10-05

## Context

Complete slices remain atomic within each export profile. A shared formation lock,
active-run singleton and CSV writer monitor previously serialized unrelated profiles.
A slow GDI profile could therefore delay otherwise ready reputation outputs.

## Decision

Serialize formation and recovery by profile with cross-process NIO leases and a
partial unique active-profile index in service schema v13. Formation recovers only
its leased profile. Startup recovery visits every incomplete profile, including
profiles removed from configuration, before scheduling is admitted.

The daemon uses two bounded workers and coalesces hints to at most one outstanding
attempt per configured profile. Snapshot transactions share the configured reader
budget. Clock and slot write transactions remain outside CSV streaming. Mutable
canonical transactions, slot namespaces, per-profile plans, quiet/max-cap cadence,
retention pins and endpoint-keyed SMB publication keep their existing contracts.
The CSV writer holds invocation-local materialization state and relies on the
profile operation lease rather than an instance-wide monitor.

## Consequences

An available second worker can complete a ready profile while another profile is
blocked in streaming. No scheduler can bypass held SQLite writer ownership. Long
WAL snapshots still pin checkpoint progress and must be measured.

Stop the old process and take coordinated storage backups before upgrading. Mixed
old/new formation binaries are unsupported: the old binary uses a global file lock
and cannot open service schema v13. Rollback requires restoring the backup, not
rewriting `user_version`. Downgrade refusal remains enforced by the migrator.

## Validation

Controlled blocking tests must prove completion of the independent profile before
release of the blocked profile, bounded outstanding work, same-profile exclusion,
profile-scoped recovery, migration preservation and bounded worker termination.
