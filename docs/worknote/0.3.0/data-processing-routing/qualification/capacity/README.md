# Capacity investigation evidence

Diagnostic evidence for the
[capacity and scheduling review](../../data-processing-capacity-review.md).
Recorded 2026-10-04–2026-10-09. The incident files describe the old deployed runtime;
CAP-0/1 evidence additionally qualifies isolated corrected executables.

## Evidence files

| File | Scope |
|---|---|
| [stand-observation-20261004.json](stand-observation-20261004.json) | Read-only ledger observations during the stall, nearby process/cgroup/JMX measurements and sampled stack summary |
| [stand-final-state-20261004.json](stand-final-state-20261004.json) | Completed ingest, aggregate revision and successful publication ledger |
| [sqlite-matcher-scaling.json](sqlite-matcher-scaling.json) | All retained JDBC samples, median summaries, VM-work counts and exact-driver live EXPLAIN |
| [SqliteMatcherProbe.java](SqliteMatcherProbe.java) | Reproducible private SQL mechanism experiment |
| [cap-0-1-20261004.json](cap-0-1-20261004.json) | CAP-0/1 frozen identities, 40 paired primary forks, ten diagnostic phase forks, exact-driver work/plans and all-five live SMB/oracle checks |
| [cap-3-stages.json](cap-3-stages.json) | CAP-3 stage mechanisms: 60 alternating primary JVM forks, 100k/1m attribution and bounded 100k diagnostics, identical semantic signatures, no retained database state |
| [cap-4-workspace.json](cap-4-workspace.json) | CAP-4 G4: three incremental workspace/canonical/receipt JVM forks (10k/100k/1m rows per artifact) and three separate actual Spring/Tika/extraction forks; frozen bytecode, full-row oracle, explicit-GC diagnostics and verified removal of all private runtime/state |
| [cap-5-writer.json](cap-5-writer.json) | CAP-5 writer-occupancy diagnostic: 100k/1m incremental rows per artifact, real atomic lifecycle writer and receipts, operation wait/hold counters, full-row oracle and mandatory private-state cleanup; no whole-service or mixed-load acceptance |
| [cap-6-reference.json](cap-6-reference.json) | CAP-6 whole-service first-failure screens, all five AS_IS imports, private diagnostic/SMB attempts and drained upgrade/restore; explicit metric corrections and NOT_RUN cells; G6 remains NOT_ACCEPTED |
| [cap-7a-reference.json](cap-7a-reference.json) | CAP-7A source-only ordered oracles, HTML/DOCX 100k complete output screens and million-row reducer failures; frozen executable/tree identities, phase anchors and owned-state cleanup; G6 remains NOT_ACCEPTED |

`artifact_revision.changed_at` is transaction effective time sampled before
the mutation loop. It must not be used as commit completion wall time. The
09:27 observation contains the previous aggregate revision; the final state
contains the new revision with an earlier effective-time timestamp.

Raw diagnostic journals and JMX attach helper remain local incident scratch
files; selected value-free evidence is retained here. No credentials, arbitrary
IOC contents or environment file are copied into this bundle.

## JDBC probe protocol

Run the source launcher with JDK 21 and matching dependencies. From the
repository root, for example:

```bash
java -cp /path/to/sqlite-jdbc-3.53.2.0.jar:/path/to/slf4j-api-2.0.18.jar \
  docs/worknote/0.3.0/data-processing-routing/qualification/capacity/SqliteMatcherProbe.java \
  100000
```

Repeat for 1,000 and 10,000 aliases. Output is JSON Lines. Each private
`:memory:` database has one canonical row and one alias per generated key,
matching lookup/lifecycle indexes and the request TEMP-table shape used by the
application. It omits unrelated application columns, foreign keys and mutation
work; it is a matcher experiment, not a service benchmark.

The three variants are:

1. `current`: current joins, including TEMP-table drop/create/insert for each
   request, statement construction, result iteration and disposal.
2. `request_first`: the same request path with `CROSS JOIN` constraining nested
   loop order to requests → full alias-key lookup → canonical row lookup.
3. `direct`: a fully constrained single-key lookup with no request TEMP table;
   statements are still constructed per invocation in this probe.

Each variant receives eight warm-up calls, then three samples of 32 requests:
16 distributed hits and 16 misses. Every sample asserts exactly 16 results.
Variants run in fixed order within one JVM per cardinality. The stand was
active concurrently. Consequently timing ratios are illustrative and may
include JIT, scheduling and cache effects; no confidence interval or
production speedup is claimed.

A separate one-hit call counts progress callbacks every 1,000 SQLite VM
instructions, including current/request-first request staging. Recorded
`vmStepLowerBoundSingleHit=0` means **fewer than 1,000 counted instructions**,
not zero work. Progress instrumentation is removed before timing samples.
Callback granularity and possible statement-prepare callbacks make these
diagnostic lower bounds, not exact per-statement instruction accounting.
[SQLite progress callback contract](https://sqlite.org/c3ref/progress_handler.html).

An independent semantic fixture checks two active matches, exclusion at the
exact expiry boundary, stale alias/lifecycle exclusion, equal hash with a
different canonical value, and isolation between key definitions. All three
forms pass. This fixture does not qualify multiple keys per request, full
canonical mutation, ordered-field updates, receipts or concurrent recovery.

## Live plan observation

The optional `live /absolute/path/to/database` mode uses a `mode=ro` main
connection, reads one alias, and only explains candidate queries. Request
tables are connection-private TEMP state. It does not execute mutations,
`ANALYZE`, index changes or candidate SELECT scans on the production database.
Run as the service's filesystem owner to preserve database/WAL ownership.
The retained live observation used dependency JARs extracted from the exact
installed Spring Boot JAR, confirming SQLite engine 3.53.2.

Do not put actual alias values into output. The probe prints plan descriptions,
versions and counters only. Production benchmark, schema-statistics changes,
JFR/NMT startup flags and rollout belong to a separate controlled qualification.

## CAP implementation baseline

The owner adopted [cap-targets.json](cap-targets.json) on 2026-10-04.
[cap-baseline.json](cap-baseline.json) pins the historical stand identity and
fixture; it does not yet establish G0 or corrected-runtime acceptance.

The [execution report](../../cap-0-1-execution.md) records scoped G0/G1A/G1B
qualification and remaining RSS/writer-hold limits. Equal complete oracle
signatures are compressed and share disk storage locally; replayable per-fork
SQLite/CSV state is removed after checks by default. Only explicitly selected
coherent state samples remain compressed. No SQLite database, JAR, credential
or remote authentication file is versioned in this bundle.

[CAP-3 execution](../../cap-3-execution.md) records attribution complexity,
construction-time diagnostic retention, late checkpoint failures and the
distinction between sampled garbage-containing heap and retained detail.
Its stage measurements are not full-service resource acceptance.

[CAP-4 execution](../../cap-4-execution.md) describes the forced-small-budget
memory plateau and its scope. The two-artifact G4 diagnostic creates 2.2*N
candidates and 2*N canonical winners for each reported N; it does not time
Router, source reading or SMB. The separate upstream series measures those
input graphs through attribution, without routing or writes. All six forks
remove their databases/source files; only this compact JSON is retained.

[CAP-5 execution](../../cap-5-execution.md) separates the controlled profile,
durable-order, saturation and shutdown screens from the writer responsiveness
budget. The writer series reuses the CAP-4 two-artifact small-budget diagnostic
with operation telemetry; it does not reproduce the upstream series or run
Router, exports, largest imports or SMB. One fresh JVM per size is a diagnostic
sample, not a primary median or speedup comparison. Frozen source/bytecode
identity and verified cleanup are in the JSON; no replayable database is retained.

[CAP-6 execution](../../cap-6-execution.md) records the unchanged whole-service
budgets and distinguishes successful output checks from capacity acceptance.
Original raw-report identities are retained even where harness Git history was
later rewritten. Writer maxima are scoped using before/after operation counters;
unchanged lifetime maxima yield bounded, not exact, window maxima. The Serial
post-GC correction sums both generations. JFR weights remain sampled estimates.
Error/timeout resources are failure windows or explicitly incomplete raw maxima;
they are not completed million-row throughput. Live transport and final
activation remain unqualified. No credentials, DB, WAL, CSV or runtime copy
are versioned or retained as replayable state.

## CAP-7A source qualification

[CAP-7A execution](../../cap-7a-execution.md) separates source contracts from
complete-service capacity. Four fresh source-only JVMs pass ordered occurrence
oracles with roughly 309–315.5 MiB RSS at 100k/1m; their single-fork timings are
diagnostic and use a distinct domain fixture. Complete-service 100k HTML/DOCX
cells pass all five output oracles, but exceed local-time and writer-hold limits.
Both million-row cells complete the source stages and fail at the private reducer
quota before measured canonical writes. Failure-window memory does not establish
completed million-row resource acceptance.

The original reports retain their original Git identities: the measured
`b228651bb23d` tree equals `e525965c420c`, and the four full-service cells share
one boot-JAR digest. No primary success median, SMB result or deployment is
claimed. All owned source/oracle/workspace databases and runtime copies were
removed; compact reports and diagnostic journals retain the failed disposition.
