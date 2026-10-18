# Results, policies and bounded diagnostics

`Result<T>` pairs a nullable value with copied diagnostics. `Notification` is an
unbounded, task-owned accumulator. `FailurePolicy` returns a pure
`FailureDecision`; the service enforces it before durable side effects.

`BoundedDiagnosticCollector` retains a stage's first N budgeted occurrences and,
when necessary, its first ERROR/FATAL. `BoundedNotification` retains at most N
ELEMENT/RUN occurrences by replacing a lower-severity sample for the first
ERROR/FATAL. Both retain every OPERATION occurrence. Neither is thread-safe.

`DiagnosticBatch.of` validates immutable retained detail against an exact
`DiagnosticSummary`. Merge batches, never synthetic display markers. The entire
aggregation tree must use the same stable marker code. Suppressed details cannot
be reconstructed from counts. Same-size stage/run budgets preserve run samples;
a smaller stage budget can lose samples even though the counts remain exact.

A raw delivery callback can throw after partial mutation. Use a resilient sink
where reporting failures must be contained. Counts use checked long arithmetic.
No collector deduplicates occurrences or owns a thread, file or transaction.
