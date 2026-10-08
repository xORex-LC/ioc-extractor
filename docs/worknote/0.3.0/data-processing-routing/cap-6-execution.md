# CAP-6 whole-service qualification

Status: **G6 NOT ACCEPTED**, 2026-10-08. Qualification tooling and the executed
covering screens are retained; failed capacity, unavailable transport and the
unexecuted acceptance cells remain explicit. The frozen budgets in the
[capacity plan](data-processing-capacity-plan.md) are unchanged.

Compact [reference evidence](qualification/capacity/cap-6-reference.json)
preserves original report identities, raw writer snapshots, corrected window
bounds, phase anchors, resource counters, output signatures and cleanup.

## Execution contract frozen before measurements

The candidate starts at `76058aa4`. The operational installation still uses
`860a3b2b`; it is not the CAP-5 candidate. Measurements use a private user-systemd
unit with `CPUQuota=200%`, `MemoryHigh=768M`, `MemoryMax=1G`, and
`-Xms128m -Xmx512m`. Verify the effective cgroup files rather than assuming that
successful unit creation applies the limits. Production state is never seeded
by SQL. Every initial populated state is built through public intake/import.

The following is the selected covering matrix, recorded before execution.
Each sample starts a fresh private service, warms it with a small document, and
verifies that document before admitting the measured input. Warmup rows remain
in canonical truth and therefore in the output oracle. No baseline/candidate
ratio is claimed by this final acceptance series.

| Cell | Input | Initial state beyond warmup | Required successful primary samples |
|---|---|---|---:|
| smoke | 1k mixed HTML | Empty | 1, harness qualification only |
| html-100k | 100k mostly unique mixed HTML | Empty | 3 |
| docx-100k | 100k mostly unique mixed DOCX | Empty | 3 |
| html-1m | 1m mostly unique mixed HTML | Empty | 3 |
| docx-1m | 1m mostly unique mixed DOCX | Empty | 3 |
| unique-100k | 100k all-unique mixed HTML | Empty | 3 |
| unique-1m | 1m all-unique mixed HTML | Empty | 3 |
| collapse-1m | 1m occurrences, 60 distinct values, HTML | Empty | 3 |
| populated-100k | Fixed 1k mixed HTML | 100k mostly unique document | 3 |
| populated-1m | Fixed 1k mixed HTML | 1m mostly unique document | 3 |
| import-* | 100k accepted CSV rows for each of the five contracts | Empty | 3 per artifact |
| smb-100k | Original seed-43 physical HTML through private SMB send/get | Publicly populated 100k state | 3 |
| mixed/fault | Intake, import, expiry, export/publication; quota, restart, policy mismatch | Declared by each deterministic fixture | Existing tests plus provisioned evidence; no percentiles with N < 200 |

DOCX is a real OPC/WordprocessingML package containing the same paragraph and
section corpus as HTML. It is a generated reference, not a claim about arbitrary
Word documents. Physical input hashes, input/final counts and section counts are
recorded. The independent oracle uses a private disk index and streams canonical
rows and CSV; it does not retain the million-row expected result in Python RAM.

Each first sample is also a feasibility screen. A failed mandatory capacity gate
is retained without retry. Stop repeated executions of that failed cell; its
three-success requirement remains unmet. In particular, an OOM or unbounded
stall is not repeated three times to produce a misleading median. Remaining
cells are explicitly NOT_RUN until executed; known CAP-5 writer failures cannot
be replaced with a smaller-workload pass. Deployment activation requires G6;
failed acceptance leaves the existing stand installation in place.

## Evidence rules

Local admission uses an atomic producer handoff into the private inbox. The
configured stability delay is recorded, and the conservative local window
includes subsequent detection, queues, export cadence and all local slices.
Report this upper bound explicitly; do not call it an exact cadence-excluded
`Tlocal`. Admission/read/commit/export anchors are retained separately. A missing
exact boundary is an open gate, never silently subtracted time.

SMB time starts at producer rename completion and ends after verified remote
markers/manifests and streamed CSV checks for every expected profile. Readback
verification overhead stays visible. Slice coverage must equal the final
canonical revisions, including profiles that first exported partial progress.

Process and effective private-cgroup samplers are external to the measured JVM.
RSS, HWM, CPU, anon/file/kernel, swap, PSI totals, memory.events, CPU throttling,
WAL and workspace/output bytes have explicit units/scopes. Counter regressions,
missing files and sampler errors fail collection. Runtime health supplies writer
wait/hold and preparation/read pressure; it is not a capacity verdict.
Private JFR/NMT/post-GC diagnostics are separate from primary measurements.
Do not describe sampled allocations as exact whole-JVM allocated bytes.

Only reports, manifests, configuration and logs are retained by default. Each
sample owns its unit, private directory and SMB namespace. Stop/join the process
before deleting its state; cleanup errors fail the sample. A bounded timeout
retains failure evidence and still removes DB/WAL/workspace/output copies.

## Progress

- Initial read-only provisioning check passed: a private user-systemd unit can
  apply the requested resource properties. Effective limits will be checked for
  the actual Java process before any measured input.
- Existing CAP-5 evidence already fails the 5s writer screen at 100k and 1m.
  This activates CAP-7C; CAP-6 must quantify whole-service consequences.
- The first smoke exposed a harness boundary error: the actual stand uses a
  file-backed admission journal, while the monitor waited for the JDBC table.
  The warmup completed and all profiles exported, but the harness timed out.
  Preserve `.dev/cap6-smoke-html/report.json` as failed harness evidence; the
  corrected monitor reads the configured durable backend. This is not a
  successful workload sample or a production performance failure.
- Corrected HTML smoke passed the implemented screens and independent output
  oracle (1k, all five artifacts). Conservative local window: 3.920s. The first
  100k primary screen at `d533a0bb` failed: 57.821s conservative local window,
  11.860s maximum PROMOTION hold, 467.30 MiB sampled RSS, 453.38 MiB anon+kernel,
  no OOM or swap, memory PSI full 0.125%. Raw CPU: 53.95s. Final aggregate rows:
  90,054, including the verified warmup; all profile fields/keys/provenance and
  slots passed. Its requested three-success series stopped after this failed
  screen. `.dev/cap6-html-100k/report.json` retains the failure and cleanup proof.
- The next harness revision adds independent mutable CSV/durable-generation
  checks, JDK 21 heap/GC counters, large AS_IS import fixtures for each contract,
  and private SMB publication/readback. Jstat samples are at most once per
  second; column/schema drift fails collection. Metaspace is reported without
  double-counting compressed class space; it is not complete non-heap/native
  accounting. [JDK 21 counter definitions](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jstat.html).
  NMT and sampled JFR allocation diagnostics remain separate private runs.
- The initial workspace byte series incorrectly selected the previous directory
  name and reported zero. This field is invalid in the earlier smoke/100k
  evidence; other independent process/cgroup/writer/output counters remain
  usable. The corrected sampler includes the configured default
  `var/document-preparation` and import `workspaces/staging` directories.
- First import smoke stopped before workload admission because `jstat` was
  absent from PATH. The current Java runtime has `jdk.jcmd`; use its matching
  JDK module launcher when a diagnostic binary is absent. Retain this failed
  collection attempt separately, with cleanup proof; it is not an import result.
- A second pre-admission import attempt exposed optional GC columns with `-`
  (unsupported counter); only the required defined columns are now parsed.
  The corrected 1k IP import passed in 4.876s (conservative window including
  import stability/reconcile), accepted 1,000 of 1,001 source rows, removed the
  duplicate without slot reuse, and verified the COMMITTED receipt, all local
  slices, mutable files, complete keys, provenance and requested slots. Sampled
  RSS: 335.64 MiB, used heap: 76.15 MiB; state and process removed.
- SMB smoke is **unavailable**, not passed: server tree-connect returned
  `STATUS_REQUEST_NOT_ACCEPTED` before document admission. Credential-backed
  encrypted smbclient namespace provisioning and removal succeeded, but the
  service's persistent share connection failed and readiness timed out. This
  status reports exhausted server connections
  ([MS-ERREF](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-erref/596a1078-e883-4972-9bbc-49e60bebca55)).
  The owning unit, authentication file, private local state and remote namespace
  were removed. `.dev/cap6-smb-smoke/report.json` and its log retain the failure.
  Live full-cycle measurement awaits available server connection capacity;
  transport acceptance cannot be inferred from the local tests.
- The first mostly-unique HTML 1m screen failed at `READ_SOURCE` with
  `java.lang.OutOfMemoryError: Java heap space` in Tika's HTML SAX traversal.
  No measured document commit or final slices occurred. The preparation worker
  died while the JVM remained active; the private unit was explicitly stopped
  instead of waiting for a meaningless publication timeout. Its DB/WAL/source
  and frozen runtime copy were removed. The sampler then lost its process and
  correctly refused a successful summary. Raw partial observations already
  show RSS 743.79 MiB, anon+kernel 723.65 MiB and sampled used heap 469.82 MiB;
  these are partial diagnostic maxima, not a successful complete resource
  window. `.dev/cap6-html-1m/report.json` records the interrupted/failed screen;
  its log is the OOM authority. CAP-7A is now demonstrated by the whole service,
  rather than inferred from an upstream-only profile.
- The harness now detects worker OOM from the owned incremental log before
  systemd considers the JVM dead. It captures resource facts before stopping
  that unit and distinguishes workload failure from collector errors. CSV
  headers are checked against the configured ordered schema, including required
  IDs. A failure to retain an evidence file still removes terminated private
  state and fails the report; regression tests cover both ownership paths.

## Completed screens and their interpretation

The candidate JAR was built at `76058aa4`, SHA-256
`4208101eb164bef6b382b706c760a64ded8efd9564885285ca705279d242d58a`.
Later commits changed qualification tools/docs only. Some harness commits were
rewritten before the 2026-10-08 continuation; original measured commit values
remain in evidence. Do not attribute these measurements to a newly repackaged
JAR merely because its Java sources are unchanged.

Each row below is one primary screen, not a median. All completed 100k screens
passed the implemented independent canonical/immutable output oracle; later
screens also checked mutable projection fields and durable generations. These
checks cover complete configured keys, public fields, canonical IDs, positive
lifecycle deadlines, source provenance, sparse slots and final revision
coverage of all three profiles. They do not close the remaining independent
diagnostic/rank-timestamp oracles in G6.

| Cell | Conservative local window, s | Maximum PROMOTION hold, s | Sampled RSS, MiB | Result |
|---|---:|---:|---:|---|
| Mostly unique HTML 100k | 57.821 | 11.860 | 467.30 | Time and writer FAIL |
| Mostly unique DOCX 100k | 65.581 | 13.709 | 402.21 | Time and writer FAIL |
| All-unique HTML 100k | 73.757 | 16.207 | 462.15 | Time and writer FAIL |
| AS_IS IP import 100k | 37.456 | 23.015 | 390.43 | Writer FAIL |
| AS_IS masks import 100k | 40.352 | 24.694 | 395.55 | Writer FAIL |
| AS_IS hashes import 100k | 37.454 | 23.162 | 393.12 | Writer FAIL |
| AS_IS blacklist import 100k | 26.059 | 14.654 | 345.04 | Writer FAIL |
| AS_IS aggregate import 100k | 35.254 | 19.598 | 356.00 | Writer FAIL |
| Incoming 1k over publicly populated 100k | 6.267 | <= 1.553, bound | 469.36 | Memory PSI full 1.877% FAIL |

Import windows include import stability/reconcile waits. All five imports
preserved accepted AS_IS values, case and paths; the duplicate was removed and
requested export-slot holes were preserved. Receipts were COMMITTED and
deliveries TERMINAL/SUCCEEDED. A single sample below 45s cannot establish the
30s median gate, and writer failures independently prevent acceptance.

The HTML/DOCX/all-unique serial stage durations sum to **53.742 / 60.214 /
68.329 seconds** respectively. These lower bounds exclude configured cadence,
intake pinning and final output convergence. Thus the time failure is supported
even though exact eligible-source `Tlocal` is still open. HTML preparation/write
account for 25.420/26.733s; DOCX for 27.212/30.864s. A faster network or a shorter
quiet period cannot remove these costs.

The populated case originally reused the lifetime writer maximum from initial
population. That violation is invalid for the incoming document: five measured
promotions held the writer for 1.553s **in total**, bounding each below 5s. The
current harness computes deltas and distinguishes exact maxima from upper
bounds when a lifetime maximum does not change. The PSI violation remains; no
rerun or erased failure is used to make this cell green.

## Million-occurrence failures

- Mostly unique HTML fails in READ_SOURCE with Java heap OOM. Its interrupted
  collector has only partial maxima, not a successful resource summary.
- Repeated HTML (1m occurrences, only 60 distinct values) also fails in
  READ_SOURCE. The early failure window records RSS 725.48 MiB and anon+kernel
  705.30 MiB. This rules out final winner cardinality as a sufficient memory
  bound for the source reader. No measured canonical commit occurred.
- Real generated DOCX reaches preparation but fails with `SQLITE_FULL` at the
  private reducer's configured page cap. The first preparation attempt lasted
  approximately 246s; the then-current harness waited through a retry until
  its 600s timeout. Failure-window RSS reached 762.27 MiB, anon+kernel 741.14
  MiB, workspace 1,083,281,296 bytes, and swap 248,078,336 bytes. No measured
  canonical commit occurred. This is not completed million-row throughput.

The host had ample free disk. The 2 GiB workspace ownership budget reserves
space for a worst-case DELETE journal, bounding the database to roughly half
that size. The cap worked; the current encoding/index density cannot complete
this reference inside it. The harness now stops on the first `SQLITE_FULL`;
neither the heap nor the workspace limit was increased.

Unique-1m and populated-1m remain NOT_RUN because their prerequisite HTML
million input already fails. Failed primary cells were not repeated to obtain
statistics. The three-success requirements remain unmet.

The observed worker death with a live JVM also exposes an overload-safety
failure. Code inspection finds `DurableDocumentDispatcher.prepare` catches
RuntimeException only; Error bypasses `job.ready` and `nudge`, leaving the
ordered head in the process-local job map. This can prevent later promotion
until restart. The head-blocking consequence is a code inference, not an
executed follow-on-document test. Published issue ING-15 owns it. The installed
service currently has `ExitOnOutOfMemoryError`; the frozen private reference
does not, and a JVM exit alone would not prove admission recovery.

## Separate diagnostic run

The 100k JFR/NMT run passed output checks but failed time/writer screens:
61.457s conservative window and RSS 501.85 MiB. Diagnostic overhead excludes
it from primary comparisons. Serial GC reports both young and tenured used
heap; the corrected post-GC value is **50,726,912 bytes (48.38 MiB)**. The old
45,056-byte value counted only young generation and is invalid.

Within the producer-handoff-to-local-slices interval, 11,942 JFR allocation
samples carry approximately **13.95 GiB of weighted allocation estimates**.
The promotion thread accounts for about 69% of that estimate. The first IOC
frame is `DocumentRowCodec.text` for approximately 4.51 GiB: it creates UTF-8
decoders and buffers per non-null field. This is evidence of serialization
churn, not proof that every byte is attributable exclusively to that method,
nor an exact whole-JVM/caller allocation counter. NMT reports 430,537 KiB
committed before forced GC, including 38,990 KiB tracing overhead; it does not
cover every SQLite/JNI allocation and must not be added to RSS. One retained
snapshot cannot establish a leak or a multi-job memory plateau.

## Upgrade, transport and remaining gates

`make service-capacity-upgrade UPGRADE_ARGS='--previous … --candidate … --config
… --output .dev/…'` passed a publicly seeded, drained private rehearsal at
`33aa7e91`. Previous service/dataframe schema was 12/12; candidate was 14/12.
All five canonical datasets, IDs, lifecycle, provenance, mutable files and
three profiles survived. Both units were stopped before a coherent filesystem
backup/restore; restored bytes and schema were verified **before** launching
the previous executable. It then passed the same checks at 12/12. This proves
the drained backup-restore path, not in-flight policy migration or production
rollback without data loss. Two earlier startup attempts remain ERROR evidence:
the harness accidentally let Spring auto-load the candidate `application.yml`
alongside the old policy. Separate non-default policy filenames fixed that
isolation error; a regression test now asserts it.

The first SMB smoke failed with the explicit server connection-limit status.
After WSL restart, a separately recorded smoke again timed out before readiness
and document admission, but its log has no such status. The current cause is
unresolved; it must not be assigned the previous cause without evidence.
Credential-backed encrypted namespace provisioning/removal passed both times.
No full `Tsmb`, populated physical 100k transport acceptance or deployment
activation is claimed.

Existing deterministic suites cover receipt-only finalization, cross-artifact
atomic import rollback, lost hints, quotas, ordering, shutdown/restart,
projection rename/ack and profile/publication recovery. Final reactor execution
must verify their actual discovery and outcomes. It does not replace sustainable
mixed-load latency samples (>=200 operations per reported class), fatal worker
recovery, exact dispatch eligibility anchors or live transport evidence.

All executed private processes were terminated before removal of their
DB/WAL/workspace/source/CSV/runtime copies. Only about 30 MiB of local reports,
logs, resource samples and JFR remain; compact evidence is versioned. No
production installation or database was migrated by these experiments.

CAP-7A (streaming/bounded source plus fatal-failure ownership), CAP-7D (private
reducer serialization/density) and CAP-7C (largest atomic writer occupancy)
now have measured triggers. Their implementation is outside CAP-6. Resolve
those mechanisms and repeat the failed/dependent G6 cells under the same
budgets; there is no evidence here requiring a Camel replacement.
