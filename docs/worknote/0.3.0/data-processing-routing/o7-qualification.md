# O7 final optimization qualification

Status: in progress, 2026-10-03. Production candidate starts at `4dd238f4`.
O1–O5 are adopted; O6 remains rejected. This qualification introduces no runtime,
policy, dependency or schema change. Customer performance acceptance is separate.

## Protocol fixed before sampling

Use the existing production Spring comparison, real extractor/classifier/Camel,
physical documents/CSV and private SQLite/workspaces. Pin `DEBUG=false`,
`TRACE=false`, `LOGGING_LEVEL_ROOT=WARN`, JDK 21 and `-Xms128m/-Xmx512m`.
Five fresh JVM pairs alternate compatible/selected order for every primary
profile. Warmed profiles first insert a disjoint fixture in the same context;
measured inserts must not become canonical no-ops or receipt replay.

| Profile | Document rows / distinct | Import rows / distinct | Warmups | Shape |
|---|---:|---:|---:|---|
| first | 8,000 / 20 | 2,000 / 20 | 0 | domains, host cleanup |
| warm | 8,000 / 20 | 2,000 / 20 | 1 | domains, host cleanup |
| half-repeat | 8,000 / 4,000 | 2,000 / 1,000 | 1 | domains, host cleanup |
| unique | 8,000 / 8,000 | 2,000 / 2,000 | 1 | domains, host cleanup |
| small | 1,000 / 20 | 1,000 / 20 | 0 | domains, host cleanup |
| large | 100,000 / 20 | 100,000 / 20 | 0 | domains, host cleanup |
| mixed | 1,000 / 20 | 1,000 / 20 | 0 | original-view routing |
| long | 1,000 / 20 | 1,000 / 20 | 0 | original-view routing, 4 KiB URL paths |

Mixed documents contain domains, bare IPv4, URLs and MD5; mixed imports contain
masks-admissible domains/URLs. Original-view routing in mixed/long profiles makes
public fields comparable to the compatible configuration; those profiles do not
measure host cleanup and must not be pooled with the domains profiles.
Multi-input carriers, explicit NULL, source authority, filtered/blocked/recovered
outcomes and receipt-only recovery belong to the existing semantic test corpus,
not a fabricated timing ratio between inequivalent policies.

Repeat the first and warm profiles on original production revision `08d30621`
with the exact same comparison probe/script/resources. Identify the test/tools
harness overlay in the manifest; it must not modify production sources. Compare
full selected signatures across revisions and contemporary compatible ratios.
Previous O0–O6 measurements explain isolated effects, but different logging or
measurement modes cannot establish the final combined timing gain.

Use the existing neutral Router probe for 1/4/16/64 branches and 1/4 callers,
SUCCESS/FAILURE/RECOVERY; keep its results separate from IOC workload acceptance.
Re-run the session retention probe for duplicate/unique/long/4-caller profiles.
These are mechanism/capacity evidence, not full concurrent application throughput.
Run a separate instrumented/JFR duplicate-heavy profile; do not use its timing
as a primary result. Preserve all samples, failing reports and profile dispositions.

The unchanged historical guards (2x time, 3x caller allocation, 1.25x sampled
heap/VmHWM, 1 GiB VmHWM) detect regressions only. The original O0 acceptance
proposal is 1.50x first / 1.25x warmed time, 1.50x caller allocation, 1.15x
sampled heap/current RSS, 256 MiB heap / 512 MiB current RSS for the two base
profiles. The operator explicitly deferred budget agreement on 2026-10-03: O7 is
qualification only. Do not retroactively widen the proposal after sampling.
A missed or unagreed budget leaves performance acceptance open.

## Results and risk dispositions

Pending measurements, semantic gate and analyzer review. Raw JFR/logs/databases
remain in ignored `.dev/` workspaces; reviewed JSON/CSV and this report will
identify exact inputs, source/harness identities, host/JVM, spread and limitations.
