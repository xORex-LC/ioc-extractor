# CAP-3 execution: attribution and construction-time diagnostics

Scope: CAP-3 in [the capacity plan](data-processing-capacity-plan.md), following
the completed CAP-2 baseline `c31da8c3`. CAP-4 and later stages remain separate.
Implementation commits: `13c513c6` (attribution), `1930f2f0` (diagnostics),
`4d77bfed` (measurement harness), `3bb6d6dd`/`94a82fa4` (compiler portability),
`60d33391` (boundary contracts and reviewed analyzer anchors).
The focused measurement candidate is `94a82fa4`, with a clean source tree;
subsequent changes add tests, reviewed analyzer anchors and documentation, with
no production or measurement mechanism changes.

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

Before CAP-3, the runner already bounded retention and sink delivery, but
extraction and document preparation constructed full diagnostic lists before
returning.
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

## Focused measurements

[Raw samples and frozen identities](qualification/capacity/cap-3-stages.json)
contain 60 primary JVM forks: five alternating before/after pairs for each of
four 100k profiles and two 1m attribution profiles. Each fork runs one full
warmup; measured work has no Java agent. Common compiled dependencies are
frozen, and the seven changed mechanism classes are compiled from baseline and
candidate sources into separate overlays. Every result signature matches across
revisions and pairs. Five offline harness contracts reject semantic mismatch,
missing/duplicate samples and partial reports, and verify cleanup after failure.

Reproduce after compiling the bootstrap test probe on a committed source tree:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=ProcessingRouteComparisonTest
make processing-stage-capacity STAGE_CAPACITY_ARGS='--baseline c31da8c3 --output .dev/cap3-stages.json --counts 100000 --large-attribution-count 1000000 --pairs 5'
```

| Profile | Items / sections | Median before → after | After / before |
|---|---|---|---|
| Ordered attribution | 100,000 / 400 | 21.022 → 8.569 ms | 0.408 |
| Unordered attribution | 100,000 / 400 | 18.433 → 13.979 ms | 0.758 |
| Ordered attribution | 1,000,000 / 4,000 | 1,688.543 → 70.344 ms | 0.042 |
| Unordered attribution | 1,000,000 / 4,000 | 1,119.267 → 146.477 ms | 0.131 |
| Extraction diagnostics | 100,000 | 48.996 → 40.260 ms | 0.822 |
| Preparation diagnostics | 100,000 | 66.406 → 59.736 ms | 0.900 |

Attribution has 250 occurrences per section, at the inclusive boundary;
unordered input uses seed 302031. Diagnostic fixtures reuse their input decision
carrier but construct actual diagnostics per item. Preparation includes a final
ERROR after 99,999 WARN occurrences. The runner budget is 128. These isolate
the mechanisms and are not HTML/DOCX, production-Spring or full-service timings.

| Diagnostic profile | Stage-retained details before → after | Caller allocation before → after (decimal MB) | Sampled current RSS before → after (MiB) | Sampled phase heap before → after (MiB) |
|---|---|---|---|---|
| Extraction | 100,000 → 128 | 81.3 → 77.6 | 132.1 → 124.7 | 51.6 → 61.7 |
| Preparation | 100,000 → 129 | 80.1 → 78.6 | 157.0 → 131.4 | 59.7 → 65.7 |

Construction-time retained detail is bounded. Total per-item allocations remain:
the semantic adapter still constructs each diagnostic before the collector
records or omits it. Sampled heap includes garbage awaiting GC and is **higher**
in these diagnostic medians; this is not evidence of a lower whole-process heap
budget. Current RSS is sampled at 10 ms; sub-10 ms work can escape the sampling
interval. Post-GC heap includes input fixtures and the final runner outcome,
not the stage's discarded intermediate list. Attribution output decisions also
remain materialized. CAP-4/CAP-7 and CAP-6 own the remaining preparation/input
memory architecture and whole-service resource qualification.

The probe creates no database. Temporary source/class snapshots are removed on
success and failure; no `cap3-stages-*` or `cap3-smoke-*` directory remained after
qualification. Only the compact JSON report is retained. The first two driver
attempts stopped before any measurement because the installed Java lacks a
`javac` PATH launcher and `ct.sym`; invoking the compiler module with explicit
source/target 21 resolves both environment restrictions. No failed attempt is
included in the 60 primary samples.

## G3 validation

Attribution focused checks: five domain invariant tests and ten comparison
instrumentation tests passed. The comparison test verifies both lookup bounds
on 100,000 occurrences and 400 sections. Existing regex marker fixtures are
also included in the focused validation.

| Task | Scoped result | Evidence |
|---|---|---|
| CAP-3.1–3.2: attribution and counting | PASS | Independent seeded linear oracle, inclusive/equal/unordered/missing/overlapping-marker fixtures; instrumented actual lookup work is at most `N+S` ordered and `N*(32-numberOfLeadingZeros(S))` unordered; no extra attributed-indicator list for counting |
| CAP-3.3: construction-time bounds | PASS | 100k extraction/preparation outcomes retain bounded detail with exact totals; losing duplicate/mapping warnings still count; concurrent invocations own separate collectors |
| CAP-3.4: complete detail and failure ownership | PASS (contract review; no new spool) | Document outcome/sink are sampled; import warning workspace/byte limits remain unchanged; invalid batches and delivery failures propagate, and the harness removes private snapshots on failure |
| CAP-3.5: checkpoint, delivery, receipts | PASS | Late ERROR under fail-fast and late FATAL under collect-and-continue produce no repository calls and reserve no IDs; one terminal summary; randomized local-batch/global-stream samples, counts and delivery match; 21 workspace and 21 canonical-import contract tests pass, including COALESCE warnings and receipt-only replay |

G3 is a mechanism/semantic gate. It does not accept the remaining whole-service
RSS, writer-hold or full SMB-cycle budgets.

## Release checks and analyzer review

The first full verify passed functional tests but stopped at diagnostics module
line coverage (550/564 against the existing 481/491 ratio) and three moved
SpotBugs anchors. Added tests exercise the actual batch/count contract:
independent stage totals, exact deltas, rejection of removed severity counts,
immutable snapshots, synthetic-summary exclusion, invalid budgets and hidden
rejecting signals. A second full verify exposed missing ETL branch coverage;
two runner regressions now verify resuming an already bounded envelope without
counting its synthetic summary, and rejecting removal/replacement of observed
diagnostics. No coverage floor, ratchet, test-universe count or exclusion was
changed.

SpotBugs `SB04-101`–`SB04-103` retain their hash, occurrence, type, priority,
rank, disposition and selectors. Only source/bytecode anchors moved from
147/238, 152/290, 161/362 to 150/257, 155/309, 164/381. The rethrows still
preserve the original propagated exception object; checked observer-close
failure wrapping still preserves its cause. Existing `PipelineRunnerTest`
failure/observer/summary tests revalidate those paths. Raw aggregate findings
and the exact XML acceptance diff were reviewed. PMD watchlist's existing
`PreserveStackTrace` signal on the wrapper unwrapping at line 147 has the same
disposition: `StageProcessingFailure.propagated()` is the original exception,
not a replacement without cause. No new suppression is introduced.

The completed full verify passes test-report integrity, architecture and
coverage gates: 222 fast / 73 integration / 5 property-gated external suites;
290 deterministic-offline suites. Aggregate coverage is 24,579/26,982 lines
(91.09%) and 8,636/10,393 branches (83.09%). Diagnostics covers 557/564 lines
and 113/122 branches; ETL covers 179/194 lines and 20/26 branches, exceeding
their unchanged ratchets. Offline skips are not provisioned SMB evidence.

SpotBugs has 126 accepted / 0 visible findings; the reviewed proposal has
0 new / 0 stale identities. CPD remains 24 groups and none touches changed
production classes. Adopted PMD has 0 blocking / 20 advisory findings; watchlist
has 44, with the runner signal reviewed above. Tool contracts (28 existing
capacity/comparison plus five new stage-harness tests) and packaging pass.
Completion runs `make verify` and `make pmd-analysis` again after the final
documentation/evidence commit, and checks their exact-HEAD freshness through
`make context`. Final command status is retained in local verification metadata;
the committed measurements keep their independently frozen candidate identity.
