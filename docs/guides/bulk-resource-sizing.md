# Bulk resource sizing

[Русская версия](ru/bulk-resource-sizing.md)

Use an explicit resource profile when rehearsing a million-input batch. The
packaged installation defaults target a smaller service: two CPUs, a 512-MiB
maximum Java heap, a 768-MiB cgroup reclaim/throttling threshold and a 1-GiB hard
limit. They are not a qualified million-input capacity promise. The production storage backend
is Native SQLite, the only supported canonical backend.

## Declare the workload

Count input IOC occurrences before reduction, documents, final canonical rows
per artifact, and the already active database separately. One input can appear
in several artifacts. A million URLs collapsing to twenty hosts is a different
workload from a million distinct indicators.

Record document sizes and formats, preparation concurrency, output policy,
retention, and the number of full export/publication operations. A throughput
number without these dimensions is not sufficient to size an installation.
Include stabilization and export cadence in end-to-end time. Independent test
oracles and additional diagnostic receipt scans belong to qualification time,
not to the service's successful publication interval.

Choose acceptance criteria before the run: completion scope, maximum control
delay, allowed memory/disk pressure, recovery checks and any sustained arrival
rate. Record binary hash, configuration, corpus hash/seed, initial database state
and host/JDK/storage/network conditions. Use several comparable runs for a
distribution; one successful batch cannot establish p95/p99 latency.

## Reference resource envelope

The finite bulk rehearsal uses the following application budget on a nominal
four-vCPU / eight-GiB host. It is a starting point for deployment qualification,
not a guaranteed latency or a change to the installation defaults.

| Resource | Reference |
|---|---|
| CPU quota | Four CPU equivalents (`CPUQuota=400%`); sequential stages still need not use all four |
| JVM | `-Xms256m -Xmx2g`, with the existing OOM, UTC and encoding options retained |
| Cgroup memory | Five-GiB high / six-GiB hard limit; leave two GiB for the rest of the host |
| Cgroup swap | Disabled for the measured service |
| SQLite | `balanced` tuning on service and dataframe databases; shipped default is `low-memory` |
| Document private workspace | Eight-GiB per invocation / thirty-two-GiB root-wide disk admission ceilings |

Memory limits cover native JVM memory, SQLite and filesystem cache as well as
the Java heap. A low heap measurement does not prove that a low cgroup limit
is harmless. Record `memory.events`, swap, pressure and filesystem cache before
attributing a delay to garbage collection or CPU. Cumulative allocated bytes
are allocation churn, not simultaneously required RAM. `MemoryHigh` triggers
reclaim/throttling and can slow the run before an OOM; `CPUQuota` limits CPU time,
not dedicated physical cores. Parent cgroups and available CPUs can constrain
the effective envelope. See [systemd resource control](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html).

Disk admission ceilings are maximum allowed workspace use, not preallocated
space or complete host storage requirements. Reserve capacity for both public
databases and WALs, mutable projections, immutable slices, pinned inputs,
retained history and a coordinated backup. Larger ceilings do not permit deleting
recovery evidence or retained data to make a run succeed.

## Check independent application budgets

The following shipped limits have different owners. Check them against the
declared input before changing the heap or workspace disk ceilings:

| Budget | Shipped value / meaning |
|---|---|
| Document admission | 64 pending documents, 4 GiB total source bytes, 512 MiB per source |
| Document preparation | Two workers, configured window four; workspace admission can reduce the effective window |
| Workspace reservation | `memory-bytes=67108864` is a shared admission estimate, not a process memory limit |
| Encoded row/field | `maximum-row-bytes=262144`, `maximum-field-bytes=65536`; keep rows and individual fields bounded |
| Private document disk | 2 GiB per workspace / 8 GiB root-wide; source pin at most one quarter of the per-workspace allowance |
| Managed-import snapshot | Separate `maximum-snapshot-bytes=268435456` (256 MiB); document limits do not enlarge it |

The proposed 8/32-GiB document disk profile leaves these other limits unchanged.
In particular, increasing workspace disk does not raise the detector's 512-MiB
source bound or enlarge encoded fields. Oversized unclaimed documents remain in
the inbox, where full polling can see them again; they need investigation even
if health is UP. Claimed work follows its durable recovery state.
HTML parser-region rejection also depends on document shape, even for a small
source file; increasing workspace disk does not remove that parser bound.

Workspace reservation can reject new work despite free host disk: live pins
reserve their full allowance and inactive retained pins also count. Raising the
per-workspace allowance can therefore reduce available concurrent admissions
unless the root-wide budget is sufficient. Workspace retention prunes expired
inactive pins on the next open; it does not run a separate idle cleanup timer.
Use the [internal capacity description](../dev/native-capacity.md#current-execution-and-admission-limits)
for the admission formula and ownership limits, and the
[configuration reference](configuration.md) for complete fields/validation.

## Prepare a deployment candidate

Follow [deployment](deployment.md) for backup, validation, activation and
rollback. Make a complete candidate from the operator's current YAML; merge
these scalar overrides into its existing sections rather than replacing its
routes, schemas, lifecycle or remote endpoints with a benchmark policy:

```yaml
ioc:
  storage:
    service:
      sqlite:
        tuning: balanced
    dataframe:
      sqlite:
        tuning: balanced
  processing:
    workspace:
      workspace-bytes: 8589934592
      total-disk-bytes: 34359738368
```

Preserve credentials and the existing JVM safety options in the installed
EnvironmentFile when changing its heap bounds. The corresponding systemd
resource overrides are:

```ini
[Service]
CPUQuota=400%
MemoryHigh=5G
MemoryMax=6G
MemorySwapMax=0
```

Changing YAML alone does not change the service's systemd or JVM resource
limits. Validate the candidate with `ioc-config check` and use the controlled
deployment procedure to apply all parts together. Record effective limits
from the running cgroup after activation; requested values are not evidence
that the host applied them. Rehearse first in an isolated installation. Do not
reuse a live service's databases or inbound/outbound transport namespace for
a capacity probe.

`ioc-config check` validates the candidate's supported configuration scope; it
does not measure available RAM/disk, validate throughput or apply systemd limits.
The transient installed `ioc` CLI has its own JVM launch options, so a daemon
EnvironmentFile heap change does not qualify that CLI's memory envelope. Keep
the validation helper's bounded heap distinct from the daemon's run profile.

## Control execution and publication independently

`ioc.ingestion.concurrency` limits the detector. To require only one prepared
document in flight, declare the execution limits explicitly:

```yaml
ioc:
  ingestion:
    execution:
      preparation-workers: 1
      window: 1
```

The packaged defaults are two preparation workers and a window of four, while
canonical promotion remains ordered. Keep those values in every measurement
identity. Do not compare the two workloads as if they had equal concurrency.

For a finite burst, the existing export quiet period can coalesce immutable
slices and remote publications. A longer quiet period also adds latency after
the final document. It does not eliminate per-document canonical writes or
mutable CSV projection. Keep the maximum-cap policy bounded and inspect actual
slice/publication counts; one final publication must not be assumed from the
configuration alone. See [daemon operations](daemon-operations.md) and the
[configuration reference](configuration.md).

Native SQLite can process an entire document within an artifact transaction;
document writes commit per artifact, while managed import has a cross-artifact
transaction. A later document failure can follow earlier durable writes.
A giant document can therefore meet a batch-time goal while delaying controls
for much longer than many smaller documents. Measure maximum writer hold and
control delay separately from total throughput. Do not split a canonical
transaction, reduce durability, or change expiry semantics merely to shorten
that measurement.

## Observe the run

On the rehearsal host, these read-only commands inspect configured unit limits
and the daemon's capacity contributor (substitute the selected unit/prefix):

```bash
systemctl show ioc-extractor.service \
  --property=ControlGroup,CPUQuotaPerSecUSec,MemoryHigh,MemoryMax,MemorySwapMax
sudo /opt/ioc-extractor/bin/ioc health --component dataProcessingCapacity --json
```

The health command returns 0 only for UP, 1 for a reported non-UP state and 2 for
a request/response failure. Save a nonzero result with its response; do not drop
it from a scripted rehearsal. The installed launcher requires root and uses the
service account/environment. See [daemon operations](daemon-operations.md).

| Observation | Interpretation |
|---|---|
| `documentExecution` pending/oldest age/saturation | Backlog and admission pressure; also inspect unclaimed inbox files, which are outside the owned queue |
| `preparationPressure` leases and last checked bytes | Cached quota observations, not current RSS, a fresh disk scan or the full reserved-disk amount |
| `writerOperations`, `queuedWriters` | Per-operation cumulative/max wait and hold in nanoseconds since process start; no current-operation duration or percentile |
| `activeSnapshotReaders`, `queuedSnapshotReaders`, `walBytes` | Read ownership/queue and dataframe WAL file size; inspect both database/WAL pairs separately |
| `memory.current`, `memory.stat`, `memory.events`, `memory.pressure` | On cgroup v2, total charge/cache and reclaim/OOM/pressure; record the cgroup and before/after counters |
| `cpu.stat`, CPU pressure and I/O pressure | Throttling or contention that can explain low throughput without a heap shortage |

Read kernel counters from the unit's reported `ControlGroup` on the applicable
cgroup mount and include tighter ancestor limits. Keep collector/agent costs
identified when they share a cgroup. The
[kernel cgroup v2 reference](https://docs.kernel.org/admin-guide/cgroup-v2.html)
defines these counters; their availability depends on the host. Health UP alone
does not enforce queue, disk-pressure or latency budgets. Process restart resets
application counters, so separate observation intervals across a restart.

Long-lived readers can pin WAL history. SQLite `journal_size_limit` is not a
live WAL ceiling, and successful history deletion need not shrink the database
file immediately. Measure reusable free space and supported maintenance along
with physical DB/WAL size; never unlink a live WAL or shorten retention to force
a capacity result. Cleanup and backup procedures belong to
[storage](../dev/storage.md) and [daemon operations](daemon-operations.md).

## Close the rehearsal

Confirm complete canonical fields and keys, provenance, public IDs/slots,
terminal deliveries, receipt replay and successful remote verification. Preserve
failed runs as failed evidence. Check final DB/WAL/workspace sizes and eligible
cleanup debt separately from protected retention. A finite successful batch
does not prove sustained cleanup equilibrium without an arrival rate and a
retention workload.

The current reference exercises real SMB against a loopback stand under WSL.
It does not qualify a physical one-Gbit/s LAN, dedicated four-core hardware,
all document formats, an arbitrary populated database or an unmeasured operator
policy. Production acceptance still requires the intended deployment profile
and current release-quality checks, which are separate from load acceptance.
Stop architecture redesign once the
declared scenario passes its agreed end-to-end and operational requirements;
record remaining stack costs and unavailable evidence explicitly.

The historical results are collected in [Native capacity](../dev/native-capacity.md).
`OUT-4` resource acceptance and `TOOL-10` lifecycle harness qualification remain
open in [known issues](../KNOWN-ISSUES.md). In particular, the current lifecycle
smoke/load waits can time out on the archived `.html.sealed` suffix before later
assertions run. Do not report those assertions as passed or reinterpret the
timeout as a measured capacity limit.

## Sources and maintenance

Check [production YAML](../../packaging/templates/application.yml),
[daemon unit](../../packaging/templates/ioc-extractor.service),
[JVM environment template](../../packaging/templates/ioc-extractor.env) and
[CLI launcher](../../packaging/templates/ioc) when changing the profile.
Update both languages when defaults, admission, telemetry or validation/deployment
procedures change. New capacity claims require identified workload/target evidence;
documentation examples and deterministic tests do not qualify a new load profile.
