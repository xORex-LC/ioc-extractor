# Production capacity closure

Owner steering received on 2026-10-12/13: replace an indefinite optimization
loop with a finite production choice against a plausible equipment and workload
envelope. This document supplements the historical capacity evidence; it does
not relabel a failed frozen C5/G6 gate or declare an unmeasured result accepted.

## Decision and finish rule

Compare the production native path and the final bounded hybrid candidate on
the same policy, input and declared resources. Prefer the simplest model that
meets the complete-cycle requirement and preserves data/recovery guarantees.
Staged availability is valuable only when it justifies its additional physical
work. Native is already production-wired; the hybrid remains qualification-only.

The owner's supplied bands are feasibility guidance: 5–15 minutes is the
preferred range, up to 30 minutes is a provisional finish criterion for the
declared million-input complete cycle, and 60+ minutes requires attribution.
They are not universal hardware-independent SLOs. The stage-by-stage intervals
are estimates, not quantities to add or claim as measured project performance.

Stop performance redesign when the declared scenario meets the agreed band,
independent data/receipt/recovery checks pass, and remaining operational costs
are documented. Then close production composition, upgrade/recovery ownership,
documentation and the deferred build-quality gates. Do not introduce another
backend, dispatcher, segment engine or automatic corrective cycle merely to
improve a percentage. A failed candidate can be rejected in favor of native.

## Reference equipment and measurement scope

| Dimension | Reference |
|---|---|
| Equipment | 4 vCPU, approximately 8 GiB RAM, local SSD/NVMe |
| Workload | 1,000,000 input occurrences before reduction; ordered canonical promotion; explicit preparation workers/window below |
| Application and collector | Shared 4-CPU cgroup, 5 GiB high / 6 GiB hard memory limit; 2 GiB reserved for the rest of an 8 GiB host |
| JVM | Xms 256 MiB, Xmx 2 GiB; a ceiling rather than required live heap |
| SQLite tuning | Existing balanced preset on both public stores; identical for both models, correctness PRAGMAs unchanged |
| Swap | Disabled within the reference slice; no swap setting changes outside it |
| Private disk | Explicit existing diagnostic 8 GiB workspace / 32 GiB total; actual DB/WAL/workspace/output consumption reported |
| Durability | Existing FULL durability, publication visibility, fences, safe UTC and receipt recovery unchanged |

The application and collector both belong to `ioc-capacity-reference.slice`.
Before intake the harness checks applied child and parent limits. The host is
still WSL with about 16 GiB assigned: the slice emulates the application resource
budget, not an independently provisioned 8 GiB machine or physical NVMe guarantee.

The stand configuration is `/srv/ioc-extractor/etc/application.yml`, credentials
are provided by its EnvironmentFile and are never copied into reports. Its SMB
endpoint is loopback `127.0.0.1`, so it can qualify the real fetch/publish protocol
but cannot measure a physical 1 Gbit/s LAN. The existing service and its DBs,
inboxes and remote directories are not modified. Each test uses an unpredictable
owned remote namespace and private service/dataframe databases and removes them.

Business policy is the pinned `cap-7c-c5-policy.yml` qualification fixture; only
the stand's sync policy is copied. The fixture uses the supported file document
admission journal and JDBC ingest-run/export/publish/import ledgers. It exercises
the real Spring binding, preflight and components, but it is not the installed
stand's complete configuration. Reports identify these settings; capacity for
an unmeasured operator policy or a different admission backend is not inferred.

The reproducible corpus includes one large DOCX across five artifacts, a processed
CSV collapsing detailed URLs to hosts, and 100 deterministic DOCX files totaling
one million occurrences. Batch completion waits for every document's successful
terminal admission and completed run, rather than accepting the last file alone.
The multi-document profile declares a 30-second export quiet period / 30-minute
cap using the existing cadence mechanism; actual slice/publication counts remain
evidence, not an assumed one-final-publication guarantee. Mutable projections
can still be rebuilt repeatedly: cadence alone does not eliminate that cost.

The reference retains the existing concurrent combined projection-error/ACK
probe and requests one extra maintenance page after every five completed probes.
The probe schedules its next command one second after completion; it does not
create an unbounded queue during a native writer hold. Actual command counts,
applied/no-op results and waits are recorded. This adds deliberate control and
maintenance pressure to the service; it is not a quiet production-only baseline
or proof of equal numbers of applied controls across the two backends. Ordinary
input parsing is successful; synthetic projection probes are a separate load.
The service boundary excludes the further 96 post-completion maintenance calls.

`ioc.ingestion.concurrency=1` serializes detection, not all document execution.
The original v3 corpus cases inherit two preparation workers and a window of
four documents from the pinned classpath configuration. One giant document
still has only one bulk document active, but the 100-document v3 profile can
overlap preparation. Its results are labeled as the configured production
pipeline rather than strict single-document evidence. A final native batch
under `.dev/hybrid-release-single-native/` uses the existing
`execution.preparation-workers=1` and `execution.window=1` through
`--single-document-execution`. It waited for the staged giant case to
finish and dispose its application, then ran once. It failed to complete all
ingest runs because of ING-17; it does not close the owner's explicit condition.
The initially queued paired batch under
`.dev/hybrid-release-single-document/` was stopped before admission. After the
staged giant service interval was observed at 1,934.65 seconds, the two additional
staged batch cells were deferred under the finish-first choice: native already
meets the complete-cycle band and remains the production model. The current
staged case later failed during its post-publication census, as recorded below.
Deferred cells are not
passed, failed workloads or a whole-matrix completion claim. No further
measurement grid is queued. A single source-fixed regression run under
`.dev/hybrid-release-single-native-fixed/` is the remaining qualification, not
an automatic retry of the failed binary or another performance redesign.

## Finite measurement sequence

1. Retain the old 2-CPU / 1 GiB raw windows and completed 100k results. The
   remaining million-document window was deliberately interrupted after resource
   re-scoping. Its collector status is ERROR with caller interruption; it has
   no complete-cycle timing or resource pass. Its application was terminated
   and private heavy state removed. The old eight-point controller was stopped;
   no additional old-envelope million runs are queued.
2. Validate the reference envelope and both model fixtures at a small size.
3. Compare native and final hybrid at 100k/1m with the same local independent
   fields, complete keys, winners, canonical IDs, provenance, slots, diagnostics
   and receipt payload checks. Retain all failed cells without automatic retries.
4. Run the actual SMB document cycle for both candidates. Stop service timing
   at durable successful publication (including service verification), then
   independently download/hash/check the outputs. Post-publication diagnostic
   receipt scans and 96 cleanup calls are separately timed and cannot inflate
   the reported service completion interval.
   Include the 100-document profile, not only the giant DOCX. The reference
   control collector has an explicit 300-second request deadline rather than
   the legacy 40-second deadline: a long native transaction is measured as a
   delay, not silently treated as an HTTP collection failure. This deadline
   is not an accepted control-latency SLO or changed production timeout.
5. Select the production model using complete time, resources, control delays,
   disk/cleanup and recovery. A repeat confirms the chosen model if the evidence
   is near the decision boundary or noisy. Preserve unknown sustained arrival
   rates and populated/history-heavy scenarios as limits, not promises.

The eight changes and frozen per-point executables remain available. Their
remaining intermediate cost measurements are deferred while the terminal
production comparison is evaluated. This is a scope change under the owner's
finish-first steering, not completion of the original intermediate matrix.

## Architectural risks to assess before production

- Generic Staged versions and bounded durable chunks can improve control access
  while increasing total transfers, index work, sync calls and cleanup. Short
  writer holds are not a throughput proof.
- Repeated whole-file projections/slices/publications across many deliveries
  can dominate the complete batch. Measure actual counts and use existing
  coalescing/cadence owners before introducing another execution framework.
- Ordinary receipt replay is also a normal repeated-document path. Storage
  savings must include replay and retention costs, not only write latency.
- Cleanup must preserve pinned snapshots and replay evidence. Finite catch-up
  is verifiable now; sustained equilibrium needs a declared arrival rate.
- Cgroup limits include filesystem cache. Legacy `memory.high=768 MiB` caused
  repeated reclaim and swap despite low live heap; CPU throttling was small.
  That observation does not prove memory accounts for the whole elapsed time.

The relevant primary references are [Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)
and [SQLite WAL](https://www.sqlite.org/wal.html). SQLite has one writer and FULL
commits require synchronization. Neither more CPU nor relaxed durability is a
substitute for removing unnecessary work while retaining the data contract.

## Correctness finding outside the performance choice

Inspection of the live strict batch found `INGEST.RECOVERY_FAILED` with
`Document admission changed concurrently`. Three occurrences were observed
before completion; the queue continued progressing. The retained reference logs
also contain one occurrence in the native smoke and one in the staged giant
case. This is not a CPU, memory or SQLite throughput limit.

The ordinary completion path and reconciliation both call
`DocumentAdmissionService.complete` / `finalizeRegistration`. A failed CAS
accepts only equality with the entire proposed aggregate, including its
timestamp/version. Equivalent concurrent finalization can therefore throw;
normal completion archives the source before this call and marks the ingest run
complete afterwards. No canonical data loss is established, but eventual
successful outputs are not proof that the race is acceptable for production.

The persistent issue is [ING-17](../../../KNOWN-ISSUES.md). The original strict
batch eventually failed its 3,600-second local-completion deadline, despite
completed preparation/canonical writes for all 100 bulk inputs. The logs contain
102 completed pipelines (including two warmups), but only 101 successful source
completion observations. There is no qualified complete-cycle time for this cell.
Owned application/remote state was removed; the failed logs remain local.

The narrow source fix accepts only matching durable terminal facts after a lost
CAS, verifies registration pins on already finalized retries and retains strict
nonterminal transitions. Seven regressions force competing terminal writes,
completion versus recovery and changed outcome/source/registration. Three fail
on the original frozen binary; all seven plus seventeen existing checks pass on
the fix. The tooling's 53 focused tests also pass; a known terminalization
conflict now prevents a successful qualification status even if outputs converge.
These are focused checks, not deferred reactor gates.

A single corrected-binary replay is planned. Its application JAR digest is
`85f1cbd135eb2700c842b6513710b02c5469dd48be0d29713801651c04f241e3`;
the only production class changed from the preceding frozen runtime is
`DocumentAdmissionService`. The previously tested one-pass census helper is also
included. The original runtime and failed measurement are not replaced. This
bounded correctness fix belongs before production readiness, not to another
storage performance loop.

## Current status

Reference-envelope and backend selection support is implemented in the existing
qualification harness. The new timing boundary separates durable SMB completion
from independent readback. Both actual SMB smoke cases and the native import
cases and native single-document 100k/1m cases and the configured native
100-document case have passed their independent correctness checks. The original
strict native single-document batch failed on ING-17; its corrected-binary replay
is the remaining case. The staged million-DOCX
collector failed in its post-service census, and later staged batches are
deferred under the finite finish rule. Native remains the production choice,
subject to the explicit execution-window and finalization regression check. Quality gates remain deferred by the owner during optimization;
no deployment, schema activation or completed acceptance is claimed.

The first reference smoke attempts failed before application intake because the
collector used `export.cadence` instead of the real `export.trigger` property.
The next attempts applied the reference resource limits successfully but both
backends failed readiness: the SMB server returned `STATUS_REQUEST_NOT_ACCEPTED`
and `SYNC.REMOTE_RESOURCE_EXHAUSTED`. These are not storage-throughput results.
Both owned applications and remote namespaces were removed. The owner then
increased the share connection limit to 12; subsequent attempts have a new
measurement identity and retain the earlier failures.

Raw reference sequences are stored under `.dev/hybrid-release*`. The third
sequence uses `.dev/hybrid-release-reference-v3/` and the transient
`ioc-production-capacity-reference-v3.service`. No automatic retry loop is used.
The harness also records aggregate export and publish operation counts before
the workload and at durable service completion, so multi-document work cannot
silently assume one final full snapshot per profile.

## Observed reference results

Sequence: `.dev/hybrid-release-reference-v3/`. Frozen application JAR SHA-256:
`be42a81ec73e88615190b14d19fd297d021295bc5fbf7680d14411bd32523c06`.
Each report also records its helper classes, driver hashes, corpus and effective
limits. These are single-fork diagnostic observations, not production percentiles.
The native single-million-document case and configured 100-document batch are
complete; the original strict native single-document batch is unsuccessful, and
the corrected-binary replay remains pending in this snapshot.
The staged million-DOCX service interval is retained with its failed
post-publication collection status, rather than promoted to qualification.

| Model | Input | Complete service seconds | Cumulative allocations GiB | Bulk heap / RSS MiB | Shared slice peak MiB | New completed slices / successful SMB publications |
|---|---|---:|---:|---|---:|---|
| Native | 1,000 IOC / 2 DOCX / SMB | 15.02 | 0.22 | 238.91 / 582.70 | Not recorded | 3 / 3 |
| Staged hybrid | Same smoke corpus | 14.92 | 0.59 | 224.72 / 577.96 | Not recorded | 3 / 3 |
| Native | 100,000 detailed URL import rows → 20 hosts | 14.76 | 3.21 | 178.69 / 586.21 | 1,022.76 | 1 / 0; local export |
| Native | 1,000,000 detailed URL import rows → 20 hosts | 117.80 | 31.68 | 193.43 / 630.53 | 2,694.06 | 1 / 0; local export |
| Native | 100,000 distinct IOC / 1 DOCX / SMB | 65.56 | 11.72 | 243.92 / 691.64 | 1,644.82 | 4 / 4 |
| Native | 1,000,000 distinct IOC / 1 DOCX / SMB | 705.29 | 116.87 | 207.96 / 868.16 | 5,120.20 | 5 / 5 |
| Native | 1,000,000 IOC / 100 DOCX / SMB; workers/window 2/4 | 640.05 | 385.31 | 266.07 / 820.97 | 5,120.12 | 3 / 3 |
| Staged hybrid | 100,000 detailed URL import rows → 20 hosts | 16.63 | 3.30 | 166.42 / 543.35 | 1,071.17 | 1 / 0; local export |
| Staged hybrid | 1,000,000 detailed URL import rows → 20 hosts | 118.89 | 32.79 | 196.80 / 589.02 | 2,787.01 | 1 / 0; local export |
| Staged hybrid | 100,000 distinct IOC / 1 DOCX / SMB | 151.49 | 27.29 | 227.93 / 693.17 | 2,602.68 | 5 / 5 |
| Staged hybrid | 1,000,000 distinct IOC / 1 DOCX / SMB; diagnostic ERROR | 1,934.65 observed | 265.44 | 286.40 / 955.20 | 5,120.31 | 5 / 5 |

SMB time starts with producer upload and ends with successful durable publication
of all required final profiles; local import time starts with handoff and ends
with terminal delivery and completed immutable export. Both include configured
cadence. Allocations are cumulative churn over the bulk measurement, not retained
RAM. Shared slice memory includes the collector and file cache; this counter was
added after the two smoke collectors loaded, so their missing values remain missing.

The 100k distinct document demonstrates why elapsed time alone cannot choose a
backend: native's maximum writer occupancy was 9.418 seconds and maximum observed
control wait was 9.414 seconds. Its complete receipt scan read 216,987 rows in
0.82 seconds. The smoke staged candidate occupied the writer for at most 13.07 ms,
but 96 additional post-service maintenance probes took 50.73 seconds, compared
with 0.11 seconds for native. These probes include retention/control preparation
as well as a bounded maintenance page; their sum is not a measured per-row GC
cost and is excluded from the service completion interval. Protected receipt
payloads are not eligible cleanup debt.

The native million-DOCX cycle took 11 minutes 45 seconds through durable SMB
completion and passed the subsequent independent checks. It creates 2,166,987
final rows across the five overlapping artifacts, rather than one million
physical SQLite rows. Writer occupancy reached 169.205 seconds; maximum
observed control wait was 168.469 seconds. The complete 2,166,987-row receipt
replay took 7.98 seconds. After extra cleanup the dataframe DB occupied
3,754.00 MiB and its named WAL 64.00 MiB. The sampled bulk workspace peak was
about 1,451 MiB. The shared slice reached its 5-GiB high threshold, with 15,847
high events but no OOM/hard-limit events or swap. File cache was the dominant
shared memory category. These results qualify elapsed time for this declared
case; they do not satisfy the historical five-second writer-occupancy limit or
establish an accepted control-latency SLO.

The configured 100-document native batch also passed its independent checks.
Its 10-minute-40-second service interval includes all 100 documents and exactly
three new final slices/publications, one per profile. It produces 2,167,020 rows
across the overlapping artifacts. Maximum promotion occupancy was 2.414 seconds;
the overall maximum writer hold was 5.032 seconds in export-slot allocation,
with a 5.029-second maximum observed control wait. Complete receipt replay read
102 receipts / 2,167,020 rows in 12.13 seconds; the post-cleanup dataframe DB was
3,721.76 MiB with a 64-MiB named WAL. Cumulative allocations reached 385.31 GiB,
but sampled bulk heap/RSS were 266.07/820.97 MiB, and there
were no OOM events or swap. This is allocation churn, not hundreds of GiB of
required RAM. At durable SMB completion the reference slice had recorded 4,381
high events; application CPU consumption was 894.51 CPU seconds and GC took
3.74 seconds, with only 0.153 seconds of CPU throttling. The later post-diagnostic
snapshot records 6,554 high events / 906.38 CPU seconds / 3.92 GC seconds; it is
not the bulk-only resource counter.

The batch uses the existing two preparation workers / four-document window.
Its corpus and cadence also differ from the one-giant-document case, so the
timing difference is not a causal architecture speedup. It does demonstrate
that document granularity changes the control-delay tradeoff substantially.
Existing final-export cadence coalesces remote publications; it does not
eliminate per-document mapping, promotion or mutable-projection work. Those
costs remain part of the measured service rather than another optimization
cycle launched without a failed complete-cycle requirement.

The staged 100k document passed its independent checks in 151.49 seconds.
Maximum writer hold/control wait were 62.45/41.50 ms, and complete receipt
replay took 1.67 seconds. Its post-cleanup dataframe DB occupied 848.77 MiB
with a 64-MiB named WAL. The 96 additional maintenance calls took 87.59 seconds:
eligible physical debt fell from 66,692 to 65,981 rows, while 1,151,740 protected
rows remained. Protected retained facts are not garbage, but the remaining
eligible debt also must not be reported as caught up or as sustainable GC.

Bulk staged phase counters attribute 75.50 seconds to transfer, 52.92 seconds
to commit, 33.64 seconds to native private preparation and 16.31 seconds to
hydration, with 4,781 hidden chunks and one rebuild. These are overlapping
unit-level counters: commit is part of transfer, and they are not additive
wall-clock stages. The declared 256-effect/byte chunk ceiling trades many
FULL-durable commits for short writer ownership. That ceiling is a policy of
this candidate, not an immutable SQLite throughput law. Changing it would
require a new pinned experiment and control-delay contract; it is not necessary
merely to optimize a percentage after a simpler production model meets the
complete-cycle requirement. Extra materialization and retained versions remain
costs of the selected staged representation. Actual final publications also
differ between the giant-document cells, so their timings are whole-service
observations rather than an isolated SQL-kernel speedup.

The staged million-row processed import also passed: 118.89 seconds, with a
10.85-ms maximum writer hold and a 0.04-ms maximum control wait. This profile
reduces to twenty hosts; its short durable changes do not establish the same
cost for a million distinct canonical records. Its 96 extra maintenance calls
took 50.62 seconds, measured after service completion.

The staged million-DOCX service completed and durably published in 32 minutes
14.65 seconds. The collector then failed with a 900-second timeout in the
post-service `receiptFamilies` diagnostic. Its retained status is **ERROR /
INCOMPLETE_POST_PUBLICATION**, not CORRECTNESS_PASS or production acceptance.
The later independent canonical/provenance/CSV/remote-readback oracles did not
run. Before that failure the complete 2,166,987-row real-port receipt replay
finished in 36.75 seconds and matched the native payload/ordered-position
checksum. This proves that receipt observation, not the omitted canonical oracles.
All private application and remote state was disposed after the failure.

Its successful exact bulk counters record 41,868 promotion admissions, maximum
promotion ownership of 1.716 seconds, maximum overall writer ownership of
5.835 seconds in CONTROL, and maximum CONTROL wait of 284.30 ms. The 96 extra
maintenance calls took 740.85 seconds, excluded from the service interval. The
post-cleanup dataframe DB was 8,024.54 MiB with a 64-MiB named WAL. At durable
service completion application CPU use was 2,479.81 CPU seconds and GC took
3.204 seconds; the shared slice recorded 112,904 high events without hard-limit,
OOM or swap events. These costs must not be replaced by post-diagnostic counters.

Overlapping bulk phase counters record 1,035.26 seconds in transfer (including
650.82 seconds in commit), 698.07 seconds in hydration, 487.22 seconds in native
private preparation and three rebuilds. They explain where this composition
spends work, not an additive wall-clock partition. It buys much shorter promotion
ownership than native at substantial total physical cost. The giant-document
observation is above the preferred/provisional complete-cycle band and is
incomplete qualification; no further candidate corrective loop is inferred.

The diagnostic defect was a repeated `dbstat` inventory query inside the logical
family loop. Its OR/subquery filter caused a whole virtual-table traversal for
each family, making a large post-publication census expensive. The source helper
now inventories btrees once with the documented [DBSTAT aggregate mode](https://www.sqlite.org/dbstat.html),
then associates table/index page totals from `sqlite_schema`. A focused exact
SQLite regression passed: populated tables, a secondary index and an empty table
retain the independent page/payload totals while only one dbstat query runs.
The running old frozen helper was not replaced; its failed observation remains
unchanged. The repair changes measurement tooling, not production elapsed time
or data publication. No million-input candidate rerun is queued.

The independent post-service full-row/CSV/receipt/fingerprint verification adds
several minutes to the qualification job. It does not represent additional
production processing after the durable SMB completion boundary. Private service,
dataframe, workspace and owned remote state were removed after verification.

## Production configuration consequence

The packaged defaults still describe a smaller installation: 2 CPUs,
`MemoryHigh=768M`, `MemoryMax=1G`, JVM `-Xmx512m`, a 2-GiB document workspace and
an 8-GiB total private-workspace ceiling. The new results do not qualify those
defaults for the million-input scenario. A production bulk profile must declare
the measured resource and disk envelope explicitly; changing the architecture
while retaining the old hard memory limit would not reproduce this evidence.
The installed stand and packaging defaults have not been changed by the
qualification. Choosing and documenting that deployment profile is part of
production closure, after the finite backend comparison.

The 117.80-second import case has strong reduction to 20 hosts. It proves the
full processed-import workload and its receipt/COALESCE checks under this
envelope, not a million distinct canonical rows, Tika or SMB delivery throughput.

## Comparison contract boundaries

Both smoke cases pass independent fields, complete keys, provenance, public
output and receipt checks. Their exact cross-model fingerprints differ in
aliases and internal IDs of the two artifacts without public IDs. The staged
backend durably reserves private candidate ID ranges before preparation; native
uses its owned AUTOINCREMENT path for those internal identities. Unused
reservations remain burned. The same physical input and receipt payload do not
therefore establish exact numeric internal-ID equality across fresh databases.
The summary explicitly reports `NOT_EQUIVALENT_UNDER_EXACT_ORACLE` and publishes
no paired speedup or acceptance ratio. Migration must still preserve existing
identities and enforce non-reuse; this observation cannot waive that requirement.

There is also an intentional timing-contract difference at an expiry crossing.
Production native samples one effective UTC after acquiring its transaction's
write ownership, as specified by ADR 0020. Proposed ADR 0041 samples fresh safe
UTC at the final staged publication and rebuilds expired prepared matches into
new lifecycles. Its text limits that refinement to the staged protocol. The
12-hour-TTL reference cases do not cross a deadline and cannot qualify equality
at this boundary. A production choice must state which accepted timing contract
it uses; it must not silently apply the proposed refinement to native or claim
all-behavior equivalence from these timings.
