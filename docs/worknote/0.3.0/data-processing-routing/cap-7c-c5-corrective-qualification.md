# CAP-7C C5 corrective qualification

Status: corrective implementation and mandatory repeat completed on 2026-10-11.
**C5 remains NOT_ACCEPTED.** Default native composition, proposed ADR 0041 and
G6 are unchanged. This report supplements, and does not replace, the retained
[first C5 failures](cap-7c-c5-qualification.md).

## Identity and method

[Corrective source changes](cap-7c-corrective-cycle.md) were measured at clean
HEAD `c01d116cd20617de7364cc06e1af8fa88ab9ddbf`. All 12 forks use the same frozen
JAR SHA-256 `090c16d7b51261b6ce0c5657ba96e51b291a6edd33c619101dfddf3c83f1ddd7`,
helper bytes and original credential-free C5 policy. The
[versioned evidence](qualification/capacity/cap-7c-c5-corrective.json) retains
complete reports, raw-file hashes, effective limits, independent oracles,
failure traces and cleanup facts. Private raw roots are `.dev/cap7c-corrective-*`.

The standard input policy is used throughout: 256 KiB rows, 64 KiB fields,
64 MiB aggregate preparation, 2 GiB per workspace and 8 GiB workspace disk
admission. `--bounded-row-profile` is false in every series. Canonical private
cache reservation is now bounded by the implemented 512 KiB cap. Both variants
retain CPU=2, heap=512 MiB, cgroup memory high/max=768 MiB/1 GiB, unchanged swap
policy, safe-clock policy and adopted C5 screens. Clock controls run once per
second, and extra real cleanup runs every fifth successful probe. Normal service
cleanup remains enabled. No Maven or structural diagnostic ran concurrently.

Reproduction uses the existing facade:

```bash
make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar --config docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml --output .dev/<new-root> --rows 100000 --samples 3 --format docx --shape unique --maintenance-every 5'
```

For the processed cell use `--shape collapse --import-artifact masks
--processed-import`; for AS_IS aggregate use `--shape unique --import-artifact
ioc_aggregate`. The admission smoke uses `--rows 1000 --samples 1 --format docx
--shape unique`. Every pair executes both members; subsequent pairs stop after
a failed member. DOCX and aggregate therefore retain one of three requested
pairs; processed import retains all three pairs, with a failed final member.

## Results

Times below are conservative handoff-to-completed-slices intervals with declared
intake quiet time subtracted, not exact dispatch eligibility. Collector-failed
intervals are observations only: they do not establish qualified throughput,
allocations, exact admission maxima or independent result equivalence.

| Cell | Native SQLite | Staged SQLite | Decision |
|---|---|---|---|
| DOCX 1k unique, standard admission | 3.77s; complete oracles | 8.80s; complete oracles, maximum attempt 1 | Both screen-pass; original default startup blocker resolved |
| DOCX 100k unique | 67.26s; complete oracles; hold 10.401s, control wait 9.865s | `ERROR`: control-worker safe-clock rejection `PT2.181S`; observed slices at 638.89s, no completed qualification/oracles | Native fails 45s window and 5s hold; series stops after first pair |
| Processed masks CSV 100k detailed URLs → 20 hosts, COALESCE | Three screen-passes, 20.33–25.09s; median 23.36s | Two fully qualified passes, 21.70/21.90s; third `ERROR` at observed 22.22s from `PT2.363S` control rejection | Failed series; two successful pairs establish semantics, not full-series acceptance |
| AS_IS aggregate CSV 100k unique | 34.61s; complete oracles; hold 22.053s, control wait 21.521s | `ERROR`: control-worker rejection `PT2.073S`; observed slices at 230.01s, no completed qualification/oracles | Native fails hold/control screens; series stops after first pair |

The 12 forks contain seven `SCREEN_PASS`, two `FAIL` and three `ERROR` results.
Nine forks completed both collection and independent oracles. All paired passing
oracles agree on full fields, canonical keys, provenance, mutable projections and
three immutable export profiles. In five fully qualified processed-import forks,
canonical receipts independently report `COMMITTED`, 20 accepted/zero rejected,
and the service delivery reports `TERMINAL/SUCCEEDED`. The real CSV contains
100k distinct detailed URLs coalescing to 20 keys; the final masks set includes
the ten warmup rows. Both successful staged forks have maximum attempt 1 and no
rebuild phase. Their maximum canonical holds are 0.014–0.023s.

For the two complete processed pairs, time ratios staged/native are 0.873 and
1.067; allocation ratios are 1.056 and 1.061. These are paired observations,
not a general speedup or an accepted series. Successful staged allocations are
3.30–3.33 GiB and sampled RSS 370.4–372.2 MiB. The comparable native samples
allocate 3.11–3.16 GiB; the missing third staged collection is never imputed.
The initial C5 comparison used the explicit narrower row/cache profile, so its
32.47s staged median is not a controlled before/after comparison with this
standard-policy series.

Raw `series_screen.completed_windows` and its medians count observed intervals
even when collection failed. The evidence adds `verified_window_summary`, which
filters completed collection plus all oracles. The failed raw series decisions
are retained unchanged; no median assembled from failed samples is presented as
an acceptance result.

## Remaining blockers and next work

1. **Large-unit total cost:** standard DOCX no longer logs `SQLITE_FULL`, but its
   observed staged interval is excessive and lacks successful collection/oracles.
   The failed window records 389.9 MiB RSS, 93.8 MiB sampled heap, 308.2 MiB swap,
   67,133 memory-high events and 19,631 MiB aggregate named WAL peak. WAL includes
   private/other databases, while named workspace bytes omit unlinked SQLite TEMP
   files. Neither metric proves canonical-only disk growth or complete physical
   disk high-water. Profile per-file WAL, checkpoint/read-pin lifetimes and typed
   transfer/read amplification before choosing another representation change.
2. **Control reliability:** three staged windows fail closed on safe-clock skew
   outside 2s. The cause of raw UTC versus durable high-water divergence remains
   unestablished; these traces alone do not prove a host clock adjustment.
   Correlate raw UTC, durable high-water, monotonic time and ownership/wait timing
   before changing clock code. Do not relax safe-clock policy or publication UTC.
3. **Capacity isolation:** the DOCX log retains transient `Canonical workspace
   admission exhausted` during projection convergence. Default startup admission
   is fixed; reliable foreground/control/export progress under competing leases
   is not thereby proved. Establish the contention/reconcile behavior with exact
   admission/disk identities rather than increasing quotas or removing cleanup.
4. **Native responsiveness:** the current large atomic native transactions still
   exceed the five-second hold budget. Staged small/processed results cannot
   qualify replacement of that authority for large units.
5. **Open matrix:** million-row, populated-100k, additional format/import matrix,
   sustainable mixed arrivals, cleanup backlog, exact dispatch and live SMB remain
   unqualified. Failed mandatory screens prohibit treating this as completed C5
   or activating the proposed SQLite visibility model.

All owned fork JVMs terminated, and every private state directory and frozen
runtime was removed. Three samples retain `Qualification collector: Qualification
control worker failed` in `cleanup_errors`: collection did not finish successfully,
even though process termination and private-state removal did. No other cleanup
errors were reported. Logs/resources/control traces remain local with versioned
hashes. Root causes and capacity acceptance remain open; deterministic correctness
and repository gates are separate evidence.
