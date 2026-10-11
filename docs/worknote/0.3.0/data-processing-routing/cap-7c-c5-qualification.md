# CAP-7C C5 comparative service qualification

Status: comparative harness implemented and first-failure qualification executed
on 2026-10-11. The C5 promotion gate is **NOT_ACCEPTED**. Remaining reference
cells and production activation are open; a failed series is not a completed
capacity matrix.

The first default-policy smoke fork exposed a candidate admission blocker:
256 KiB maximum rows, 4 MiB caches and a 64 MiB aggregate preparation budget
cannot reserve two canonical workspaces plus a document lease. The staged
configuration rejects startup. This is a failed default-configuration result,
not a resource pass. Comparative covering cells may explicitly use
`--bounded-row-profile` (16 KiB rows, 4 KiB fields and 1 MiB caches in **both**
variants, with the same 64 MiB aggregate budget). Those results cannot qualify
the larger default row/field contract. Fixing the candidate's worst-case chunk
reservation without weakening accepted input limits remains separate work.

The comparison keeps the corrected native SQLite implementation as the baseline.
The candidate is the private staged SQLite backend from C3/C4/C6. Neither fork
changes the packaged application, deployed service or production selector.
ADR 0041 remains proposed and G6 remains `NOT_ACCEPTED`.

## Measurement contract

`make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar
bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar --config <complete-policy.yml>
--output .dev/<new-evidence-directory> --rows 100000 --samples 3'` freezes the
packaged application and three explicitly selected test-only helpers. Both
variants use the same physical corpus and application policy. They start the
normal Spring application, intake, canonical processing, mutable projection and
immutable export paths; only the complete candidate persistence-port bundle is
replaced in the staged fork. A fresh active database is converted before intake.
Optional accumulated state is built through public document ingestion after
conversion. This is not a populated migration or stopped restore measurement.

Each fork runs in its own user-systemd unit with 2 CPU, `-Xms128m -Xmx512m`,
768 MiB memory high and 1 GiB memory maximum. Actual applied limits, packaged
bytes, helpers, policy, source commit, fixture hash, mode and cleanup are retained.
The same bounded Java agent in both primary variants observes already computed
writer wait/hold values at admission exit, including unsuccessful transactions.
Only the actual canonical `JdbcWriterAdmission` bean's identity is admitted;
private preparation-database guards are excluded. A development run before this
filter included private guards: its staged writer maxima are not canonical
occupancy evidence and are retained solely as superseded collector diagnostics.
A regression compares these values with the real guard's native metrics. Unlike
cumulative health maxima, the resulting maxima belong to the measured window.
The agent also reads cumulative JVM allocation accounting across all threads;
this includes HTTP, cleanup, projection and scheduler work, rather than only the
canonical caller. Agent/control overhead is included in both variants.

A containing worker makes a real clock control request once per second after the
preceding request completes and invokes actual history/version cleanup every
fifth request. `--maintenance-every 0` leaves cleanup at the unchanged service
cadence and retains only the extra clock probes; it is a distinct covering cell,
not a replacement for a failed cleanup-stress result. Reports separate scheduled lateness, eligible-to-complete control
latency and exact admission queue wait. This is a declared closed-loop background
load, not an unlimited arrival-rate or sustainable mixed-intake claim. A worker
failure or incomplete shutdown prevents publication of successful metrics.

The reused service sampler retains raw current/peak RSS, sampled heap, CPU/GC,
cgroup anonymous/kernel/file accounting, PSI, WAL and workspace/disk bytes.
Separate `--diagnostic` forks retain JFR/NMT and post-GC heap; their timing is not
mixed with primary runs. The local interval remains a conservative handoff-to-
completed-slices window after subtracting the declared intake quiet period.
It is not an exact export-ready-to-dispatch measurement.

The independent disk oracle checks complete final fields, canonical keys,
positive canonical/lifecycle IDs, source summaries, mutable IDs and immutable
slots. The comparison rejects different input hashes or final oracle results.
CSV slot validation streams one committed registry snapshot into the disk-backed
oracle before indexed local checks. It does not repeat a full versioned-view
lookup for every CSV row. This readback runs after the measured service window.
An additional committed-family census retains receipt, history, version and slot
growth and checks source-summary references. Pending visibility, crash recovery,
TTL boundary semantics and deterministic conflict exhaustion remain established
by the C4 contracts; a family census does not substitute for those oracles.

`--import-artifact` derives one AS_IS contract from the unchanged schema,
identity and export catalogs; imported public IDs request sparse external slots.
`--import-artifact masks --processed-import` instead uses the existing production
selected-import fixture's host view, classifiers and COALESCE contract. The
unique fixture has one host per URL; `--shape collapse` sends distinct paths,
ports and query values into 20 hosts with identical admitted source cells. Its
independent oracle checks clean masks and reclassified match codes, while the
real delivery ledger and canonical receipt must report the coalesced key count.
This specific import fixture does not claim arbitrary source-cell conflicts or
full related-artifact routing. DOCX ZIP metadata is fixed so paired forks have
identical bytes and document source identities.

## Failure and acceptance policy

Both members of a pair execute even if one fails a resource screen. Further
pairs stop after a failed pair; failed logs, samples and measurements remain.
Raw evidence stays in the private `.dev` output. Business databases, CSV copies
and frozen runtimes are removed only after bounded service termination.

The adopted time, RSS, PSI, five-second physical writer and ten-second control
queue limits remain unchanged. Complete series also enforce the 30s/180s median
budget; an incomplete series or individually passing forks with an excessive
median cannot pass.
Passing a covering cell is a screen result, not completion of the whole C5/G6
matrix. Exact profile dispatch eligibility,
sustainable invalidation/intake, the complete artifact/import matrix, populated
conversion, cleanup backlog and diagnostic live-state limits must retain their
own evidence or remain explicitly open. No native-transaction fallback, smaller
workload, hardware increase or deferred cleanup makes a failed cell acceptable.

## Recorded comparison

[Evidence](qualification/capacity/cap-7c-c5-reference.json) preserves all 42
serialized forks, their original statuses, source/executable/helper/policy hashes,
raw-file digests, exact counters, independent oracles and verified cleanup.
One earlier caller-interrupted warmup has raw logs but no serialized sample;
its missing outcome is identified separately rather than invented retroactively.
The [credential-free policy](qualification/capacity/cap-7c-c5-policy.yml)
preserves the actual default private document configuration. Import catalogs
and the explicit bounded-row override are derived by the committed driver.

The packaged native executable has SHA-256
`e04081d66291d41b922f1cd7c42f63b2c64ba944127967919a4cd352a0569559`.
Primary paired cells alternate fork order. Three pairs were requested for each
100k cell; new pairs stop after the first failed pair. This is not an automatic
retry of failed samples. Small contract screens request one pair; populated
diagnostics request one separate pair. No generalized speedup or ratio-acceptance
claim is made from fewer than five independent primary pairs.

| Cell | Corrected native SQLite | Staged SQLite | Disposition |
|---|---|---|---|
| Actual DOCX, 100k unique, empty store, ordinary cleanup cadence | Complete output oracles; 84.49s; maximum canonical hold 15.449s; control queue wait 14.891s | `SQLITE_FULL` during private `ioc_aggregate` receipt preparation; incomplete workload at 284.70s | Failed mandatory pair; no completed staged throughput or ratio |
| HTML, 100k repeated occurrences, empty store | Two complete windows: 34.78s and 57.28s | Two complete windows: 29.81s and 37.09s | All four output oracles pass; stop at the native 45s maximum failure; incomplete three-pair series |
| Processed masks CSV, 100k distinct detailed URLs → 20 hosts, COALESCE | Three complete windows; median 27.12s, range 26.88–34.22s | Three complete windows; median 32.47s, range 31.96–33.32s | All six full oracles pass; staged fails the unchanged 30s median budget |
| AS_IS aggregate CSV, 100k unique records | Full oracles pass; 59.30s; hold 35.972s; control wait 35.143s | Receipt and completed slices observed at 302.60s, followed by control-collector failure from unsafe UTC | Native fails three screens; staged `ERROR`, no successful counters/oracles/ratio |
| AS_IS 1k masks / IP / hashes / blacklist | Complete contract screens: 8.92 / 4.83 / 4.95 / 9.61s | Complete screens: 11.26 / 7.28 / 7.45 / 7.06s | Fields, keys, provenance, receipt and sparse requested slots pass where applicable; not 100k import acceptance |
| AS_IS 1k aggregate | Warmup times out after document admission timestamp failures; measured CSV never starts | Complete 7.58s contract screen | No valid paired performance result; native 100k aggregate proves the import contract separately |
| Populated 10k, then 1k repeated HTML, separate JFR/NMT forks | Full oracles; post-GC live heap 44.38 MiB | Full oracles; post-GC live heap 44.78 MiB | Scoped accumulated-state/diagnostic pass, not primary timing or populated 100k acceptance |

Times are conservative complete-window upper bounds with intake quiet time
subtracted, not exact `Tlocal` or export-ready dispatch latency. The 100k processed
receipt must contain 20 accepted rows, zero rejected rows and `COMMITTED`; the
service delivery must separately be terminal/succeeded. The final masks count
is 30 because ten warmup masks remain. Both backends match the independent full
field/key/provenance oracle, mutable projections and all three export profiles.

Completed processed-import primary forks allocate 3.07–3.13 GiB in native and
3.19–3.28 GiB in staged; sampled RSS ranges are 346.3–348.9 and 382.3–391.9 MiB.
Canonical maximum holds are 0.138–0.226s and 0.007–0.027s respectively. Thus this
cell demonstrates physical responsiveness, but does not meet staged's total
time budget. In the unique DOCX failure window, staged records 35.12 GiB JVM
allocations, 400.9 MiB sampled RSS and 1,387.9 MiB aggregate WAL. Its partial
canonical maximum hold/wait are 1.899s/1.846s; neither completed throughput nor
full oracles may be inferred from this failure window. It has two rebuild phases
and maximum attempt two. Six rebuild phases belong to the earlier separate
cleanup-stress failure, whose all-guard writer collector is superseded. Its
raw record is retained with that scope limitation. WAL totals include private
databases; named workspace bytes omit unlinked SQLite TEMP files. Neither value
is a canonical-only size or a complete physical-disk high-water claim.

## Blockers and required follow-up

1. **Default admission:** preserve the 256 KiB row limit, original field limits
   and 64 MiB aggregate budget while redesigning worst-case batch/cache/lease
   reservations.
   The narrower qualification profile is not permission to change defaults.
2. **Large private preparation:** the unique DOCX fails at
   `JdbcConfirmationReceiptWriter.stageFieldPositions`; private main/TEMP page
   limits are each one quarter of the 2 GiB workspace allowance. Profile the
   cloned snapshot, receipt/field-position and delta representation, then reduce
   amplification without dropping facts or increasing quotas. The host had free
   space; this is a configured SQLite limit, not host disk exhaustion.
3. **Invalidation and concurrent leases:** the earlier cleanup-stress 100k DOCX
   exhausts the finite rebuild budget before completion. Logs also retain
   observable `Document preparation disk admission exhausted` during competing
   export preparation. Separate true semantic invalidation from no-op control
   work, validate finite progress and reserve foreground/control/export capacity.
   Removing cleanup, increasing attempts or falling back to native is prohibited.
4. **Unsafe UTC and coordination:** a staged aggregate control rejects a backward
   skew of `PT2.061S` against the 2s safe-clock limit. Other warmups reject
   `Invalid document admission version or timestamps`, leaving a run at
   `PROJECTION_COMPLETED`. Monotonic durations remain valid, but these cells do
   not qualify completion/recovery. Extend the existing `ING-16` coordination
   timestamp policy and deterministic recovery tests; do not disable safe-clock
   admission or replace absolute UTC deadlines with process time. The underlying
   host-clock cause is not established by these logs.
5. **Total cost and evidence:** reduce staged preparation/read/transfer cost under
   the same results and limits, then rerun mandatory primary pairs. The complete
   million-row/format/import/populated-state matrix, sustained mixed arrivals,
   cleanup backlog, exact profile dispatch and live SMB remain `NOT_RUN` or open
   after failed mandatory screens. C4 correctness evidence and these smaller
   passes cannot close those boundaries.

No production Java, default composition, deployed service or accepted ADR was
changed by C5. The test-only Spring bundle and agent are absent from the packaged
application. All owned serialized-fork JVMs terminated and private DB/WAL/CSV and
frozen runtimes were removed; raw logs/resource/control traces and separate JFR
recordings remain local. Final repository verification is required at the final
documentation/evidence commit; it does not turn resource failures into acceptance.
