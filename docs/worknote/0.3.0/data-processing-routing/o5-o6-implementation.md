# O5–O6 optimization implementation

Status: O5 implemented; O6 experiment and final qualification pending.
Starting reference: e3b9248c, branch module/platform/router, 2026-10-03.
Scope follows [the optimization plan](processing-optimization-plan.md) and
[Camel applicability](camel-optimization-applicability.md). O7 acceptance is separate.

## O5 ownership and static metadata

RouterProcessedImportRowPreparer now receives the immutable compiled contract at
construction. It validates artifact/input/output authority once, indexes artifact
definitions and binds effective output merge policies. Each call still rejects
contract drift (including the same ID with a changed definition/fingerprint),
unknown admitted branches and missing input cells. Related branches, ABSENT/NULL,
conditional source authority, carrier clearing, compound conflicts, primary
output requirements and warnings retain their existing owners and semantics.

The compiler captures registered operation/destination processor references.
Selector trace outcomes use constant labels instead of per-decision lowercase
conversion; observer exceptions remain isolated by RoutingTraceSink. MDC remains
active even when decision tracing is disabled. The existing generic MdcScope
restores through its sequenced map's reversed entries, removing close-time
stream/list construction without adding a second MDC implementation.

The retained O4 diagnostic document recording attributes sampled allocation
weights to executeAdmitted (107.9 MB), PlanSelection construction (76.8 MB),
MdcScope.put (7.9 MB) and MdcScope.close (5.2 MB). These are sampling weights from
an instrumented recording, including incidental setup; not exact allocation
counts or performance acceptance. Mapper/provider/transform metadata binding and
lazy failure containers are deferred: this profile does not establish a useful
isolated gain for them. Both paths retain the single ConfigurableRowMapper.

Focused regressions cover full-contract drift, per-row admission, static authority,
compound carrier and source contracts, nested MDC, repeated writes, unowned keys,
exception restoration and Camel trace/concurrency contracts. No dependency,
module, durable schema, policy or configuration knob changes are introduced.

## Isolated MDC mechanism check

Three alternating before/after fresh JVM pairs exercise 20,000 warm-up and
100,000 measured three-key scopes (`-Xms128m -Xmx512m`), checking the in-scope
view and ambient restoration. Before uses the e3b9248c compiled MdcScope; after
uses the source hash and standalone source retained in
[the isolated report](qualification/optimization/o5-mdc-isolated.json).
Median calling-thread allocation is 53,990,184 → 36,766,712 bytes (31.9% lower)
and elapsed time 54.6 → 41.6 ms. This qualifies removal of the close-time list
in that mechanism, not an application speedup or a whole-process memory claim.
Raw logs and the reproducible source remain under .dev/o5-mdc-probe and
.dev/O5MdcProbe.java; the report embeds the source independently of those files.

## Qualification

Primary comparisons, diagnostic counters and final quality evidence will be
recorded below before this slice is considered qualified. Raw recordings and
failed experiments stay under ignored .dev workspaces.

O5 checks: focused import, Camel and MDC tests passed; `make verify`,
`make pmd-analysis`, `make pmd-watchlist` and `make docs` passed. The adopted
PMD pass followed removal of two newly unused private method parameters.
Raw SpotBugs (120 accepted), CPD (24), PMD policy (24) and watchlist (30)
reports were reviewed; no new finding or ratchet adjustment was introduced.
The final combined slice will be verified again on its committed HEAD.
