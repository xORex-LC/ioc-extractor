# CAP-6 whole-service qualification

Status: in progress. The frozen budgets in the [capacity plan](data-processing-capacity-plan.md)
are unchanged. A completed experiment and accepted capacity are separate outcomes.

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
