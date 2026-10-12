# Typed chunk journal comparison evidence

Twelve completed local-service forks, six alternating pairs; no resource-budget
acceptance or production activation. The [full report](../../../typed-journal-experiment.md)
describes scope, protocol, negative observations and disposition.

- `comparison.json`: unaltered collector output, exact source/JAR/helper/policy
  identities, independent semantic fingerprints, original process metrics,
  admission counters, phase counts, physical census and cleanup outcomes.
- `summary.json`: derived medians/ranges and original per-fork values. Bulk
  heap/RSS/WAL/private peaks are recomputed from the original monotonic bounds.
  Original process peaks additionally include post-window reads and cleanup.
  The summary includes hashes of all original retained files and local daemon logs.
- `*.resources.jsonl`: original sampled process/cgroup/JVM/named-storage data,
  including paths and sizes of individual WAL files.
- `*.controls.jsonl`: original concurrent ACK/maintenance measurements; scheduled
  lateness and execution time remain distinct.
- `clock-preflight.*`: original UTC/monotonic preflight; runtime safe-clock checks
  remain authoritative.

Daemon logs remain in `.dev/typed-journal-qualified/<index>/`, hashed in the
summary. No DB, JAR, workspace binary or credential is included. Broad read p99,
sustainable cleanup and larger workloads remain outside this bounded result.
