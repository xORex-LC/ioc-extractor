---
title: "DATA-TTL-01 P6 lifecycle load profile"
version: "0.3.0"
status: "Measured — export-slot correction pending"
document_type: "Performance evidence"
source_of_truth: false
language: "en"
---

# DATA-TTL-01 P6 lifecycle load profile

> **2026-08-19 interpretation note.** Measurements in this document remain
> valid for lifecycle expiry, retention, heap and query plans. The public-ID
> non-reuse assertion characterizes the P6 implementation; it is not acceptance
> evidence for the later I-22 requirement. P7 needs a separate 100k export-slot
> profile covering survivor stability and smallest-hole reuse.

## Evidence boundary

This profile exercises the bootable fat JAR through the normal daemon ingestion
boundary. It does not insert fixture rows directly into SQLite. The same
workspace is restarted for history retention and then receives new input to
characterize the then-current monotonic public-ID behavior.

The run is rootless application evidence. It does not replace the required
fresh-install, two-step upgrade and rollback exercise under the packaged
systemd unit on a disposable host.

| Field | Value |
|---|---|
| Source commit | `b5bdd1a10802b9f5b7158d2e39ed9d34c2d98537` |
| Worktree at run start | clean |
| JAR SHA-256 | `84c5a76957c8cf5ee1d0f615fe5155162718d8c41631ab085430e9a7e8fbc299` |
| Host | Linux `6.6.87.2-microsoft-standard-WSL2`, x86_64 |
| Runtime | OpenJDK `21.0.11` |
| SQLite | `3.45.1` |
| CPU / memory | 12 logical CPUs / 16229584 KiB |
| JVM profile | `-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError` |

Reproduce from a clean worktree with:

```bash
make lifecycle-load
```

The ignored raw report is written to
`.dev/lifecycle-load-66667/lifecycle-report.md`.

## Scenario and result

The deterministic 66667-row input produces 100001 canonical artifact rows
because network indicators also populate `address_blacklist`.

| Metric | Measured | Guardrail | Result |
|---|---:|---:|---|
| Canonical active rows | 100001 | at least 100000 | pass |
| Deadline spread | 5306 ms | at most 30000 ms | pass |
| Expiry start after earliest deadline | 1001 ms | at most 5000 ms | pass |
| Drain after latest deadline | 4799 ms | measured | pass |
| Archive/drain throughput | 10984.29 rows/s | at least 2500 rows/s | pass |
| History-retention restart-to-drain | 40602 ms | at most 180000 ms | pass |
| Maximum observed JVM high-water | 571144 KiB | at most 1048576 KiB | pass |
| Reconcile cycles containing expired rows | 4 | eventual drain | pass |
| Minimum bounded expiry transactions | 103 | bounded batches | pass |

The 2500 rows/s regression floor is deliberately conservative: it is about
23% of this qualifying run and below the slower diagnostic calibration observed
while the harness was being aligned with the packaged JVM. It catches a major
algorithmic or query-plan regression without presenting one WSL2 run as a
production capacity promise. The 180-second retention limit is likewise a
release regression guard, not a retention SLA.

## Correctness assertions

- all active rows moved to typed `EXPIRED` history with source summaries;
- mutable projections converged to header-only files;
- expiry left each insert-driven `artifact_revision` unchanged;
- expiry created no immutable export slice, while later accepted new rows
  created the next slice with exact active membership;
- retention removed history and source summaries while preserving allocators;
- later accepted input allocated public IDs above every former ID under the P6
  implementation; P7 deliberately replaces this export behavior;
- lifecycle health returned `UP` after admission, expiry, retention and
  reappearance;
- the active and history sets were exact after every phase.

## Query plans

Every artifact used its lifecycle deadline and history retention index:

```text
masks              ix_masks_lifecycle_due / ix_masks_history_retention
ip_list            ix_ip_list_lifecycle_due / ix_ip_list_history_retention
address_blacklist  ix_address_blacklist_lifecycle_due / ix_address_blacklist_history_retention
hashes             ix_hashes_lifecycle_due / ix_hashes_history_retention
```

SQLite reported `SEARCH ... USING COVERING INDEX` for all eight paths. The
harness fails if a required index is absent from `EXPLAIN QUERY PLAN`.

## Calibration disposition

An earlier 150001-canonical-row diagnostic run without the packaged heap flags
reached 1070144 KiB and correctly failed the 1 GiB harness guard. That exposed a
test-boundary mismatch rather than justifying a looser limit. The harness now
passes the production launcher heap flags to the rootless runtime and defines
the workload by canonical-row count, not raw input count.
