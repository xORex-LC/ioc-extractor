# Results, policies and bounded diagnostics

This package pairs processing values with diagnostics, computes pure stopping
decisions, and retains useful samples with exact occurrence counts. It performs
no I/O and owns no transaction, thread, retry identity or logger. Types here use
the JDK and sibling diagnostic values; see the
[module reference](../../../../../../../../README.md) and
[package-info.java](package-info.java).

## Source map

| Source | Responsibility |
|---|---|
| [Result.java](Result.java) | Nullable value plus copied diagnostics; mapping and severity helpers |
| [Notification.java](Notification.java) | Unbounded mutable accumulation and immutable snapshots/results |
| [FailurePolicy.java](FailurePolicy.java), [FailureDecision.java](FailureDecision.java) | Pure evaluation and explicit stop/continue result for caller enforcement |
| [BoundedDiagnosticCollector.java](BoundedDiagnosticCollector.java) | Stage-local detail budget and marker-free batch construction |
| [BoundedNotification.java](BoundedNotification.java) | Run-level sample replacement, exact merge, optional delivery and display marker |
| [DiagnosticBatch.java](DiagnosticBatch.java) | Immutable retained detail with validated exact summary |
| [DiagnosticSummary.java](DiagnosticSummary.java) | Exact total, suppression and severity counts; addition and monotonic deltas |
| [SuppressionCodes.java](SuppressionCodes.java) | Package-private validation of consumer-supplied reserved marker metadata |

## Values and policy enforcement

`Result.success(null)` is valid. `map` skips a null value, preserves diagnostic
order and propagates mapper failures unchanged. A non-null mapper is required
even for a null value. Results copy their diagnostic lists, while `Notification`
mutates its own collection; `diagnostics()` and `toResult(value)` snapshot it.
Neither type automatically delivers diagnostics or applies a policy.

`failFast()` stops at the first retained ERROR/FATAL in the supplied notification;
`collectAndContinue()` stops only at FATAL. Both continue on warnings. A stop
holds its diagnostic; continue has no reason. Custom policies must be pure and
return a non-null decision. The caller enforces that decision before durable
effects. Compaction preserves the built-in stopping signals, not every code or
the original earliest reason: evaluate code-sensitive rules before compaction.

## Samples, exact counts and aggregation

For a positive budget N, ELEMENT and RUN share the following limits:

| Accumulator | Retained detail | Order |
|---|---|---|
| `BoundedDiagnosticCollector` | First N occurrences plus, if needed, first ERROR-or-worse and first FATAL; at most N+2 | Encounter order |
| `BoundedNotification` | At most N; a first ERROR/FATAL can replace the earliest lowest-severity budgeted sample | Replacement keeps the displaced position |

Both retain **every OPERATION occurrence** outside the budget. Such a rejecting
occurrence also satisfies the corresponding stopping signal. A budget does not
bound total bytes, context sizes, OPERATION cardinality or total delivery calls.
All occurrences, including equal ones and omitted detail, count separately.

Pass `batch()` between accumulation levels: it contains only real retained
occurrences and exact counts. Matching stage/run budgets preserve the direct-run
sample; a smaller stage budget can omit detail that a larger run would retain.
Counts remain exact, but omitted context and reasons cannot be reconstructed.
Merge each intended batch once: there is no deduplication or replay protection.

`DiagnosticBatch.of` copies detail and checks retained size, per-severity counts,
the reserved marker exclusion and retained ERROR/FATAL signals. It cannot prove
that omitted observations actually occurred. Use one stable marker code across
the entire aggregation tree. The construction marker is validation configuration,
not stored batch identity; checks compare code objects by equality.

`BoundedNotification.diagnostics()` is a display snapshot: when detail was
suppressed it appends a synthetic marker with the highest omitted severity and
`limit`, `suppressedCount` and `suppressedBySeverity` context. Its timestamp may
change on each read. Snapshot reads change no counts and deliver nothing. Never
merge the display marker as an occurrence; use `batch()` instead. The
[consumer guide](../../../../../../../../../../docs/guides/libraries/diagnostics.md#batches-counters-and-the-reserved-marker)
owns the full marker field representation and executable examples.

Summary totals exclude synthetic markers, severity counts sum to total, and
suppressed counts describe omitted real detail. `plus` combines independent
summaries; `since` subtracts an earlier snapshot; `plusDiagnostics` adds real
occurrences without changing suppression. Missing severities count as zero;
explicit zero entries are valid and affect record equality. Batch
`suppressedBySeverity()` omits zero entries. Arithmetic is checked, never wrapped.

## Delivery, mutation and ownership

`offer` reports whether the new occurrence was retained now. `addAll(batch,
delivery)` invokes the callback synchronously for each newly retained occurrence,
including replacements; replacing a sample never retracts an earlier delivery.
`addAll(batch)` merges without delivery.

A raw callback failure propagates after the failing occurrence has been counted
and retained. Later occurrences and the batch's omitted counts are not yet
merged. Bulk invalid input and arithmetic overflow can also leave an accepted
prefix. Nothing rolls back or retries it; replaying a whole batch can double-count
or redeliver that prefix. Use the
[resilient sink](../sink/README.md)
for fallible observation that must not abort processing.

Mutable notifications/collectors belong to one task and are not thread-safe.
Snapshots copy collections but share values, codes, context values and causes;
keep referenced objects stable. Nonpositive budgets, invalid metadata/counts,
configured markers as occurrences and hidden rejecting signals are rejected.
Public Javadoc describes exact exception conditions; the
[test reference](../../../../../../../test/java/com/iocextractor/platform/diagnostics/README.md)
maps boundary, merge and failure checks.
