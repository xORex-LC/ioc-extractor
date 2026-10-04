# ADR 0035: Generation-owned mutable artifact projections

Status: Accepted, 2026-10-04. Refines mutable projection ownership and
acknowledgement from [ADR 0020](0020-canonical-record-expiration-lifecycle.md).

## Context

Direct ingest and run recovery installed CSV files independently of lifecycle
convergence. Atomic replacement protected complete bytes but allowed an older
snapshot to overwrite a newer acknowledged file. Required-generation CAS after
replacement could not prevent this installation ordering. The stream reader
also returned no generation evidence; convergence acknowledged a pre-build
work-state observation rather than the snapshot actually installed.

## Decision

One application service owns each configured mutable artifact within the
process. All oneshot, ingest, recovery, import-triggered convergence, activation
and expiry requests use it. Bootstrap exposes only the owner as the projection
port; the raw CSV installer is private to its construction.

A fair interruptible per-artifact lock spans snapshot reading, temporary file
construction, atomic installation and acknowledgement. Canonical writer admission
is held only by existing short database operations, including acknowledgement;
it never spans CSV construction. Lock order is artifact owner, then database
admission. No canonical writer calls back into projection while holding admission.

The stream reader returns row count and covered generation from one SQLite read
transaction. Acknowledgement advances projected coverage monotonically up to
that installed generation, provided it does not exceed required work. A newer
canonical mutation during the build remains pending. This supersedes the exact
required-generation CAS restriction for mutable acknowledgement; it does not
alter canonical transactions, receipts or immutable slice coverage.

The owner retains one successful outcome per artifact. Waiting callers can use
it when it covers the latest durable generation; advisory diagnostics keep their
counts and receive each caller's correlation ID. Generation zero is untracked
(lifecycle disabled) and always rebuilds under the same serialization boundary.

Failure before replacement preserves the old target. Failure after replacement
but before acknowledgement leaves durable work pending. Startup/periodic
convergence retries from canonical truth without requiring a new mutation.
The owner creates no workers; callers own execution, interruptible waiting and
cancellation. CSV streaming checks interruption before installation and per row.

## Consequences

- Different artifacts may progress independently. Within one artifact only one
  build/install/ack attempt runs at a time; parallel speculative builds are
  deferred until measured benefit justifies a more complex installation fence.
- Mutable output paths and dataframe storage require a single service process.
  This is not a distributed filesystem lock or a transactional filesystem/DB
  commit. Process crashes between rename and ack are repaired by pending work.
- External deletion/corruption of acknowledged output remains an operational
  reprojection concern; durable coverage alone is not a file integrity check.
- Immutable slices retain their separate slot, manifest and recovery owners.
- The stale-install race, partial coverage, cancellation, fault windows and
  restart recovery require deterministic regression tests.
