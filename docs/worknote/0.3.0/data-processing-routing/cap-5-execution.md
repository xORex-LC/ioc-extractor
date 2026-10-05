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

## Gates and limits

Focused export scheduler tests and the real Spring isolation test have passed.
Migration, bounded-reader, shutdown and complete analyzer gates are still pending.
G5A/G5B are not closed. The frozen five-second maximum canonical writer occupancy
must be measured; scheduling cannot preempt an existing atomic transaction.
CAP-7C remains the required follow-up if that unit exceeds the accepted budget.
All measurement states must be private and removed after evidence capture.
