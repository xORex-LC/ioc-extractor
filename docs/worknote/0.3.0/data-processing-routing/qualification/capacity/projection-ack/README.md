# Bounded projection ACK comparison evidence

One completed local-service experiment; no production activation or numeric
resource acceptance. See the [full report](../../../projection-ack-experiment.md).

- `comparison.json`: unaltered twelve-fork/six-pair collector output, all ACK
  samples, semantic/resource results and frozen measurement identities.
- `summary.json`: derived medians/ranges and original per-fork values, paired
  ratios, pooled operation percentiles, scope limits, cleanup audit and hashes.
  The premature runtime-cleanup flag is preserved and explained; no cost
  measurement was corrected after the run.
- `*.resources.jsonl`: original process/cgroup/named-file samples. Collection
  includes warmup/ACK; reported peaks reset after ACK and include catch-up.
- `*.controls.jsonl`: original concurrent control/maintenance measurements;
  eligibility and scheduled lateness remain distinct.
- `clock-preflight.*`: observed UTC/monotonic preflight. Runtime safe-clock
  validation remains authoritative.

Full daemon logs remain at `.dev/projection-ack-qualified/<index>/`, hashed in
the summary; publication phases and admission metrics remain in the report.
No database, JAR, workspace binary or credential is included. Sustainable
cleanup and broad production read p99 remain unqualified.
