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

`ioc.ingestion.concurrency=1` serializes detection, not all document execution.
The original v3 corpus cases inherit two preparation workers and a window of
four documents from the pinned classpath configuration. One giant document
still has only one bulk document active, but the 100-document v3 profile can
overlap preparation. Its results are labeled as the configured production
pipeline rather than strict single-document evidence. An additional finite
paired batch under `.dev/hybrid-release-single-document/` uses the existing
`execution.preparation-workers=1` and `execution.window=1` through
`--single-document-execution`. It waits for the v3 controller to dispose all its
applications, then runs once for each model, with no retries. This closes the
owner's explicit single-document workload condition without silently rewriting
the earlier measurements. No further measurement grid is queued.

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

## Current status

Reference-envelope and backend selection support is implemented in the existing
qualification harness. The new timing boundary separates durable SMB completion
from independent readback. Both actual SMB smoke cases and the native import
cases and native single-document 100k/1m cases have passed their independent
correctness checks. The 100-document case, large staged cases and final
production choice are pending. Quality gates remain deferred by the owner during optimization;
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
The native single-million-document case is complete; the 100-document batch and
large staged cases remain pending in this snapshot.

| Model | Input | Complete service seconds | Cumulative allocations GiB | Bulk heap / RSS MiB | Shared slice peak MiB | New completed slices / successful SMB publications |
|---|---|---:|---:|---|---:|---|
| Native | 1,000 IOC / 2 DOCX / SMB | 15.02 | 0.22 | 238.91 / 582.70 | Not recorded | 3 / 3 |
| Staged hybrid | Same smoke corpus | 14.92 | 0.59 | 224.72 / 577.96 | Not recorded | 3 / 3 |
| Native | 100,000 detailed URL import rows → 20 hosts | 14.76 | 3.21 | 178.69 / 586.21 | 1,022.76 | 1 / 0; local export |
| Native | 1,000,000 detailed URL import rows → 20 hosts | 117.80 | 31.68 | 193.43 / 630.53 | 2,694.06 | 1 / 0; local export |
| Native | 100,000 distinct IOC / 1 DOCX / SMB | 65.56 | 11.72 | 243.92 / 691.64 | 1,644.82 | 4 / 4 |
| Native | 1,000,000 distinct IOC / 1 DOCX / SMB | 705.29 | 116.87 | 207.96 / 868.16 | 5,120.20 | 5 / 5 |

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
