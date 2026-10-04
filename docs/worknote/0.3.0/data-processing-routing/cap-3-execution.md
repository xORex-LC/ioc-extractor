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

## Diagnostics design in progress

The current runner already bounds retention and sink delivery, but extraction
and document preparation construct full diagnostic lists before returning.
Construction-time collectors will carry retained samples and exact severity
counts as separate facts. The global runner must merge those facts without
counting a synthetic suppression summary as another occurrence, and must
preserve the rejecting error/fatal signal before canonical writes.

Managed import has a separate participant-warning and receipt contract. Its
complete detail requirements must be checked before changing any import path.
No new diagnostic disk spool is justified solely by a sampled pipeline result.

## Validation

Attribution focused checks: five domain invariant tests and ten comparison
instrumentation tests passed. The comparison test verifies both lookup bounds
on 100,000 occurrences and 400 sections. Existing regex marker fixtures are
also included in the focused validation.

Pending high-volume diagnostics qualification and final
`make verify` / `make pmd-analysis` evidence. This file records progress rather
than declaring G3 passed.
