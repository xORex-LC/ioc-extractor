---
title: "DATA-IMPORT-01 P5 evidence"
version: "0.3.0"
status: "Implemented with focused compatibility verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P5 evidence

## 1. Evidence boundary

P5 implements local managed ownership, immutable snapshot admission and the
loss-tolerant detection path. It does not open a daemon intake worker: P7 owns
the shared recovery-before-intake startup barrier, global drain and terminal
finalization. P6 is the first slice allowed to make canonical import writes.

## 2. Ownership and snapshot evidence

- `StrictAtomicFileOwnership` is shared with ordinary ingest. It accepts only a
  regular non-symlink source, refuses an existing protected target and has no
  copy or non-atomic fallback.
- Local source roots and processing/snapshot/staging/terminal/quarantine roots
  are typed configuration and must be pairwise disjoint. Adapter-created
  protected directories are owner-only on POSIX filesystems.
- Detection performs a sorted complete direct-child listing and requires stable
  size, modification time and file identity for the configured quiet period.
  Candidate tokens are opaque adapter values; claim decodes only a single
  direct-child name and revalidates all observed metadata.
- Claim moves the source into a delivery-derived private namespace, streams it
  to a `.part` file while enforcing the byte limit and computing SHA-256, checks
  source metadata again to catch an open producer handle, forces bytes, then
  atomically publishes a read-only snapshot.
- Snapshot references resolve only under the private snapshot root. Parser and
  staging ports never receive the inbox or mutable processing path.

## 3. Ordering, events and retry evidence

- `DataframeImportAdmissionService` reserves a global sequence in the service
  ledger before claim and advances through `CLAIMING`, `CLAIMED` and
  `SNAPSHOT_PINNED` using state/version CAS.
- A repeated full scan returns the existing active occurrence for the same
  `(source, candidate)` rather than failing or creating parallel work. After a
  terminal occurrence, the same candidate token may represent a new delivery.
- Claim failures store `next_attempt_at` and a safe code. No managed detector
  thread sleeps.
- `ImportDeliverySnapshotPinned` is published only after exact snapshot
  evidence is durable and contains no path, filename, digest or IOC values.
  Publish failure is safe because ordered ledger recovery is authoritative.
- Poll and WatchService triggers use the same source-keyed complete-listing
  coordinator. Watch event filenames are discarded; rejected, duplicated and
  lost hints affect latency only.
- Ordinary ingest now uses the same strict ownership primitive. Positive retry
  delay is scheduled on a daemon executor rather than blocking the Spring
  Integration poller with `Thread.sleep`.

## 4. Executable evidence

| Check | Result |
|---|---|
| local adversarial corpus | symlink, path escape, collision, producer mutation, unsupported atomic move and disk-full cases fail closed |
| snapshot integrity | exact bytes, size, SHA-256, private locator and read-only publication verified |
| ledger/admission | active-candidate duplicate is idempotent; sequence/head CAS and snapshot-before-event boundary pass |
| ordinary ingest compatibility | strict lifecycle tests and synchronous/async retry terminal behavior pass |
| affected-module verification | `ioc-adapter-ingest` plus upstream reactor: `BUILD SUCCESS`, `0` visible SpotBugs findings |

The broader affected-reactor run reached `ioc-app` with all earlier modules
green and initially failed only because the new typed properties were not yet
listed in both operator guides. Both guides are updated in this slice. The final
full-reactor gate is intentionally run after P6 so it covers the requested
P5/P6 span on one committed tree.

## 5. Next boundary

P6 may consume only a ledger-pinned snapshot and a verified sealed stage. It
must attach staging read-only, apply one dataframe transaction with an
`import_commit` receipt and publish all convergence events strictly after that
transaction commits.
