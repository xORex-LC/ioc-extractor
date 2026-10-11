# CAP-7C C5 second corrective qualification

Status: corrective implementation and mandatory repeat completed on 2026-10-11.
**C5 and G6 remain NOT_ACCEPTED.** Native composition remains the default;
proposed ADR 0041 is not activated. This report supplements the retained
[first corrective qualification](cap-7c-c5-corrective-qualification.md).

## Identity and method

The [implementation record](cap-7c-second-corrective-cycle.md) describes the
clock, admission and export-query corrections. All 12 primary forks ran at
clean HEAD `87fe7b0dca746a4c150bdaa18d104f5435f79680`, with the same frozen JAR
SHA-256 `99fa2227e61183dd85a7810f10ea032aa151f1b506017628ef1fe129b56e28bd`
and identical helper class bytes. The
[versioned evidence](qualification/capacity/cap-7c-c5-second-corrective.json)
retains complete reports, exact driver/policy/helper identities, raw-file
hashes, UTC-step observations, independent oracles and cleanup facts. Private
raw roots are `.dev/cap7c-cycle2-*`.

Every fork uses standard 256 KiB rows, 64 KiB fields, 64 MiB aggregate
preparation admission, 2 GiB per workspace and 8 GiB workspace disk admission.
Canonical private cache reservation remains capped at 512 KiB. CPU=2,
Xms=128 MiB, Xmx=512 MiB, cgroup high/max=768 MiB/1 GiB and swap policy are
unchanged. `--bounded-row-profile` is false throughout. Normal cleanup remains
enabled; the collector runs one clock probe per second and extra real cleanup
after every fifth successful probe. No build, query-plan experiment or JVM
thread dump ran concurrently with these primary forks.

Reproduction uses the existing facade:

```bash
make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar --config docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml --output .dev/<new-root> --rows 100000 --samples 3 --format docx --shape unique --maintenance-every 5'
```

Use `--rows 1000 --samples 1 --format docx --shape unique` for admission smoke.
For processed masks import use `--shape collapse --import-artifact masks
--processed-import`; for AS_IS aggregate use `--shape unique --import-artifact
ioc_aggregate`. Import fixtures are physical CSV files. Both members of each
pair execute; the unchanged driver stops subsequent pairs after a failed
member. DOCX and aggregate retain one of three requested pairs; processed
import completes all three pairs, alternating their execution order.

## Results

Times are conservative handoff-to-completed-slices intervals with the declared
intake quiet time subtracted, not exact dispatch eligibility. An ERROR interval
is an observation, not qualified throughput, allocations, result equivalence
or an exact writer-admission maximum. Failed intervals are excluded from
completed-window medians by the corrected collector.

| Cell | Native SQLite | Staged SQLite | Decision |
|---|---|---|---|
| DOCX 1k unique, standard admission | 3.37s; complete oracles | 8.66s; complete oracles | Both screen-pass; time ratio 2.572 and allocation ratio 3.439 expose staged fixed cost |
| DOCX 100k unique | 69.54s; complete oracles; maximum hold 13.333s, control wait 12.453s | ERROR: control-worker safe-clock rejection `PT2.089S`; observed local interval 421.54s, no completed qualification/oracles | Native fails 45s local, 5s hold and 10s control-wait screens; staged window is incomplete |
| Processed masks CSV 100k distinct detailed URLs → 20 hosts, COALESCE | All three screen-pass, 17.40–18.45s; median 17.65s | All three screen-pass, 16.38–17.52s; median 17.26s | Complete paired semantics and resource screens for this cell |
| AS_IS aggregate CSV 100k unique | 33.51s; complete oracles; maximum hold 16.963s, control wait 15.979s | ERROR: control-worker safe-clock rejection `PT2.037S`; observed local interval 153.77s, no completed qualification/oracles | Native fails hold/control screens; staged window is incomplete |

There are eight SCREEN_PASS, two FAIL and two ERROR results. Ten forks complete
collection and independent fields, complete keys, canonical IDs, provenance,
mutable projections, immutable slices and public slots. Paired completed
oracles agree for admission smoke and all three processed-import pairs. Each
completed fork verifies all five artifacts and all three enabled export
profiles. ERROR forks do not gain an equivalence claim from apparent workload
completion or separately passing component tests.

All six processed-import forks independently record canonical receipt
`COMMITTED`, 20 accepted rows, zero rejected rows and 20 public mutations. Their
service delivery is separately `TERMINAL/SUCCEEDED`, with 100,000 source rows,
20 accepted stage rows, zero rejected rows and no delivery retry. The final
masks set contains 30 rows: 20 collapsed keys plus ten warmup rows. Provenance
is checked independently, and the committed-family census finds no orphan
origins. All three staged forks have maximum publication attempt 1 and no
REBUILD phase.

For those three complete pairs, staged/native time ratios are 0.941, 0.993 and
0.935; allocation ratios are 1.055, 1.064 and 1.047. Native allocations are
3.10–3.12 GiB and sampled RSS 343.6–349.0 MiB; staged allocations are
3.25–3.31 GiB and sampled RSS 369.2–371.4 MiB. Sampled heap peaks are
73.2–85.9 MiB, with no sampled swap in this cell. Native maximum writer holds
are 0.095–0.105s; staged maxima across all operation classes are 0.028–0.032s
(promotion holds are smaller). Staged eligible clock probes complete within
0.9ms, while extra cleanup includes preparation/queueing and takes up to
0.953s. These successful paired observations qualify this cell only.

## Clock diagnosis and collection boundary

The newly adjacent raw UTC/monotonic samples establish actual backward UTC
steps in this environment. Compute each relative step as
`delta(utc_epoch_ns)/1e9 - delta(monotonic)`, retaining both original endpoints
in versioned evidence. The large staged DOCX fork contains 14 negative steps
near 2.48s, approximately 32 seconds apart; aggregate contains five. For example:

- Before: monotonic `178444.727137431`, UTC `1791705291880754915` ns.
- After: monotonic `178444.835871201`, UTC `1791705289505066379` ns.
- Monotonic advances 0.109s while UTC decreases 2.376s: a relative step of
  approximately -2.484s.

The two failure responses preserve raw UTC, monotonic time, durable high-water
and the subsequent clock snapshot. A snapshot can already be CLAMPED below
2s by the time the error handler inspects it: it is a later sample and does not
invalidate the earlier `PT2.089S`/`PT2.037S` rejection. No OS-clock cause beyond
the measured steps is asserted. This establishes an environmental issue in
these forks; it does not retroactively establish the exclusive cause of older
failures that lacked adjacent UTC evidence.

The separately reproduced early-sampling code defect is fixed and retains its
deterministic regressions. Genuine backward UTC outside policy still fails
closed. Do not relax skew/clamp limits, replace publication UTC with monotonic
time or add automatic retries to make qualification pass. Some passing raw
lifetimes also contain a UTC step, without a failed reference-window clock
operation; a passing short sample does not prove stable host UTC.

After their worker fails, ERROR windows do not retain the complete prescribed
extra-control/cleanup load. Their observed local intervals and resource peaks
therefore cannot be used for an accepted before/after speedup or overhead
claim. The corrected `series_screen` retains these negative decisions and
counts zero completed staged windows for DOCX and aggregate.

## Admission and reader amplification

The standard control-preparation queue and nonblocking reservation guard have
deterministic cancellation, retaining-close, failed-pin, memory-refusal and
promotion-progress regressions. The repeated primary logs contain no
`Canonical workspace admission exhausted` or `Canonical control workspace
admission exhausted`. This closes the observed competing-control refusal;
broader mixed/populated-state progress still needs qualification.

The retained clock-only diagnostic fork at
`054f43361fa5307acebc71d8cc6ca7628a2884fa` is ABORTED with a failed staged
collector. Its single thread dump and actual-schema query plans identify the
old completeness check: assignment-view materialization followed by an
assignment scan for every active row. The new `NOT EXISTS ... LIMIT 1` probe
uses the full profile/artifact/lifecycle index in the same snapshot. The
regression executes the production query, checks its plan, rejects a missing
assignment and preserves TTL equality semantics. No new index or identity
contract is introduced. Long readers can impede WAL checkpoint progress;
see the [SQLite WAL documentation](https://www.sqlite.org/wal.html).

Named lifetime observations localize the diagnostic peak to canonical WAL:
19,331 MiB canonical versus 197.5 MiB canonical-private. The repeated failed
DOCX window records 582.3 MiB canonical WAL and 197.5 MiB private WAL,
405.1 MiB sampled RSS, 93.9 MiB heap, 158.2 MiB swap and 514.0 MiB named
workspace. Aggregate records 33.8 MiB canonical WAL, 141.3 MiB private WAL,
372.7 MiB RSS and 2.9 MiB swap. These are retained observations from incomplete
qualification; the query-plan proof is stronger than inferring a controlled
performance reduction from differently failed windows. Named sizes exclude
unlinked SQLite TEMP and do not establish total physical disk high-water.

## Repository gates and remaining work

At measured source HEAD, full `make verify`, `make pmd-analysis`,
`make pmd-watchlist`, `make lint-shell` and `make docs` passed, with fresh
verify/PMD confirmed by `make context`. JDBC runs 513 deterministic/external
shell tests with zero failures/errors and one expected external skip. Tools
contracts include 31 service-capacity tests. Aggregate coverage is 92.32% line
and 85.78% branch; all per-scope ratchets pass.

Raw SpotBugs retains 168 exactly reviewed findings and zero visible findings.
The changed export-query finding preserves validated identifiers and bound
values; no accepted-finding registry was expanded. The complete CPD comparison
confirms one removed binding/result duplication and no new groups, tightening
the exact count from 24 to 23 at unchanged scope/75-token threshold. PMD retains
zero blocking/20 advisory findings; the new complexity finding was resolved by
extracting resource admission helpers. The unchanged 80 watchlist findings were
reviewed, including callback-owned lease cleanup and fatal-error propagation.
Final committed-HEAD verify/PMD freshness remains required after the evidence
commit; source gates do not substitute for it.

All 12 owned JVMs terminated, all private state directories were removed and
each frozen runtime was removed. The two ERROR forks retain collector failure
in `cleanup_errors`, separately from successful process/state cleanup. The
earlier interrupted diagnostic and its raw hashes remain negative evidence.

Next work must stabilize and verify the qualification environment's UTC, then
repeat the failed large cells under the same policy. Large staged preparation,
transfer and complete-window cost remain unqualified; the native large atomic
holds remain over budget. Million-row, populated-state, sustainable mixed
arrivals, exact dispatch eligibility, complete import/format breadth, physical
disk high-water and live SMB remain open. Passing processed import and
deterministic repository gates do not close C5/G6 or authorize activation.
