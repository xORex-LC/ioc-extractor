# CAP-0–CAP-1 execution evidence

Status: in progress, 2026-10-04. Scope is CAP-0, CAP-1A and CAP-1B from
the [capacity plan](data-processing-capacity-plan.md). The adopted budgets
remain acceptance targets. Later bounded-memory preparation and scheduling
stages are not implemented by this change.

## CAP-1A implementation checkpoint

`c7314427` changes ordinary request matching and staged import alias planning
to request-driven complete-key probes using SQLite `CROSS JOIN`. It preserves
the transaction visibility and import planning boundaries. No index migration,
bulk prematching or alias mutation policy change is included.

Nine real JDBC matcher/mutation regressions pass, including union of several
keys, stable ordering, equal digests with unequal material, strict expiry,
artifact/definition isolation, inserted and changed aliases visible within a
transaction, isolation from another connection and rollback.

A preliminary empty-store active-lifecycle run, one fork per workload:

| Input | Local processing | Caller allocations | Sampled current RSS peak |
|---|---:|---:|---:|
| Physical mixed document, 10k unique occurrences | 11.080 s | 2,592,641,872 B | 412,956 KiB |
| Physical processed import, 10k unique rows | 4.509 s | 1,273,593,872 B | 387,252 KiB |

This is a sanity sample, not paired improvement or budget acceptance. The
document profile uses original-view routing and one section; it does not
represent the stand's cleanup policy or 400-section incident fixture.

## Harness progress and remaining gates

The existing Make comparison facade now supports active-lifecycle capacity
profiles. The ordinary golden document profile previously left lifecycle
disabled and therefore did not exercise the stalled alias-matching kernel.
Capacity runs reject missing active metadata and use an independent oracle
for every public field and complete configured record key in all five
document artifacts. Actual results are read through a cursor after timing.
Production pipeline observer events supply stage durations; sampler failure,
process failure and missing phase anchors invalidate a run.

A 40-occurrence physical document and 40-row processed import passed in the
private CAP-1A snapshot. The document has six measured pipeline stages and
dataframe schema version 12. The independent oracle is also run against the
preliminary 10k document database; all five artifacts pass.

G0 remains open for full stand-policy AS_IS imports, explicit writer wait/hold
anchors and complete state manifests. G1A remains open for the full 100k
daemon/SMB cycle, version-pinned work screen and repeated scaling evidence.
CAP-1B will be compared to a frozen CAP-1A executable with identical probes,
configuration and physical inputs. Primary runs carry no Java agent or JFR;
diagnostic instrumentation remains a separate measurement mode.

## CAP-1B implementation checkpoint

Mutation resources now belong to a caller-owned connection/transaction. The
immutable engine opens one session per ordinary artifact write and one per
affected artifact during import promotion. Sessions retain at most 32 prepared
statements, clear bindings and batches between leases, close all resources
before commit and never commit or close the caller's connection. Matching uses
a complete-key singleton query or a lazily created TEMP request table, with
256-request chunks and 256-key driver batches. Row-local match key material is
reused for a newly inserted/restarted row; there is no content-keyed global
cache. Catalog/schema/effective time are immutable session inputs.

Alias delete/reinsert remains unchanged. Import prematching still finishes
before promotion; sequential ordinary confirmations still see their own writes.
Import planning/final-cell decoding and lifecycle archival remain separate
resource owners and are not claimed as optimized by this step.

The new `JdbcStatementScopeTest` adds five fast scenarios for resource reuse,
bounded eviction, reentrant/thread ownership, clearing failure and preservation
of close failures. The source universe deliberately increases from 220 to 221
fast suites and from 287 to 288 deterministic suites; integration/external
counts and every coverage/analyzer threshold remain unchanged. Three additions
to the existing matcher integration suite cover repeated calls, independent
connections, chunk boundaries and rollback/retry. Final gates are pending.

## Measurement disposition and remaining memory boundary

The first five-pair 10k comparison is retained locally as preliminary evidence:
its first document pair overlapped the final 16 seconds of PMD analysis. It is
excluded from the primary comparison. A separate quiet five-pair 10k repeat
and five-pair 100k comparison use frozen CAP-1A and CAP-1B runtimes, byte-equal
probes/resources, alternating order and fresh empty stores. The 100k import
workload now also contains 100k rows; it is not a repeated 10k import labelled
as a 100k profile.

Initial 100k document samples reduce local processing from roughly 84 seconds
to 28 seconds, but process RSS remains near 800 MiB. These are provisional
samples until the complete report is checked. The reused statement/matcher
resources reduce work and allocation; the still-materialized document,
prepared rows and downstream projection/export state remain CAP-3–CAP-5
concerns. Neither the 512 MiB service RSS target nor capacity acceptance is
closed by CAP-1B.

## Local evidence retention correction

The first capacity harness retained every per-fork SQLite store and repeated
CSV, accumulating about 11 GiB in CAP workspaces. This was an execution defect.
Completed-run state is now disposable after the semantic checks: all three
comparison/capacity tools clean owned databases, WAL/SHM, staged snapshots and
generated projections on success and failure. Inputs, configuration, logs,
measurements and compressed complete oracle signatures remain. Full state
retention requires explicit `--retain-state`; stand snapshots use gzip. Cleanup
does not follow symlinks or traverse source/Git/frozen-runtime directories.
Each new comparison fork refuses to start with less than 1 GiB free.

Obsolete generated states across 75 private benchmark workspaces were removed,
and existing full signatures were compressed, releasing about 15 GiB. One
coherent compressed canonical/service pair from the successful 100k stand run
and one from a 10k processed import remain for investigation. The frozen
CAP-1A/CAP-1B runtime identities match the primary report after cleanup.
Twenty-seven offline harness contracts and real 40-document/40-import JVM
forks pass; their retained evidence occupies roughly 60/80 KiB and no SQLite
state remains in either completed fork. The WSL relocation interrupted a
diagnostic run and left incomplete files; those samples are excluded until
reproduced. Completed primary reports are intact.
