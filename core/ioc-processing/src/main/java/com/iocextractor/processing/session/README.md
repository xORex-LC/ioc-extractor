# com.iocextractor.processing.session

## Responsibility and type

[IndicatorProcessingSession](IndicatorProcessingSession.java) owns invocation-local
reuse of immutable semantic results. It is thread-confined, implements
`AutoCloseable` and depends on the classification facade and domain host derivation.
It does not retain occurrence diagnostics, positions, mapped rows or durable state.

## Keys, policy and source

Classification uses the complete `Indicator` as key: value, type, source label and
section all participate. Original and derived indicators share this partition.
`classifyWith` reuses it only when the operation classifier wraps the exact pinned
policy instance; a different policy bypasses the cache. Policy behavior must remain
fixed for the scope, including custom collaborators used by that policy.

Host derivation uses original value/type as key and caches only successful derived
value/type pairs. Every result reattaches the current occurrence's source, including
on a hit. Typed unavailable host results and thrown failures are not cached.
Unmatched or neutral classification decisions are ordinary successful values;
cache admission does not decide whether an input should be accepted downstream.

## Budget and lifetime

Defaults are 256 entries and 1 MiB of estimated retained-byte charges in total,
split equally into classification and host partitions. Custom odd totals give the
extra entry/byte to classification. Each partition uses access-order LRU eviction
until both its entry and charged-byte limits admit a new value. An entry above
64 KiB or its partition's byte limit bypasses caching without evicting hot entries.
A zero entry/byte budget disables admission; negative budgets are rejected.
The charge estimate is not a whole-process heap or RSS guarantee.

Close clears retained references and is repeatable. All processing entry points
reject use after close. The caller owns the scope, closes it on success and failure,
and must not share one session concurrently. Independent sessions share no cache.
Document and import staging handles supply wider reuse; direct single-row calls
use local scopes. This class itself does not open a route, thread pool or workspace.

## Verification and related docs

[IndicatorProcessingSessionTest](../../../../../../test/java/com/iocextractor/processing/session/IndicatorProcessingSessionTest.java)
covers source/section/type keys, policy bypass, host-source reconstruction, LRU/
charged-budget eviction, oversized bypass, failures, close and isolated concurrent
sessions. It does not establish thread safety for sharing a session or an arbitrary
custom policy. See [ADR 0033](../../../../../../../../../docs/ADR/0033-bounded-semantic-reuse-during-import-staging.md)
for rationale and [processing](../../../../../../../../../docs/dev/processing.md) for runtime ownership.
