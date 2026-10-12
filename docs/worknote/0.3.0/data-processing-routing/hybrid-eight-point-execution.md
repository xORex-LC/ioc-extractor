# Eight-point SQLite hybrid optimization cycle

Owner authorization: 2026-10-12. Source baseline: `46cb4ab3` plus the existing
direct ACK, compact journal and immutable receipt worktree. Native remains the
production default. PostgreSQL, weaker durability and canonical multi-file
transactions are excluded.

Raw qualification reports, logs and samples are local and Git-ignored at the
owner's request. Historical summary tables remain in the experiment documents.
Reproducible probes, policies and frozen acceptance definitions remain versioned.
This cycle writes new raw results under `.dev/hybrid-eight/`; reviewed summaries
and failure dispositions belong here. No historical resource budget is silently
reinterpreted as acceptance.

## Execution contract

Implement points in order and freeze each executable before measuring it.
Every point runs a real daemon document and processed-import workload at 100,000
and 1,000,000 inputs, including canonical fields/keys/provenance, receipts,
mutable projections and immutable export slices. External SMB delivery is outside
the local cycle. Failed windows stay failures. One fork per cell is diagnostic
evidence, not a tail-distribution or production SLO claim.

The owner deferred reactor verify, coverage and static-analysis gates for this
optimization cycle. Compile only the executable and qualification helpers needed
to measure it. Preserve test sources and semantic oracles; do not weaken gates,
alter their baselines or claim deferred checks passed.

## Progress

Owner steering now prioritizes a finite production choice on the 4-vCPU/8-GiB
reference. See [production capacity closure](production-capacity-closure.md).
The old-envelope intermediate measurement queue is stopped; its million-document
collector interruption remains unsuccessful evidence. Implementations and frozen
binaries are retained. The table below records the original matrix, not a claim
that all eight intermediate measurements have completed.

| Point | Implementation | 100k / 1m qualification |
|---|---|---|
| 1. Control phase attribution | Implemented; frozen binary | Two expanded 100k passes; 1m interrupted |
| 2. Direct control admission | Implemented; frozen binary | Deferred terminal comparison |
| 3. Streaming receipt positions | Implemented; frozen binary | Deferred terminal comparison |
| 4. Bounded transfer/bulk work | Implemented; frozen binary | Deferred terminal comparison |
| 5. Index/statistics/physical layout | Implemented; frozen binary | Deferred terminal comparison |
| 6. Cleanup debt and useful service | Implemented; frozen binary | Deferred terminal comparison |
| 7. Retained publication evidence | Implemented; frozen binary | Deferred terminal comparison |
| 8. Complete lifecycle comparison | Probe and final composition frozen | Native giant/batch/import and corrected strict batch passed; Staged giant post-diagnostic ERROR; finite comparison finished |

## Measurement boundaries

Record actual input counts, final rows, policy and binary digests, allocations,
heap/RSS, writer wait/hold, control phases, receipt scans, physical family debt,
DB/WAL, cleanup progress and failure/recovery outcomes. Do not merge local
completion with post-publication diagnostic scan/cleanup time. Reuse the existing
whole-service independent oracle and Spring composition. New pure ACK probes
must have their own identity; the historical combined error/ACK probe remains
unchanged.

## Implemented mechanisms and limitations

1. Unit-level monotonic timing splits preparation gate, workspace admission,
   native schema, hydration, preparation, descriptor, transfer, connection,
   transaction and commit. Default observation remains no-op; no IOC logging.
2. Direct projection controls do not acquire the private-control gate. Shared
   writer ownership, safe time, epochs, CAS and synchronous failure persistence
   remain unchanged. A paused prepared control must rebuild after an effective ACK.
3. Receipt rows and field positions use two ordered cursors in one transaction.
   No per-row SQL query; no receipt-sized map. Missing positions and pinned readers
   retain their existing contract.
4. Descriptor sizing retains scalar counters instead of chunks/addresses; the
   actual transfer retains exact checksums. Contiguous table effects use checked
   batches within the existing 256-effect/byte transaction ceiling. Two source
   walks remain: removing the manifest contract or writing another disk journal
   would introduce additional complexity.
5. Fresh candidate layouts omit exact copied key/lookup duplicates, preserving
   all constraint/visibility mechanisms. Existing layouts are not altered on reopen.
   Maintenance owns bounded SQLite planner optimization at hourly cadence.
   Generic WITHOUT ROWID conversion is not applied: rowid-based reclamation and
   INTEGER/AUTOINCREMENT identity are current invariants.
6. Reclamation uses one set-based DELETE over a preselected bounded address
   interval. Cursors and useful-service counters commit together. Read-only
   qualification census distinguishes eligible rows from protected rows. The
   fair writer scheduler is reused. Automatic quotas require observed service
   rates and a declared incoming workload; no arbitrary millisecond SLO is adopted.
7. Physically unreferenced committed document/import headers become compact
   permanent replay proofs. Identity/pins/receipt authority remains, including
   a transactional insert guard and reopen capability pins. Cancelled promotions
   remain retryable. This reduces retained overhead, not lifetime retention to zero.
8. Fixed local workloads retain fields/keys/provenance/slices/receipt evidence,
   physical cleanup census and separate post-publication pure ACK/error probes.
   Versioned ordinary receipts remain the conservative composition until the
   immutable candidate's lifecycle cost has been compared. There is no production
   activation or claim of sustained cleanup equilibrium from a finite window.

## Execution evidence so far

- Host UTC preflight initially failed with a 2.485-second backward step before
  admission. The owner stopped timesyncd; the following 90-second preflight was
  stable. Application safe-clock policy is unchanged.
- The first fast compilation omitted `-parameters`; Spring rejected the ACK
  probe parameter. Failed/aborted windows remain local. Frozen adapter binaries
  were preserved while helper parameter metadata was corrected.
- 18 focused semantic checks passed in 16.833 seconds, outside reactor gates:
  direct ACK versus paused preparation, both receipt layouts, pinned-reader
  retirement, sparse positions, replay-proof compaction/reopen and rejection of changed pins.
- Point 1, 100k unique document: local completion 311.994 seconds. Subsequent full
  receipt-read diagnostic exceeded the legacy HTTP timeout; qualification is
  **INCOMPLETE**, not a semantic/resource pass. Future full post-publication scans
  use a separately declared 900-second collection timeout. Historical failure
  remains; timing is not represented as full qualification.
- Point 1, 100k processed collapse import: 27.747 seconds and CORRECTNESS_PASS.
  Input, final-key/provenance and committed import evidence checks passed.

Raw windows: `.dev/hybrid-eight/point-1-measured/`; earlier clock/compiler failures:
`.dev/hybrid-eight/point-1/` and `.dev/hybrid-eight/point-1-stable/`. Frozen runtimes
are stored separately per point. These paths are local artifacts, not Git evidence.

### Explicit expanded disk profile

The original million-input unique document failed in PREPARE_ARTIFACTS with
SQLITE_FULL before canonical promotion. The private profile inherited the
classpath 2 GiB workspace / 8 GiB aggregate disk bounds. Available host disk was
912 GiB, so the failure was a workspace envelope, not a full host volume. It is
retained as a failed capacity cell and is not changed into acceptance.

A separate sequential diagnostic series uses **8 GiB workspace / 32 GiB aggregate
disk** for every point and both sizes. CPU quota, JVM heap, cgroup limits, row/field
limits and lifecycle guarantees stay the same. This explicitly larger private
profile lets the writer mechanisms be exercised at one million unique inputs;
it is not production configuration or compliance with an agreed storage budget.
Outputs have `point-N-expanded` identities; comparisons never pair expanded and
original envelopes. The final point also compares versioned and immutable receipt
payloads at this same envelope, provided their complete independent semantic
oracles pass.

The broader control/receipt regression matrix executed 39 cases: 38 passed and
one failed because an old test required an exact internal exception substring.
The guard still rejected owned/recursive calls. The assertion now checks the
exception type and ownership outcome; that affected case passed independently.
No automated retry or changed production guard hid a failing concurrency case.

### Collector interruption and explicit continuation

The first expanded million-document collector exited while its separately owned
user-systemd application continued. Its control/sample window is incomplete,
so that cell is retained as **ABORTED**, without a complete-cycle timing or pass.
The owned application was stopped and its temporary heavy state removed; partial
logs and counters remain local. The two completed 100k cells are unchanged.

The sequential controller now runs as a repository-local transient user-systemd
service, independent of the interactive tool session. The driver checkpoints
sample identity, owned state and handoff before waiting for completion. Explicit
`--resume` verifies the frozen binary, helper, policy and disk envelope; it skips
passing cells and retains failed cells. The million-document cell is a new
measurement, not an automatic flaky-test retry. Collector versions/source
identities are retained for both runs.

Four summary-scope regressions and 45 existing tools contracts passed; these
checks do not replace the deferred reactor quality gates. The final finite
retention diagnostic uses an explicitly compiled classpath rather than borrowing
an old Failsafe report as evidence of fresh gates.

## Observed service windows

| Point | Profile | Inputs | Qualification | Local s | Inputs/s | Allocated GiB | Bulk heap MiB | Bulk RSS MiB | Max writer hold ms | Max control wait ms | Receipt scan s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 original | unique-document | 100000 | ERROR | 311.99 | 320.52 | — | 94.22 | 398.82 | — | — | — |
| 1 original | collapse-import | 100000 | CORRECTNESS_PASS | 27.75 | 3604.03 | 3.39 | 89.52 | 377.19 | 36.24 | 0.09 | 0.03 |
| 1 original | unique-document | 1000000 | FAIL | — | — | 33.42 | — | — | 91.52 | 0.26 | 0.01 |
| 1 original | collapse-import | 1000000 | CORRECTNESS_PASS | 306.24 | 3265.38 | 33.52 | 89.93 | 387.52 | 185.77 | 0.07 | 0.04 |
| 1 expanded | unique-document | 100000 | CORRECTNESS_PASS | 181.74 | 550.23 | 28.36 | 93.23 | 397.09 | 446.02 | 178.81 | 3.42 |
| 1 expanded | collapse-import | 100000 | CORRECTNESS_PASS | 16.49 | 6063.12 | 3.27 | 84.03 | 374.12 | 9.41 | 0.07 | 0.01 |

Bulk heap/RSS are sampled maxima filtered to handoff → local completion. Allocations include concurrent controls and writer drain; post-publication scans/cleanup are excluded from their exact counter. A failed window is not accepted capacity. One fork per cell gives no production percentile or SLO.

## Finite production comparison

The [production capacity closure](production-capacity-closure.md) records the
reference-resource full-service cases, actual SMB publication, post-service
receipt/cleanup costs and the model choice. Native remains production-wired;
the Staged candidate is not activated. The original intermediate per-point
matrix is deferred under the owner's finish-first steering, not declared passed.

A strict 100-document Native case exposed ING-17: concurrent admission
finalization left a run without completion despite finished canonical work.
Its timeout remains unsuccessful evidence. The narrow source fix has independent
forced-interleaving regressions; the new-binary whole-service case passed with
zero terminalization conflicts in 909.48 service seconds at workers/window 1/1.
This closes a correctness seam rather than reopening SQLite performance design.
