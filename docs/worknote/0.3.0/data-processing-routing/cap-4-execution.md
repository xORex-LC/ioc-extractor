# CAP-4 execution and qualification

Date: 2026-10-05. Scope: CAP-4 only; no CAP-5 scheduling change or stand deployment.

## Implementation

[ADR-0036](../../../ADR/0036-sealed-document-preparation-workspace.md) owns the
state/recovery decision. One admitted private SQLite connection/cache serves
all document artifacts. Every routed occurrence is stored; global selection
uses full canonical material, preserves first-key order and replaces complete
LAST_NONEMPTY rows including positions and provenance. Preparation seals before
the failure-policy checkpoint; no canonical IDs are reserved by preparation.

Owned repeatable cursors replace document-wide winner, confirmation and receipt
lists. Receipt inserts flush at 128 rows or 1 MiB estimated bindings; an
individually larger binding flushes alone. Document rows have a separate encoded
byte limit. Duplicate
confirmation validation uses disk TEMP storage with a 64 KiB cache and a bounded
page count. The canonical mutation session remains transaction-scoped. Receipt
replay shares the writer connection, including a pool with only one slot.

Successful/dry-run/rejected work is deleted. A sealed promotion failure retains
the source/identity/seal for the stable observation. Per-artifact commit markers
are checked before reading or reserving rows, even while the whole receipt is
STAGING. COMPLETE receipts remain usable after workspace deletion. Pinning
checks source byte limits during copying, forces source/metadata writes and
directory changes, and validates hashes. Expired pins are pruned on admission;
active file leases are excluded. Defaults and both configuration guides expose
all nine physical workspace settings. Physical budgets do not change the
semantic processing fingerprint or invalidate earlier complete receipts.

## Qualification status

CAP-4 implementation and scoped G4 qualification are complete. The full Spring gate identified a
test isolation defect: four independently initialized canonical stores reused
one workspace root and observation. Golden fixtures now own a separate workspace
per store; production pin identity mismatch remains fail-closed.

The suite universe adds one integration class (74 integration classes,
291 deterministic offline classes total, five property-gated external shells).
The final workspace suite executes 33 cases and the lifecycle writer suite 28,
with no failures or skips in these two suites. No coverage floor is lowered.

G4 measurements use `make document-workspace-capacity`: incremental generation,
two artifacts, ten percent duplicate observations, independent full-row winner
oracle, real canonical promotion and complete receipt reread. Live heap samples
use explicit GC and are diagnostics, not primary end-to-end latency evidence.
Each fork's databases and frozen runtime are removed on success, oracle failure
and timeout. The cleanup/error contract is independently tested.

Whole-text/occurrence buffers, full SMB-cycle time and final service resource
acceptance remain later capacity gates. No CAP-5 implementation or stand
deployment is included.

| Gate / task group | Status | Evidence and boundary |
| --- | --- | --- |
| CAP-4A ownership/recovery | PASS | ADR-0036, stable pin/rank/policy/seal and per-artifact recovery matrix; legacy unranked work fails closed |
| CAP-4B global reduction | PASS | Independent whole-row oracle with 64/4096 KiB caches, composite/full-key collisions, losing occurrences and retained observations; no size-dependent list path |
| CAP-4C port migration/promotion | PASS | Production workspace wiring, owned cursors, bounded receipt batches, pool-one replay, interruption and corruption/quota tests; import remains delivery-atomic |
| G4 working-state scaling | PASS | Fixed small budget, 10k/100k/1m incremental input, stable live heap, all private files removed |
| Deterministic offline release gate | PASS | `make verify`: test discovery/report integrity, architecture, coverage, exact SpotBugs and CPD gates |
| PMD policy / ownership review | PASS | `make pmd-analysis`, `make pmd-watchlist`; findings reviewed below |
| Tooling / documentation | PASS | `make lint-shell`, `make docs`, explicit local link check and `git diff --check` |
| Provisioned SMB / full cycle / G6 | NOT_RUN / OPEN | No live stand or network run in this slice; existing absolute targets are not declared achieved |

## G4 measurements and remaining resource limits

The retained [raw evidence](qualification/capacity/cap-4-workspace.json) is from
implementation commit `be4afb5a787efbe6ee18c42b09501b98f2b0bfdf`, with frozen
runtime SHA-256
`38caf0e9dfe4bdb6967019dac46e7d02acbd88a26faf2dbb0a89fd0694b70043`.
The six fresh JVMs ran on WSL2/Linux with OpenJDK 21.0.12.1 on 2026-10-05,
12:24:19–12:32:38 UTC. Each size has one sample; these are mechanism diagnostics,
not primary timing medians, confidence intervals or before/after speedup claims.

The preparation/promotion series uses `-Xms32m -Xmx64m`, a shared 2 MiB
admission budget, one 64 KiB native workspace cache, 4 KiB encoded rows,
2 KiB fields, 128-row commit batches and a 2 GiB workspace/root quota.
The measured lease is 160 KiB. The single canonical connection's 2000 KiB cache
and confirmation-validation 64 KiB cache are recorded separately from workspace
admission. N means **rows per artifact**: two artifacts, N originals,
2.2*N candidates including duplicates, and 2*N canonical winners. The probe
checks first-key order, complete KEEP_FIRST/LAST_NONEMPTY rows and field
positions, promotes them through the real lifecycle writer and checks the
COMPLETE receipt against the same independent oracle.

| N per artifact | Baseline live heap, MiB | Sealed live heap, MiB | Live heap after receipt, MiB | Sampled peak heap, MiB | Sampled peak RSS, MiB | Workspace disk, MiB | Total private test state, MiB |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10,000 | 12.950 | 13.128 | 13.275 | 28.324 | 164.58 | 5.76 | 30.52 |
| 100,000 | 12.943 | 13.109 | 13.263 | 33.298 | 192.85 | 58.58 | 308.62 |
| 1,000,000 | 12.990 | 12.894 | 13.027 | 29.191 | 174.85 | 605.64 | 3,138.86 |

The baseline measures fixed JVM/adapter ownership overhead. Live heap remains
about 13 MiB as cardinality grows 100-fold; the largest sealed-minus-baseline
sample is 0.178 MiB. Negative deltas are GC/sampling variation, not negative
allocation. Sampled peaks include garbage and are not retained working state.
The complete 2-million-winner test runs with a 64 MiB heap cap. Disk grows with
actual rows and receipts: the million-size workspace remains within 2 GiB;
total test state remains within the probe's 8 GiB assertion. That assertion is
not a production quota for the canonical dataframe database.

| N per artifact | Prepare and seal, s | Canonical promotion, s | Receipt read and oracle, s |
| ---: | ---: | ---: | ---: |
| 10,000 | 2.361 | 1.357 | 0.142 |
| 100,000 | 23.327 | 12.355 | 1.125 |
| 1,000,000 | 293.277 | 127.771 | 9.151 |

These durations include forced small-cache spill and durable disk writes. They
exclude Router, Tika, SMB and export/publication and do not close G6 latency or
writer-hold budgets. The factory always uses disk; the 4096 KiB functional case
checks the same selection behavior without the small-cache pressure. Every
candidate still passes processing/validation before selection in the production
path.

The separate upstream series uses actual Spring/Tika/read/refang/extract/attribute
with unique defanged domains, `-Xms32m -Xmx512m` and explicit GC at stage
boundaries. It does not include preparation, Router or writes:

| Input occurrences | Live heap before attribution, MiB | Live heap after attribution, MiB | Sampled peak heap, MiB | Sampled peak RSS, MiB |
| ---: | ---: | ---: | ---: | ---: |
| 10,000 | 41.55 | 40.92 | 54.58 | 282.30 |
| 100,000 | 59.10 | 52.17 | 125.82 | 343.98 |
| 1,000,000 | 230.09 | 160.86 | 500.73 | 729.99 |

The remaining whole-text/occurrence graph grows with input and the million-size
upstream RSS exceeds the eventual 512 MiB service target. G4 bounds the routed
preparation-through-confirmation segment; it does not qualify the sum of all
service components. CAP-5 scheduling and CAP-6 primary qualification remain
open. CAP-7A becomes required if the actual supported end-to-end workload still
fails G6 after those stages; this upstream diagnostic is evidence to investigate
that boundary, not an automatic change of authorized scope.

All six forks report verified cleanup. Their SQLite databases, source documents,
logs and frozen runtime are removed, including the harness's independently
tested oracle-failure/timeout paths. Only the compact JSON is versioned.

## Analyzer review: CAP-4-SQL-TRUST

The first complete analysis reports four new SQL identities and removes the
former `JdbcConfirmationReceiptStore.loadRows` identity. The exact baseline
change retains raw visibility and narrows acceptance to four reviewed methods:

| Identity | Review evidence | Reopen when |
| --- | --- | --- |
| CAP-4-SB-001 | Workspace PRAGMAs concatenate validated integral cache/page limits only; quota failure tests | PRAGMA operands admit strings or tuning grammar changes |
| CAP-4-SB-002 | Workspace INSERT/conflict clauses are adapter-owned constants; row/key/artifact values are bound; independent winner oracle | Reducer SQL ceases to be closed templates |
| CAP-4-SB-003 | Receipt SELECT table/column names are admitted immutable schema identifiers, validated and quoted; receipt ID is bound; single-slot replay | Schema admission, quoting or binding changes |
| CAP-4-SB-004 | Receipt COUNT uses the same schema-owned quoted table; complete header/count checks share the read transaction; removed-empty-receipt test | Receipt count/snapshot contract changes |

Null-return path findings, generic close rethrow and loop string-concatenation
signals encountered during development were fixed in code. CPD remained at
24/24 groups in the first complete analysis. Coverage floors and missed-count
ratchets remain unchanged; source-failure ownership and invalid-budget tests
cover additional meaningful failure paths.

## Failure and resource ownership qualification

The final implementation adds regression cases to existing suites for malformed
row encodings, UTF-8 byte limits, catalog/count drift, seal/source/identity
tampering, cursor state, cancellation, disk admission and retained-pin cleanup.
Two independent factories cannot prune or acquire an active pin. A failed
cleanup still releases its lease; unrelated root contents are never adopted.

Receipt tests reject changed source/policy/version, inconsistent artifact/row
counts, unknown artifacts and missing typed rows before the remaining artifact
can commit. They also exercise bounded receipt batches, cancellation, a
single-slot pool, terminal/purge SQL failures and complete receipt replay after
workspace removal. Close/restore fault matrices verify that all owned resources
are attempted and the original exception retains suppressed cleanup failures.
Legacy incomplete ingestion without a durable admission rank fails closed;
recovery does not extract it again or silently assign a new rank. This is an
explicit disposition for ING-11, not general closure of that issue.

On the final implementation worktree, `make verify`, `make pmd-analysis` and
`make pmd-watchlist` pass. SpotBugs reports 129 reviewed raw findings and zero
unaccepted findings; CPD remains 24 groups. Adopted PMD counts remain
UnusedAssignment=2, DoNotThrowExceptionInFinally=1, CognitiveComplexity=7,
NPathComplexity=3 and ExcessiveParameterList=7. No coverage floor, scope or count
ratchet is weakened. CAP-4-SB-002 changes only its exact constructor bytecode
anchor (395 to 391) after a control-flow refactor; its SQL trust review is
unchanged.

The same-count PMD replacement is reviewed explicitly: clearing
`RowSources.map.current` before advance/mapping prevents a previous row from
remaining visible after failure or end of input. PMD's UnusedAssignment signal
does not model that failure path; a regression test does. The removed occurrence
was the obsolete in-memory ID-offset increment. The other UnusedAssignment is
the existing cross-thread SMB signal.

The watchlist contains 49 CloseResource, two NcssCount and four PreserveStackTrace
signals. Six CloseResource signals intersect this change: the workspace owns its
reusable statements; its returned cursor owns the query statement/result set;
the factory transfers its file lease to workspace close or closes it on failed
admission; the mapped cursor closes its source. Failure tests cover these
ownership transfers. The existing 61-NCSS canonical transaction method remains
advisory size debt; splitting transaction policy is outside CAP-4.
