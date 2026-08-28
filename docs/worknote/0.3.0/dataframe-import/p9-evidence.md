---
title: "DATA-IMPORT-01 P9 evidence"
version: "0.3.0"
status: "Implementation and stand qualification in progress"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P9 evidence

## 1. Evidence boundary

P9 qualifies the implemented managed-import path for bounded load, writer
fairness, packaged operation and an external Samba round trip. This record
separates completed evidence from the remaining release gates; an older live
upgrade is not treated as a substitute for the exact `v0.2.0` package scenario.

## 2. Reference load

Both opt-in profiles ran on Debian 12, ext4 and Temurin 21.0.11 with a forked
512 MiB heap. The harness enforced a 384 MiB observed-heap ceiling and internal
time limits of 120 seconds for 100k and 600 seconds for 1M.

| Profile | Rows | Accepted / rejected | Public mutations | Stage / promotion / total | Peak heap | Max RSS |
|---|---:|---:|---:|---:|---:|---:|
| insert | 100,000 | 100,000 / 0 | 100,000 | 5.489 / 16.183 / 21.672 s | 163.9 MiB | 340.0 MiB |
| mixed | 1,000,000 | 750,000 / 250,000 | 500,000 | 55.906 / 163.546 / 219.453 s | 157.9 MiB | 361.5 MiB |

The 100k process wall time was 27.58 seconds. The 1M process wall time,
including the 750k-row seed outside the measured import interval, was 253.58
seconds. The stage files were approximately 91 MiB and 922 MiB; the resulting
dataframe databases were approximately 165 MiB and 1.41 GiB.

The captured plans used the staging match covering index, canonical row-key
unique index, canonical alias lookup index and import-receipt unique index. No
plan exposed a scan proportional to the highest requested sparse slot. Peak
heap stayed effectively flat when the delivery grew by 10x.

The 1M promotion transaction held the serialized writer boundary for 163.546
seconds. It passes the approved bulk SLO but remains an operational reason to
schedule maximum-size imports deliberately; fairness orders later writers but
does not make one SQLite transaction preemptible.

## 3. Contention and local smoke

`JdbcWriterAdmissionTest` passed both focused scenarios on the candidate host:

- no two local dataframe writers entered concurrently;
- queued import, ordinary ingest, lifecycle and export-slot writers entered in
  observed FIFO order through the shared fair interruptible admission gate.

The daemon composition test also retains distinct managed-import and sync
executors, preventing one single-thread scheduler from running another
subsystem's blocking work. `make dataframe-import-smoke` then passed local
claim, immutable snapshot, staging, canonical commit, terminal source/report
publication and mutable projection convergence.

The final full reactor, build-quality reports and documentation gates passed on
the committed evidence state, and `make context` reported fresh verification.

## 4. Packaging and rollback

Commit `646fb714ebdfcbf4f8e41fd1502e8848d82b6374` was deployed through
`packaging/deploy-local.sh`; its mandatory clean Maven verification and local
health gate passed. The live upgrade migrated dataframe schema v3 and service
schema v6 to v9/v9 while preserving 596 canonical rows.

A controlled activation of the preserved older binary failed on the new
configuration preflight as intended. The first rollback probe exposed that the
deployment transaction restored the application symlink and databases but not
the previous systemd unit. After unit backup/restore was added, a second probe
restored the release, both SQLite databases and the unit, and health returned
`UP`. That probe exposed one further fidelity gap: host-added POSIX ACLs were
lost on database extraction. The final implementation archives/restores POSIX
ACLs and extended attributes; the repeated live probe restored:

- the previous immutable release symlink;
- the byte-identical version-matched systemd unit;
- dataframe and service schema v9 databases with `quick_check=ok`;
- all 596 rows; and
- the named operator ACL on the database directory and files.

Packaging, ShellCheck, shell syntax/contracts and documentation links passed;
the documentation check covered 785 links with zero errors.

The deployed predecessor was a pre-`v0.2.0` snapshot using the later marked
release layout. This is useful stricter compatibility and rollback evidence,
but it does not close the exact `v0.2.0` upgrade/rollback gate. A packaged fresh
installation and exact tagged `v0.2.0` upgrade/rollback remain outstanding.

## 5. Lifecycle activation and external Samba round trip

The existing database used the documented two-phase cutover. The compatibility
deployment first reached health with lifecycle disabled. A local-only
activation candidate then selected fixed 10-minute validity and explicit
legacy expiry while new managed import and synchronization workers remained
disabled. Admission reached durable `ACTIVE`, archived all 596 legacy rows,
left zero active rows and converged all mutable projections. The final restart
enabled the managed local/SMB sources, notification acceleration, periodic
listing, export and publish.

An approved external Samba delivery used a 185-byte, one-row CSV that first
passed advisory preview (`accepted=1`, `rejected=0`). Durable timing was:

| Milestone | Time from managed-import detection |
|---|---:|
| canonical commit | 160 ms |
| terminal success | 207 ms |
| export start | 1.229 s |
| export complete | 1.250 s |
| verified remote publish | 1.276 s |

The export itself took 21 ms. Publish succeeded on its first attempt and
verified the committed marker plus six files. The configured stability window
adds approximately two seconds before durable detection, so the expected
operator-visible round trip is approximately 3.3 seconds plus producer upload
time. Health remained `UP`, the import queue returned to zero, and the accepted
row received one active lifecycle with the configured ten-minute deadline.

At that deadline the bounded reconciliation backstop removed the row from the
active set within one second, archived exactly one additional `ip_list`
lifecycle and left 597 history rows in total. The mutable `ip_list` projection
converged at required/projected generation `3/3`; due records, pending
projections and reconciliation backlog all returned to zero. No immutable
export run or publish-ledger entry was created by expiry, as required by the
lifecycle contract.

A second external delivery exercised the ordinary document path with the
versioned golden HTML corpus. The remote object's timestamp does not establish
when its producer finished uploading, so the end-to-end application interval
is measured from the durable fetch-ledger timestamp:

| Milestone | Time from completed SMB fetch |
|---|---:|
| ordinary ingest start | 1.182 s |
| last canonical artifact commit | 1.395 s |
| ordinary ingest complete | 1.455 s |
| target export start | 2.531 s |
| target export complete | 2.553 s |
| verified remote publish | 2.576 s |

The extraction run itself completed in 273 ms. Its four artifact commits
inserted the exact golden cardinalities: eight masks, one IP-list row, nine
address-blacklist rows and two hashes, with no renewals or lifecycle restarts.
All mutable projection generations converged, the three configured immutable
profiles completed, and the delivery target verified its committed marker plus
six files on the first publish attempt. Overall health remained `UP` with no
pending publish, import or projection work.

Endpoint addresses, share paths, credentials, filenames, IOC material and
digests are intentionally omitted from this versioned evidence.

The round trip above predates ADR-0026 and did not record the effective SMB
encryption policy, negotiated dialect or encryption gate. It remains valid
functional Samba evidence, but it is not evidence for the later
`encryption: required` guarantee.

## 6. Remaining P9 release gates

- Run packaged fresh-install and exact `v0.2.0` upgrade/rollback qualification
  on a disposable systemd host.
- Qualify any additional production Windows Server/NAS family before claiming
  support beyond the approved Samba implementation; polling remains the
  correctness fallback when notifications are unavailable.
- Re-run the approved Samba contract with `encryption: required` and retain the
  explicit `SmbEncryptionContractTest` result. Until then, effective encrypted
  transport qualification is an external skip, not a pass.
