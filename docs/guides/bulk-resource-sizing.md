# Bulk resource sizing

Use an explicit resource profile when rehearsing a million-input batch. The
packaged installation defaults target a smaller service: two CPUs, a 512-MiB
maximum Java heap, a 768-MiB cgroup soft limit and a 1-GiB hard limit. They are
not a qualified million-input capacity promise. The production storage backend
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

## Reference resource envelope

The finite bulk rehearsal uses the following application budget on a nominal
four-vCPU / eight-GiB host. It is a starting point for deployment qualification,
not a guaranteed latency or a change to the installation defaults.

| Resource | Reference |
|---|---|
| CPU quota | Four CPUs; sequential stages still need not use all four |
| JVM | `-Xms256m -Xmx2g`, with the existing OOM, UTC and encoding options retained |
| Cgroup memory | Five-GiB high / six-GiB hard limit; leave two GiB for the rest of the host |
| Cgroup swap | Disabled for the measured service |
| SQLite | Existing `balanced` tuning on service and dataframe databases |
| Document private workspace | Eight-GiB per invocation / thirty-two-GiB root-wide disk admission ceilings |

Memory limits cover native JVM memory, SQLite and filesystem cache as well as
the Java heap. A low heap measurement does not prove that a low cgroup limit
is harmless. Record `memory.events`, swap, pressure and filesystem cache before
attributing a delay to garbage collection or CPU. Cumulative allocated bytes
are allocation churn, not simultaneously required RAM.

Disk admission ceilings are maximum allowed workspace use, not preallocated
space or complete host storage requirements. Reserve capacity for both public
databases and WALs, mutable projections, immutable slices, pinned inputs,
retained history and a coordinated backup. Larger ceilings do not permit deleting
recovery evidence or retained data to make a run succeed.

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

Native SQLite can process an entire document within an artifact transaction.
A giant document can therefore meet a batch-time goal while delaying controls
for much longer than many smaller documents. Measure maximum writer hold and
control delay separately from total throughput. Do not split a canonical
transaction, reduce durability, or change expiry semantics merely to shorten
that measurement.

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
