# CAP-3 execution: attribution and construction-time diagnostics

Scope: CAP-3 in [the capacity plan](data-processing-capacity-plan.md), following
the completed CAP-2 baseline `c31da8c3`. CAP-4 and later stages remain separate.

## Attribution

Marker discovery retains its ordering: earliest start, longest span, configured
pattern order, then non-overlapping selection. Input occurrences retain their
encounter order. Non-decreasing positions (including equals) use one advancing
cursor; unordered positions use binary search for the last marker at or before
the occurrence. The lookup costs are `O(N+S)` and `O(N log S)` respectively,
after marker discovery. Counting unattributed occurrences reads decisions and
does not materialize an additional list of attributed indicators.

Regression coverage includes inclusive boundaries, no marker, unordered input,
equal positions, adjacent/overlapping markers and seeded randomized fixtures
against an independent linear oracle. The diagnostic agent counts actual marker
position comparisons; primary timing forks do not attach the agent.

## Diagnostics

The current runner already bounds retention and sink delivery, but extraction
and document preparation construct full diagnostic lists before returning.
Construction-time collectors now carry retained samples and exact severity
counts as separate facts in `DiagnosticBatch`. A local collector keeps the first
`limit` ELEMENT/RUN occurrences and, when needed, up to two additional first
ERROR/FATAL representatives in encounter order. It does not displace an early
sample: doing so before global merging could change the original run sample.
The global runner merges omitted counts, applies its original retention and
delivery rules and creates one terminal synthetic summary, excluded from
observed totals. Low-cardinality OPERATION diagnostics remain budget-exempt.
Collectors are owned by synchronous stage invocations, not shared run state.

Managed import has a separate participant-warning and receipt contract. Its
complete detail requirements must be checked before changing any import path.
No complete-detail document diagnostic spool is part of the supported contract:
the document outcome and sink are explicitly sampled. Import's retained accepted
participant warnings already stream through the existing private SQLite stage
and canonical receipt. Warning detail has an existing cap at
`ImportWorkspaceLimits.maximumRowErrors()` (default 100,000 per stage); above
that cap the current writer omits further warnings. This is separate from
document diagnostic counts, and CAP-3 does not claim full import warning detail
beyond that cap. Workspace byte limits and explicit storage failures remain
the authority. Adding a second spool would duplicate ownership and introduce
new disk consumption without a consumer requirement. CAP-3 changes neither
import warnings nor receipt schema/recovery.

## Validation

Attribution focused checks: five domain invariant tests and ten comparison
instrumentation tests passed. The comparison test verifies both lookup bounds
on 100,000 occurrences and 400 sections. Existing regex marker fixtures are
also included in the focused validation.

Pending high-volume diagnostics qualification and final
`make verify` / `make pmd-analysis` evidence. This file records progress rather
than declaring G3 passed.
