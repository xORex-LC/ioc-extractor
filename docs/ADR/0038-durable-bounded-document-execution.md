# ADR-0038: Durable bounded document execution and writer admission

Status: Accepted
Date: 2026-10-06

## Context

Executing hash, parsing, routing, canonical writes and projection on the file
poller delays intake behind the slowest document. Dispatching complete IOC graphs
to an unbounded executor would amplify memory pressure. Parallel canonical writes
could also change whole-row KEEP_FIRST and registered-field precedence.

## Decision

Every daemon document uses the existing durable admission protocol, irrespective
of its artifact field policy. The single detector reserves an observation, assigns
its dataframe order and atomically claims its token. It returns before snapshot
copying, hashing or parsing. The service journal is the recoverable queue; executor
queues contain bounded job references. A periodic scan discovers lost hints.

Up to two workers seal private source snapshots and prepare the bounded workspace
defined by ADR-0036. Only the oldest unresolved document admission may promote.
The content-key guard is acquired at promotion, after preparation, so a newer
same-content document cannot block the older document while awaiting its turn.
Canonical writes, receipt authority, projection recovery, archive and terminal CAS
retain their existing transaction boundaries. A read-only receipt lookup may skip
preparation; promotion revalidates it and falls back to extraction if it expired.

Admission limits cover pending count and source bytes. The preparation window,
worker count and shared workspace memory/cache/disk quotas separately bound
executing work. Saturation leaves an unclaimed input discoverable. Claimed work
keeps its observation, order and private source across retry and restart. Attempts
and backoff are durable in service schema v14. Exhausted failure with a verified
content key uses the existing rejection lifecycle. Exhausted pre-hash failure
stays durably blocked with its owned token and makes capacity health DOWN; it
never fabricates a content identity from path/mtime. This does not close ING-13
or introduce an operator requeue command.

One shared dataframe writer admission selects CONTROL, EXPIRY, EXPORT_SLOTS,
PROMOTION and MAINTENANCE work between atomic units. Fresh requests prefer that
order; requests waiting at least 100 ms precede fresh requests and retain FIFO
order. Selection is non-preemptive. Nested work belongs to its outer writer unit.
ID reservation is a short CONTROL operation; snapshot streaming, hashing and
preparation remain outside writer ownership. Service-journal writes belong to
their independent service database.

Health reports bounded phase/count/age/source-byte counters, completed rows/bytes,
writer wait/hold totals and maxima by class, active/queued snapshot readers, WAL
bytes and cached workspace pressure. Existing export/publish health views retain
durable publication lag. Telemetry does not write or log per IOC.

## Consequences

Preparation can proceed while an older document promotes. Ordered promotion
intentionally blocks behind the oldest retry or pre-hash blocked job; allowing
overtaking would silently change KEEP_FIRST. Shutdown stops intake, joins workers
within a bounded grace period and closes unpromoted handles while preserving
durable tokens. Startup recovery remains a barrier before either intake opens.

Writer priorities cannot make a large atomic transaction interruptible. If its
maximum occupancy exceeds the accepted five-second target, resource acceptance
remains open and CAP-7C must resolve visibility/atomicity explicitly. No existing
transaction is silently chunked to make the measurement pass.

Stop the previous binary and back up both databases and owned files before
upgrade. Existing document admissions migrate with zero execution attempts and
no backoff. Old binaries cannot open service schema v14. Rollback requires the
coordinated backup; changing `user_version` is unsupported.

## Validation

Controlled tests cover out-of-order preparation with ordered promotion, hint
flood/loss, count and byte saturation, retry identity, pre-hash blocking, worker
shutdown/restart, receipt revalidation, terminal finalization and journal reopen.
JDBC tests verify non-starving writer selection, reader permits and WAL pinning
under a delayed snapshot. Whole-service mixed-load and live SMB qualification
remain CAP-6; these mechanism tests do not replace them.
