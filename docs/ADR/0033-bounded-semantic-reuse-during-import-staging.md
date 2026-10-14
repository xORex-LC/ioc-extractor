# ADR 0033: Bounded semantic reuse during import staging

- Status: Accepted
- Date: 2026-10-03
- Supersedes: the no-eviction admission policy and row-only import scope in
  [ADR 0032](0032-invocation-owned-ioc-semantic-reuse.md)

## Context

Mass URL-to-host workloads exhaust first-admission caches with distinct original
URLs. Later adjacent repeats and hot derived hosts under a new source then miss
permanently. Row-local import scopes cannot reuse successful computations across
CSV participants. Neither limitation justifies skipping mapping or staging rows.

## Decision

Keep invocation-owned, thread-confined semantic state and the same complete
classification key and pinned-policy identity. Divide the existing aggregate
256-entry / 1 MiB charged budget equally between classification and successful
host derivation. Each partition evicts least-recently-used entries on admission.
Original and derived classifications share the classification partition: frequent
derived hosts stay hot, while cold original URLs can be replaced. Explicit odd
limits give the remainder to classification. A 64 KiB per-entry ceiling remains;
an entry larger than either ceiling bypasses the cache without evicting entries.
No failure, row, diagnostic, clock, position or canonical key is cached.

Application staging opens one processed preparation session after recognition and
contract-pin validation. It maps every row and performs source-authority checks
through that same preparer, then closes the session before sealing the workspace.
Read, mapping and workspace-append failures close it too. Each restaging attempt
opens a new session. As-is imports bypass processing sessions; receipt-only
recovery and reuse of an already sealed stage never enter staging preparation.

The selected router preparer supplies the bounded semantic session. Other
preparers use a stateless forwarding adaptation preserving both preparation and
source-authority behavior. Direct single-row preparation remains supported and
owns a row-local scope. Advisory validation retains that existing row-local path.

## Consequences

The aggregate admission ceilings do not increase. Eviction adds bounded metadata
and insertion work on unique streams, so repeated, unique and host-collapse
workloads must be measured independently. Charge is conservative accounting, not
an exact JVM heap ceiling. Concurrent attempts each own their own allowance.

Import participants, COALESCE, canonical receipts, ID reservation, failure policy
and recovery authority are unchanged. There is no external cache, new dependency,
operator configuration or storage migration. The caches remain an optimization:
misses and eviction always fall back to the same semantic computation.
