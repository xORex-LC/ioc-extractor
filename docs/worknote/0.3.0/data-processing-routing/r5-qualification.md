# R5 synthetic qualification and handover

Status: synthetic Router qualification implemented and run on 2026-09-27.
R5 is not complete: document/import fixtures and a valid before/after comparison
depend on IOC P3/P4 (and activation on P5). No production IOC plan is registered.

## Reproducible profile

`RouterQualificationTest` checks 1,000 inputs for every combination of 1/4/16
selected branches, 1/4 concurrent callers and SUCCESS/FAILURE/RECOVERY. Each
call checks prepared candidates, blocked consumers and recovery evidence. The
opt-in `make router-qualification SIZE=100000` runs the same 18 combinations
in separate JVMs after 2,000 warmup inputs per combination. The script records
CSV, JDK and commit metadata under `.dev/router-qualification`; no IOC values,
canonical DB or service runtime are involved.

Measurement includes plan compilation, embedded Camel startup, caller-thread
allocated bytes, used-heap samples after requested GC, and elapsed routing plus
result validation. Each run uses `-Xms128m -Xmx512m`, bounded worker futures
and a 180-second process timeout. Thread allocation is reported as `-1` if the
VM does not support it. Heap samples are advisory: `System.gc()` and the lack of
RSS/native-memory measurement make them unsuitable as an isolated production
memory guarantee.

Observed profile: OpenJDK 21.0.12.1, 12 reported processors, HEAD
`f82023247a8cc6fde33dca0b4bec428072b4e03a` with R5 worktree changes.
All 18 100k profiles completed; retained-growth samples were about 0.2 MiB.

| Selected branches | Callers | Throughput range, inputs/s | Allocation range, bytes/input | Camel startup range, ms |
|---|---:|---:|---:|---:|
| 1 | 1 | 99,360–115,414 | 10,377–13,344 | 238–251 |
| 1 | 4 | 140,556–166,484 | 10,578–13,666 | 241–249 |
| 4 | 1 | 65,070–76,459 | 18,639–24,089 | 237–257 |
| 4 | 4 | 108,397–119,716 | 18,701–24,354 | 245–274 |
| 16 | 1 | 27,838–35,727 | 52,839–68,397 | 257–281 |
| 16 | 4 | 48,607–64,422 | 52,458–68,872 | 255–281 |

Ranges span success, one-in-four expected failure and one-in-four recovered
failure. Failure runs dispatch fewer branches and are not directly comparable
to success runs. These are one-run observations, not confidence intervals or a
hardware-neutral SLO. The sample retained heap after startup was 2.8–2.9 MiB;
it excludes JVM baseline and native Camel resources. A resource acceptance
budget for the actual service remains open until same-fixture legacy versus
Router measurements include parsing, mapping, diagnostics, storage and import
workspace costs. Do not use these Router-only throughput figures to approve
production activation.

## Remaining R5/P6 handover checks

1. After IOC P3, run document golden fixtures through the old and admitted
   Router paths with identical input, policy and output, then remove replaced
   inner dispatch in the migrated scope.
2. After IOC P4, run processed-import fixtures including ABSENT/NULL/VALUE,
   recovered warnings, authority and receipt recovery. Keep AS_IS import on
   its existing path and prove no duplicate runtime on processed rows.
3. After P5, test pinned policy identity across restart and in-flight delivery.
   Compare end-to-end latency, allocations, heap/RSS and throughput against the
   previous preparation path on the same host/JDK/configuration; review a
   resource budget from those measurements before production activation.
4. Re-run full quality gates and promote durable operation/activation guidance
   to published docs in IOC P6. The synthetic profile alone does not close R5.
