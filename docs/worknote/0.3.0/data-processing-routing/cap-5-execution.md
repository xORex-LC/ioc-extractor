# CAP-5 execution evidence

Status: implementation in progress. Scope: CAP-5A and CAP-5B only. No stand deployment.
Baseline: `0b56321c1571811369fba0114db067571cd34398` (CAP-4).

## Export isolation findings

The baseline has three independent global barriers: the scheduler's single executor,
the NIO formation lease, and the service ledger active-run singleton index. A real
Spring/SQLite/CSV blocking test additionally found `CsvArtifactSliceWriter.stage`
holding the shared object monitor for the entire reader/CSV operation. The first
new isolation test timed out on the second profile before releasing the first.
Removing that invocation-wide monitor under profile leases made the same test pass.
This is an observed failure and correction, not a claimed throughput ratio.

The new scheduler has two workers, one outstanding attempt per configured profile,
and a bounded queue. Startup recovery precedes dispatch. Recovery during formation
is scoped to the leased profile; it cannot discard another live profile's staging.
Service schema v13 replaces the active singleton index with a partial unique
profile index. Snapshot reader admission uses the configured dataframe read budget;
clock/slot transactions happen before acquiring a reader permit.

## Ingestion design under review

The existing poller performs claim, immutable snapshot, full content hash, extraction,
canonical promotion and projection inline. The admission journal already provides
RESERVED/ORDERED/CLAIMED/LINKED/TERMINAL recovery anchors. CAP-5B must separate the
short durable reservation/claim from worker hashing/preparation, queue references,
and serialize promotion in durable admission order. An executor-only handoff that
lets whichever preparation finishes first write KEEP_FIRST rows is unacceptable.
Source-key exclusion must apply at promotion, without a newer same-key job holding
that guard while waiting for an older job.

## Adopted execution path

Every daemon document now reserves/claims a durable admission before the detector
returns. Hashing/private sealing and routing preparation run in at most two workers;
only the oldest unresolved admission promotes. Journal attempts/backoff survive
reopening (service schema v14). Default bounds are window 4, pending 64, source
bytes 4 GiB total/512 MiB each, with the separate shared workspace cache/memory/disk
budget. The cross-budget preflight rejects a per-source size above the source-pin
allowance. No stand configuration or deployment was changed.

Byte admission uses one metadata observation for both the dispatcher quota and
the durable reservation. Claim sealing copies at most that admitted byte count;
growth fails without a partial sealing file or loss of the original owned token.
This closes a reviewed gap where post-copy validation could otherwise follow an
unbounded copy from a producer's still-open descriptor.

Count/byte saturation leaves input in the inbox. Lost hints recover through a
one-second bounded journal scan. Exhausted verified-key work follows rejection;
exhausted pre-hash work retains a blocked token/order and reports capacity DOWN.
No fabricated path/mtime content key, silent dequeue or new operator replay API
was introduced. ING-11/ING-13 remain explicit. Receipt lookup before preparation
is advisory; promotion revalidates and falls back if necessary.

Shared writer admission covers CONTROL, EXPIRY, EXPORT_SLOTS, PROMOTION and
MAINTENANCE at transaction boundaries. After 100 ms a waiting request takes FIFO
precedence over fresh priority. Counters measure complete writer units, including
nested work, with no per-IOC telemetry. Dataframe ID reservations use CONTROL;
startup/schema migrations remain before runtime admission. Read/CSV/SMB paths do
not become canonical writer operations.

## Controlled reader/WAL evidence

`JdbcSnapshotSliceReaderIT` ran with reader limit 1: one consumer held an actual
read snapshot, a second waited without borrowing a connection, and 128 canonical
rows (8 KiB values) were written while the first snapshot remained open. The
first reader retained its original membership. PASSIVE checkpoint could not drain
all frames while that reader was held. After release, both readers completed,
their permits/connections were released, and TRUNCATE drained the WAL:

```text
CAP5_WAL pinned_bytes=6427232 rows_written=128 drained_bytes=0 reader_limit=1
```

The focused run passed in 46.344 s including its upstream build. This is a
controlled SQLite pinning diagnostic, not service throughput or a bounded WAL
size guarantee. The delayed consumer's lifetime still determines checkpoint
progress. Private state is owned by JUnit TempDir.

## Gates and limits

Focused export scheduler tests, the real Spring isolation test, service migration
and reader/WAL tests have passed. The real prepared-document test also passed:
newer preparation completed first, both artifacts remained empty until promotion,
the earlier masks row retained its source and aggregate name used the later rank.
The bounded-sealing regression and the existing source lifecycle recovery suite passed (17 cases; 36.851 s including the upstream build). Complete analyzer gates are still pending.
G5A/G5B are not closed. The frozen five-second maximum canonical writer occupancy
must be measured; scheduling cannot preempt an existing atomic transaction.
CAP-7C remains the required follow-up if that unit exceeds the accepted budget.
All measurement states must be private and removed after evidence capture.

## Test universe review

One new Surefire suite, `DataProcessingCapacityHealthIndicatorTest`, checks the
operational blocked-document status and WAL path/counters. Existing suites retain
their lifecycle classification, including the renamed handler implementation's
`FileSourceMessageHandlerIT`.
`PreparedDocumentPromotionIT` additionally checks the real Spring/Router/SQLite
winner outcome when a newer document is prepared first. The exact universe becomes
223 fast, 75 integration, 5 external and 293 deterministic-offline suites. No
coverage floor, analyzer exclusion or test discovery rule changes.
