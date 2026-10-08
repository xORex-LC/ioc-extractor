# Daemon document ingestion

This capability takes stabilized files from a local inbox to canonical SQLite,
recoverable CSV projection and source disposition. Spring Integration is a driving
adapter. Extraction, routing, lifecycle rules and storage authority remain behind
application ports. See [processing.md](processing.md), [storage.md](storage.md)
and [ADR-0038](../ADR/0038-durable-bounded-document-execution.md).

## Execution flow

```text
periodic full listing -> include/exclude -> quiet-period stability
  -> durable observation reservation -> dataframe admission order
  -> atomic private token claim -> detector returns

bounded preparation workers:
  durable token -> private sealed source -> SHA-256 -> journal link
    -> read-only receipt lookup or extraction/routing -> sealed workspace

one promotion worker, oldest unresolved document first:
  prepared reference -> content-key guard -> receipt revalidation
    -> failure-policy checkpoint -> canonical commit -> projection
    -> source archive -> terminal CAS -> post-commit change hint
```

The detector remains single-threaded (`ioc.ingestion.concurrency=1`). It performs
metadata checks, short durable admission and atomic ownership only. Hashing,
source copying and extraction do not run on the poller. The dispatcher queues
job/workspace references, rather than documents or IOC collections. Every daemon
document uses admission, including artifacts with only KEEP_FIRST policies.

Preparation uses the repeatable bounded workspace described in
[processing.md](processing.md). Up to two workers may prepare independently. The
workspace owns decoded/refanged text, indexed extraction claims and ordered
occurrence cursors, as well as the reducer. HTML prunes completed subtrees and
DOCX uses SAX; parser-region and field limits reject preparation before promotion.
The admitted dispatch window is the smaller of the configured window and the
factory's global workspace capacity, preventing later ready jobs from consuming
every lease ahead of the oldest job. These bounds still require a separate
whole-process memory measurement. Only
the oldest unresolved admission may promote; a newer ready document cannot
change whole-row KEEP_FIRST by finishing preparation first. Registered mutable
field precedence still uses the durable dataframe rank. The source-key guard is
acquired at promotion, so a newer duplicate cannot hold it while waiting for an
older document. Preparation and promotion remain separate application operations.

## Admission and pressure

`ioc.ingestion.execution` configures preparation workers, window, pending count,
total pending source bytes and per-source bytes. Defaults are 2 workers, window 4,
64 pending documents, 4 GiB total source bytes and 512 MiB per source. Worker
count cannot exceed two or the window; the window cannot exceed the pending
count, which cannot exceed 256. Per-source bytes cannot exceed the workspace
source-pin allowance (one quarter of the workspace disk quota).

These are separate budgets: admission bounds owned input; the preparation window
bounds handles; workspace admission bounds shared cache, parser working
reservation, memory and disk. The streamed source protocol is defined in
[ADR 0039](../ADR/0039-streamed-document-source-processing.md).
Oversized or saturated unclaimed input remains in the listed inbox. Full polling
rediscovers it without an AcceptOnce filter. Claimed input never disappears because
an executor rejects a hint: the journal remains the recoverable queue. A coalesced
hint accelerates dispatch; a one-second journal scan is its correctness backstop.

WatchService remains an opt-in latency path for reliable local filesystems.
Quiet-period rejects enter its retry set, but OS events do not replace full
listing on network or unreliable filesystems. Producer `*.part` plus atomic rename
remains the preferred input contract.

## Identity and ownership

Each delivered occurrence gets a new UUID `ObservationId`. Retry and recovery keep
that identity and admission rank. Content identity is SHA-256 of the sealed source;
path, size and mtime are metadata, never a substitute content key.

The admission journal follows `RESERVED -> ORDERED -> CLAIMED -> LINKED -> TERMINAL`.
Dataframe storage owns rank allocation; the service DB or fsync-backed file journal
owns the source/job recovery reference. Atomic token claim is no-replace and has no
copy/non-atomic fallback. After claim, `sealClaim` publishes a private inode using
fsync and atomic move; an open producer descriptor cannot modify the source read
by hashing and processing. Stable hash evidence is checked before and after read.
Growth after bounded admission is rejected before processing.

The ingest ledger follows `ABSENT -> CLAIMED -> SOURCE_ARCHIVED|FAILED`. Terminal
CAS is monotonic: same outcome is idempotent; opposite outcome conflicts. The run
saga follows `STARTED -> DB_COMMITTED -> PROJECTION_COMPLETED -> COMPLETED`, with
precommit failure marked FAILED. Canonical truth is SQLite; projection failure is
recovered forward. Service-journal and dataframe transactions are independent;
the terminal registration handshake is retried from durable state.

## Receipts and retry

With active fixed lifecycle, a read-only complete receipt lookup can avoid
preparation for `(source_key, processing-policy fingerprint)`. It does not renew
canonical rows. Promotion revalidates the receipt using effective time and replays
its typed rows through the canonical writer. If it expired or was purged, the
same ordered job falls back to extraction. Default receipt retention is 30 days.
A parsing/mapping semantics change outside configuration must advance the explicit
code-policy epoch in the fingerprint.

Execution attempts and retry-after are durable in service schema v14. A transient
failure retains the private source and original observation/rank. Retries use
bounded configured backoff without sleeping on the poller. Once a verified
content key exists, exhausted failure uses the existing source rejection/dead-letter
lifecycle. Each failed attempt emits a typed diagnostic with its cause; terminal
logging is observation only and cannot retry a successful canonical operation.

An exhausted pre-hash failure has no trustworthy content key. Its admission stays
blocked with the owned token (`retry_after_ms=Long.MAX_VALUE`), blocks later document
promotion and makes capacity health DOWN. There is no automatic requeue/clear API;
the operator must investigate the token/permissions and perform a supported
recovery or coordinated restore. Startup cannot silently skip a broken token or
invent an identity. Pre-reservation failure may leave the input in the inbox;
ING-13 remains open. The partial-run resume limitation ING-11 also remains open.
See [KNOWN-ISSUES.md](../KNOWN-ISSUES.md).

## Startup, recovery and shutdown

`CanonicalIntakeStartupCoordinator` is the highest-precedence ApplicationRunner.
It keeps intake closed while recovering run saga, document admissions, ordinary
source work, lifecycle admission and managed imports. Only then do document
execution and ordinary intake start. A failed barrier stops both intake paths,
fails readiness and reports the original diagnostic once. Admission recovery
preserves the existing rank; unresolved legacy work without rank under ordered
field policy requires drain/restore and never receives an invented new rank.

The dispatcher closes admission, joins the coordinator/preparation/promotion
workers within a bounded grace period, then closes unpromoted handles. Cancelled
preparation closes its run/workspace and preserves the claimed delivery. A worker
that fails to terminate causes an explicit shutdown failure; its live handle is
not discarded underneath it. Periodic journal discovery resumes pending work on
restart. This is at-least-once orchestration with idempotent durable steps.

A fatal worker `Error` stops intake and promotion scheduling, releases ready
handles and interrupts other workers. An already active promotion retains its
own handle until its cleanup. Every independently owned release is attempted,
including when another release throws `Error`; primary and suppressed failures
remain visible. Durable admissions are retained for startup recovery. The failed
dispatcher object cannot restart itself, and `dataProcessingCapacity` is DOWN
while dispatch is stopped, even when no admission is marked blocked. This is a
fail-stop ownership contract, not a guarantee that a depleted JVM can recover
without process restart.

One daemon process per inbox/database namespace is supported. Multiple processes
would need a separate lease/fencing contract. Managed CSV import uses its own
intake, delivery ledger, workspace and atomic promotion; document queue order does
not replace the import ordering contract.

## Operational evidence

`ingestionLifecycle` is UP only after recovery and running intake. The daemon's
`dataProcessingCapacity` health view adds metadata-only document phase counts,
pending source bytes and oldest age, completed documents/rows/bytes, saturation and
blocked counts. It also reports writer wait/hold totals and maxima by operation
class, active/queued snapshot readers, WAL bytes and cached workspace pressure.
Health reads do not advance admissions or enumerate IOC rows. Failure summaries
expose the exception type; detailed causes belong to diagnostics.

The existing export/publish health views report durable publication lag. Writer
admission is non-preemptive: priority and aging apply between transactions and
cannot bound an already-running atomic promotion. Long snapshot readers can pin
WAL checkpoint progress even while canonical writes continue.

Terminal `source_ingest` logs preserve run id, completion and diagnostic severity
counts; COMPLETED_WITH_ERRORS has failure outcome. Receipt replay carries
`ioc.ingest.disposition=duplicate` without fabricated extraction completion.
Startup recovery logs one start and one terminal outcome with duration and counts.
Events remain latency hints; durable ledgers and periodic reconcile own correctness.

## Extension and maintenance

New detection transports still need stable candidates, durable occurrence
identity and private ownership. Streaming/tailing requires its own checkpoint
identity, including offset/rotation. New storage implementations implement inward
ports; Spring/JDBC remain outside core. Parallel promotion or transaction chunking
requires an explicit visibility/ordering/recovery decision, rather than executor
configuration alone.

Update this document when claim/identity, phase order, admission limits, retry,
shutdown, startup recovery or terminal semantics change. Module references live in
[adapter-ingest](../../adapters/adapter-ingest/README.md); related capabilities are
[artifact export](artifact-export.md), [event coordination](event-coordination.md)
and [managed import](dataframe-import.md).
