# SQLite capacity: remaining costs and feasible choices

Status: analysis requested by the owner on 2026-10-11, before another corrective
implementation cycle. **No new performance budget, architecture activation or
release acceptance is established here.** PostgreSQL is outside 0.3.0 scope.
The owner has no exact production size, arrival rate or active-database estimate
yet. Historical failed C5/G6 results and their frozen targets remain unchanged.

## Finding and recommendation

The original artifact-wide lookup defect was removable; it was not an inherent
SQLite limit. That correction, bounded processing and the selected IOC semantics
are already implemented. The remaining measured cost has a different shape:
preparation, canonical mutation and the volume of final data dominate the large
unique document. Shorter writer transactions are a separate availability goal.
They do not necessarily make the complete delivery faster.

There is evidence of further avoidable work, but no evidence that 30 seconds
for every 100k reference, or a five-second atomic writer hold, is attainable
with the current artifacts, guarantees and two-CPU envelope. There is also no
proof that those numbers are impossible after optimization. Treating either
claim as established would be a mistake.

Recommendation: retain the native default, investigate the dominant preparation
and write costs with a small, bounded set of experiments, and make a distinct
decision about control-operation availability before further investment in the
staged candidate. Do not activate staged SQLite merely because its individual
writer holds are shorter. Do not replace Camel or the database to address an
unattributed cost. Close the release decision against an explicitly supported
workload envelope, rather than expanding the implementation until a guessed
universal time target passes.

## Evidence identity and limits

Reviewed clean HEAD: `36c7b5c52ddc220fdcab155c00a6ea35494327a8`, branch
`module/platform/router`. The retained measurements ran at
`87fe7b0dca746a4c150bdaa18d104f5435f79680`, JAR SHA-256
`99fa2227e61183dd85a7810f10ea032aa151f1b506017628ef1fe129b56e28bd`.
Changes between that measured source and reviewed HEAD are documentation/evidence.

The [second C5 repeat](cap-7c-c5-second-corrective-qualification.md) and its
`qualification/capacity/cap-7c-c5-second-corrective.json`
remain authoritative for those executions. The
`qualification/capacity/cap-7c-feasibility-phase-analysis.json`
adds analysis of retained logs, **not another benchmark**. Every included log's
SHA-256 was checked against the retained bundle. Document stages are selected
by `event.action=stage_complete`, the `reference.docx` source and run ID;
durations come from monotonic `event.duration`, not UTC timestamp subtraction.
ERROR forks are excluded from the derived completed-window evidence.

The runtime envelope was unchanged: two CPU equivalents, Xms/Xmx 128/512 MiB,
cgroup high/max 768 MiB/1 GiB, 64 MiB preparation admission, standard 256 KiB
rows/64 KiB fields, 2 GiB per workspace and 8 GiB aggregate private disk.
Canonical private caches are capped at 512 KiB. These are measurement conditions,
not an assertion that every tuning parameter is optimal.

Times below are conservative local handoff-to-ready-slices intervals with the
declared quiet period removed. They are not exact dispatch-eligibility SLOs or
SMB cycle times. Large failed cells have one complete native sample, not a
three-sample median.

| Completed profile | Native SQLite | Staged SQLite | What it establishes |
|---|---:|---:|---|
| DOCX, 1k unique | 3.37 s | 8.66 s | Same complete oracles; staged fixed cost is substantial |
| DOCX, 100k unique, five artifacts | 69.54 s | No completed qualification | Native semantics complete; latency/hold/control screens fail |
| Processed CSV, 100k distinct detailed URLs to 20 hosts | Median 17.65 s | Median 17.26 s | Three complete pairs; fields, COALESCE, receipt, provenance and outputs agree |
| AS_IS aggregate CSV, 100k unique | 33.51 s | No completed qualification | Native semantics complete; hold/control screens fail |

The staged large cells fail collection after actual backward UTC steps exceed
the safe-clock policy. Their observed 421.54/153.77-second intervals are **not**
qualified throughput or valid native/staged ratios. They motivate locating
staged write cost; they cannot support a final performance verdict. Stable UTC
is required before a valid repeat. No cause such as NTP or virtualization is
asserted, and relaxing the skew policy is not a remedy.

### The large native document has a localized cost

| Sequential stage | Seconds |
|---|---:|
| READ_SOURCE | 0.805 |
| REFANG | 1.154 |
| EXTRACT | 2.733 |
| ATTRIBUTE | 0.379 |
| PREPARE_ARTIFACTS | 28.683 |
| WRITE_ARTIFACTS | 31.090 |
| Sum | 64.843 |

Preparation and writing account for **92.18% of these stage durations**.
Preparation includes routing, mapping, identity, encoding, reducer SQL and
sealing; it cannot all be attributed to SQLite or Camel without finer profiling.
WRITE_ARTIFACTS is also broader than physical writer occupancy. The difference
between the stage sum and 69.54 seconds includes coordination/output timing;
it must not be labelled pure export time.

The document commits through the existing per-artifact confirmations, with
receipt/run-ledger recovery across them. Managed import instead preserves one
cross-artifact delivery transaction. The five document promotion holds are not
one delivery-wide transaction. Optimizations must preserve each flow's actual
atomic unit, rather than enlarge or split it implicitly.

Five promotion holds total 25.630 seconds; the longest is 13.333 seconds.
Control holds themselves total only 0.086 seconds, but their maximum queue
wait is 12.453 seconds. These waits overlap other work and are not additive to
the document duration. Scheduling cannot preempt the running transaction.

The completed census contains **216,787 public rows including warmup** across
five artifacts, 216,787 aliases and the same total of source and ordinary
receipt rows. It also contains 100,054 field-origin facts, 100,054 receipt field
positions and 83,379 export-slot assignments. These are persisted row counts,
not SQL-statement or disk-write counts. The profile does substantially more
work than 100,000 inserts into one table.

Whole resource-window CPU is 59.17 seconds, sampled RSS peaks at 377.44 MiB,
sampled heap-used at 91.94 MiB, and measured allocations are 14.80 GiB.
Reported GC time is 0.429 seconds, with no full GC; memory PSI full is about
0.085% of its resource window. Allocation churn warrants investigation, but
retained heap, GC pauses and memory pressure are not the demonstrated primary
limit in this completed cell. Native aggregate import similarly has a
16.963-second atomic promotion hold. More heap alone does not address that hold.

As an accounting illustration, removing all 25.630 seconds of document
promotion holds while leaving every other cost unchanged would still leave
about 43.91 seconds of the conservative interval. This is **not a lower bound**
on a redesigned service: work and overlap can change. It shows why optimizing
only writer occupancy cannot establish the 30-second target.

### Short holds and low total cost are different results

In the completed 1k pair, native promotion has five holds totalling 0.221
seconds, maximum 0.090 seconds. Staged has 59 holds totalling 0.527 seconds,
maximum 0.025 seconds. Its total time is 2.572 times native and allocations
3.439 times native. The window includes 26 staged publication units, including
control/maintenance/export work; it is not 26 document deliveries.

The completed collapsing import needs only 20 accepted stage rows and 20 public
mutations after processing 100,000 inputs. Native's maximum writer hold is
already roughly 0.10 seconds in that cell. Staged adds approximately 5–6%
allocations and somewhat higher RSS for a small time difference. This is a good
case for either implementation, not evidence that the staged architecture is
necessary for the cleanup requirement.

## Which costs belong to which problem

| Category | Current finding | Disposition |
|---|---|---|
| Confirmed and corrected defects | Artifact-size lookup multiplier, early safe-time sampling, control workspace admission interference, repeated export-assignment scan | Retain regressions; do not reopen them as generic framework problems |
| Confirmed residual capability gap | Earlier 1m unique HTML/DOCX complete-service runs exhaust the configured private reducer SQLite capacity after source stages complete | Not a SQLite engine-wide size limit; qualify a new reducer or explicitly narrow supported input capacity |
| Confirmed operational cost | Large native atomic mutations delay short control work for 12–16 s in completed samples | Decide acceptable service latency; optimize the transaction or retain a qualified atomic-visibility alternative |
| Avoidable mechanisms, benefit not yet quantified | Repeated prepared-row decoding/key work; full payload for each candidate; per-version SQL preparation; repeated private schema setup | Isolate their time/bytes/counts before choosing a change |
| Required work under current semantics | Examine inputs, validate conditional mapping, preserve configured winner/order/provenance, write canonical facts/receipts, generate complete CSVs | Optimize representation and shared work; do not remove guarantees to improve a benchmark |
| SQLite constraint | One physical writer per database, even with WAL | Extra threads or a larger pool cannot provide concurrent writes to that database |
| Environment/measurement blocker | Actual backward UTC and incomplete large staged windows | Diagnose the environment separately; preserve failed windows and safe-clock rejection |
| Unmeasured envelope | Populated/history-heavy state, sustained mixed arrivals, complete 1m outputs, remaining format/import breadth and live SMB | State as unknown; no acceptance extrapolation from a small or collapsing profile |

The million-row finding belongs to the
[CAP-7A complete-service evidence](cap-7a-execution.md); its reader improvements
and source-only successes do not close the later reducer failure.

## A useful workload model

Input count alone is insufficient. Distinguish input bytes; occurrences;
distinct original values; final keys per artifact; artifact fanout; sources and
field-origin participants; active canonical/slot state; retained history and
versions; output bytes; and concurrent work/arrival frequency.

Every occurrence still needs enough processing to establish eligibility,
diagnostics and its contribution. A deterministic reducer may reduce subsequent
payload or mutation work to final keys and compact participant summaries. It
cannot generally turn the whole job into work proportional only to final keys.
When all values are unique, final keys grow with the input; cache reuse cannot
produce the collapsing profile's savings.

Complete artifact CSVs also require reading/writing their active output. Even
20 newly accepted keys can trigger an output over a much larger accumulated
database. Preserving a stable snapshot and sparse public slots has a cost.
History and hidden versions make sustained disk/cleanup qualification different
from a fresh-database run.

For writer scheduling, sustainable arrival rate requires total demanded writer
service to remain below available writer time, with headroom for bursts and
control work. Short chunks reduce non-preemptive blocking but do not reduce
that total demand by themselves. With no production rate estimate, a throughput
sample cannot establish sustainable queue latency.

## Feasible changes within SQLite

The following are hypotheses with concrete seams, not another mandatory sequence
of architecture stages. An optimization is retained only if its attributable
benefit and semantics pass; lack of benefit is a legitimate stop result.

### 1. Reduce repeated work on the existing native path

The [document workspace](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcDocumentPreparationWorkspace.java)
encodes every candidate into `candidate`, updates a separate winner index,
checks integrity and hashes the sealed database. Every sealed cursor then
decodes prepared rows again. The
[write stage](../../../../core/ioc-application/src/main/java/com/iocextractor/application/pipeline/stage/WriteArtifactsStage.java)
performs complete identity validation before the writer. The writer validates
again, mutates through a cursor and the
[receipt writer](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcConfirmationReceiptWriter.java)
opens separate cursors for receipt rows and field positions.

First measure these passes and encoding/hashing separately. Candidate changes
include fusing receipt rows and field-position capture in one bounded pass, and
carrying admitted final-key material/count/ordered-field metadata in a sealed
adapter-owned source. A general caller must still receive complete validation;
a sealed source must prove the same checks, pin/policy integrity and row count.
Do not simply remove the application or adapter's authority checks.

The canonical mutation/matcher sessions already reuse SQL resources. CAP-1B is
implemented; adding another global statement cache would duplicate ownership.
Sessions must remain connection/transaction confined. A separate set-based
new-row fast path is possible only if profiling demonstrates remaining per-row
kernel work dominates. It must preserve alias ambiguity, incoming collisions,
ID reservation, TTL restart, ordered fields, sources and receipts. It is a
larger semantic change than eliminating a repeated cursor.

**Verification boundary:** exact ID reservations and provenance with
deduplication disabled, final-key collisions, malformed/corrupt sealed input,
failure before commit and receipt-only recovery. Unchanged CSV bytes alone are
not an equivalence oracle.

### 2. Optimize private reduction rather than weakening canonical durability

Current document preparation uses `DELETE` journal and `synchronous=FULL`, with
batch commits, even before the workspace is sealed. Unsealed preparation is
discarded/rebuilt from a pinned input; sealed promotion failures retain a
workspace for replay. That distinction supports an experiment with less
expensive **unsealed private** persistence and a durable seal, not an automatic
change to public canonical or service transactions.

Prove the transition first: all private handles/sidecars are accounted for;
the final SQLite commit/database and identity/catalog/seal are durable in the
correct order; interrupted unsealed work is discarded; interrupted sealed work
replays exactly; a workspace cannot masquerade as sealed after partial writes.
Do not assume toggling a PRAGMA or forcing only the manifest establishes this.
The current source-text workspace already uses ephemeral storage policy; that
is not a new opportunity to count twice.

A compact reducer could retain final-key/rank/ordinal and replay evidence for
participants while retaining full winner payloads separately. Bounded sorted
runs and merge are an alternative to per-candidate B-tree maintenance when
cardinality is high. This is a potential response to the million-row quota
failure, not permission to discard losing observations. Full candidate payloads
currently participate in exact replay validation; their removal requires an
explicit, equally strong replacement. Count- and byte-bounded buffering, disk
headroom and cleanup remain mandatory.

**Verification boundary:** configured KEEP_FIRST/LAST_NONEMPTY, disabled dedup,
whole-row versus field merge, source contributions, diagnostics, input order,
cross-run final-key collisions and deterministic recovery. Measure both highly
collapsing and unique inputs; a combiner's improvement in one does not imply an
improvement in the other.

### 3. Remove staged transport overhead only if staged availability is needed

The staged candidate is not a whole-database copy per delivery. Its
[selected base](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcSelectedCanonicalBase.java)
hydrates affected canonical graphs. Slot handling additionally hydrates whole
affected assignment namespaces and active membership, which can depend on the
existing artifact size. Distinguish this from selective canonical hydration.

There are concrete transport costs:

- [Private initialization](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcStagedPublication.java)
  runs the native migrations and schema reconciler for each attempt. Consider
  a validated empty schema template or a smaller preparation for metadata-only
  units under the same fenced publication protocol. Never clone canonical data
  blindly or bypass catalog/format checks.
- [Typed chunks](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcTypedPublicationChunk.java)
  prepared and closed an INSERT for each version row at the reviewed baseline.
  The later [bounded staged cycle](cap-7c-staged-transport-cost.md) implements
  reuse through the existing statement owner within each chunk transaction,
  preserving effect order, checksum, idempotency and bounded cleanup. Its
  isolated allocation benefit does not remove full staged publication cost.
- [Transfer](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcPublicationTransfer.java)
  walks the sealed effects twice: description and transfer. A sealed compact
  chunk manifest could avoid repeated framing, if it proves the same counts,
  deadlines and digest against the pinned snapshot. Two bounded passes are
  preferable to an unbounded list; removing one is not free.

The versioned reader predicate, hidden facts, fencing, dependency invalidation
and later cleanup are structural costs of this atomic-visibility design.
Measure populated-state query plans and bytes per public mutation before adding
indexes; each additional index also increases version-write cost. Preserve
publication UTC, expiry equality and receipt visibility across all families.
A hybrid that writes directly to native tables while bypassing versioned
visibility is not a safe fallback.

### 4. Tune output and storage within proven snapshot boundaries

Generation-aware mutable projection and independent export profiles are already
implemented. Coalescing their triggers cannot remove a required full output,
and reimplementing their schedulers creates duplicate owners. Share scans or
encodings only where snapshot/as-of, generation, filtering and formatting are
equivalent. Canonical IDs and reusable export slots differ, so blindly reusing
CSV bytes is invalid. Do not page a changing dataset through unrelated read
transactions and call it one immutable snapshot.

Investigate cache/checkpoint settings only with recorded effective PRAGMAs,
page/sidecar bytes, reader lifetime and the aggregate memory budget. A 512 KiB
private cache is conservative, not a proved optimum. Reallocating a fixed
aggregate budget is different from increasing deployment limits. Forcing a
checkpoint or reducing batch size may increase total cost; neither is a
universal remedy for long transactions.

## Patterns worth borrowing, and alternatives to reject

These sources support mechanisms; none establishes a throughput prediction for
this service.

| Primary source | Applicable lesson | Boundary here |
|---|---|---|
| [SQLite WAL](https://www.sqlite.org/wal.html) | Readers and one writer can coexist; long readers delay checkpoint/reset, and large transactions retain WAL work | More writer threads cannot remove serialization; `journal_size_limit` is not a live-WAL cap |
| [SQLite many-small-query explanation](https://www.sqlite.org/np1queryprob.html) | In-process SQL avoids client/server round trips | Do not apply a generic N+1 diagnosis; measure access paths, JNI/bind work and repeated SQL preparation |
| [Spring JDBC batches](https://docs.spring.io/spring-framework/reference/data-access/jdbc/advanced.html) and [Xerial 3.53.2.0 DB source](https://github.com/xerial/sqlite-jdbc/blob/3.53.2.0/src/main/java/org/sqlite/core/DB.java) | Reuse a statement and bound batch retention; this driver's batch loop still binds/steps individual rows | JDBC batching is not parallel execution, bulk-engine insertion or an atomicity change |
| [Beam Combine](https://beam.apache.org/documentation/programming-guide/#combining-elements) | Compact accumulators and partial combination can reduce downstream payload | First prove associative/commutative summaries and a total winner order; arbitrary conditional COALESCE and occurrence/provenance loss are not permitted |
| [DuckDB external sorting](https://duckdb.org/2021/08/27/external-sorting) | Bounded runs, deliberate row/payload layout and sequential merging avoid dependence on unlimited memory | Borrow a private reducer pattern; do not add a second canonical database or assume DuckDB's published timings transfer |
| [NiFi back pressure](https://nifi.apache.org/nifi-docs/user-guide.html#back-pressure) | Bound both queued objects and bytes; avoid a queue blocking the completion needed to drain itself | Admission/backpressure already exists; tune against service demand, without silently expiring accepted deliveries |
| [SQLite RBU](https://www.sqlite.org/rbu.html) | Separating incremental bulk work from visibility is a known mechanism | Not a drop-in adapter: excludes WAL, prevents other target writes and does not enforce triggers/FK/CHECK in the normal way; reject adoption for this release |

No new framework is required for the first two candidate areas. No change to
Camel ownership is justified by the current phase evidence. Native sequential
Camel dispatch is already implemented and has its own scoped comparison in
[CAP-7B](cap-7b-execution.md).

SQLite persistence policy is also a semantic boundary. Current named presets
have a `NORMAL` synchronous floor, while document preparation explicitly uses
`FULL`. Neither should be described as identical power-loss durability.
[SQLite PRAGMA documentation](https://www.sqlite.org/pragma.html#pragma_synchronous)
distinguishes consistency from durability. This review proposes no relaxation
of canonical/service durability or clock policy.

## Reassess acceptance without rewriting failed history

Separate four decisions:

1. **Correctness:** configured cleanup, field authority, identity, winner order,
   provenance, delivery atomicity and receipt recovery are hard contracts.
2. **Safety/admission:** deployed CPU/memory limits and declared byte/count/disk
   admission must hold without data loss. Do not raise them to turn an old
   measurement green. Supported capacity must explicitly cover any rejected
   input sizes or require an intentional contract change.
3. **Availability:** choose how long eligible short operations may wait under a
   declared load. A five-second writer hold is a proposed mechanism budget,
   not a business fact. Reader access, control writes and external publication
   have different prerequisites. Short holds alone do not establish their SLO.
4. **Performance:** set document/import time ranges for specific cardinality,
   fanout, existing database and output conditions. Desired 15 s/30–40 s and
   adopted 30 s/180 s proposals are not measured lower bounds or forecasts.

The owner has now requested feasibility reassessment, not a retrospective PASS.
Keep `cap-targets.json` and C5/G6 `NOT_ACCEPTED`. Any replacement envelope or
latency policy must be reviewed, dated and frozen before another acceptance
series, with previous failures retained. Until production sizes/rates are known,
publish observed ranges and unknown regions rather than promise a universal
maximum or declare the million-row requirement unnecessary.

## A finite next decision

| SQLite-only option | When it fits | What still needs proving |
|---|---|---|
| Optimized native default | Large delivery latency and temporary control waits are acceptable within an explicit operating envelope | Dominant preparation/write improvements, admission bounds and mixed-load queue behavior |
| Optimized staged candidate | Control-write latency during large atomic deliveries is a real requirement that native cannot meet | Valid complete large/populated/mixed C5 windows, total cost, retained-version cleanup and operational activation |
| Different private reducer, same canonical kernel | Unique input capacity or preparation bytes dominate after lower-risk changes | Bounded storage, exact selection/replay, ID/provenance equivalence and complete large outputs |

The third option can accompany either writer architecture. It is not a third
database choice. Availability and total delivery cost should be judged
separately; staged storage is not a prerequisite of configurable URL cleanup.

Before another complete C5 cycle, collect one diagnostic breakdown of the
dominating private/native work: elapsed time and allocations for mapping,
encoding, identity, reducer SQL, seal integrity/hash, each prepared-row pass,
canonical matching/mutation, receipt capture and output formation. For staged,
add initialization, hydration, description, transfer, publish and cleanup,
with rows/bytes/statement preparations per phase. Use existing phase logs first;
new instrumentation belongs in separate diagnostic runs, not primary timings.

Choose **one** measured dominating mechanism for the first bounded experiment.
Reuse the existing oracles and unchanged workload/output semantics. Reject a
prototype if it just moves cost to cleanup/output, changes ID/provenance,
removes admission headroom or has no benefit outside noise. Do not proceed to
large reducer replacement just because a statement-reuse prototype was easy.

A complete qualification repeat becomes useful after a retained improvement or
an explicit acceptance-model decision, and after independent verification of
stable UTC. Stop invalid collection instead of repeating until a short window
happens to pass. Keep the native default and candidate isolation throughout.

The end of this work is a documented choice of writer architecture and supported
workload/availability envelope, verified semantics/safety, and measurements for
that envelope. It is not the elimination of all SQLite costs. The existing
planned matrix remains open until its disposition is explicitly agreed; this
analysis does not silently waive it or authorize stand deployment.

## Analysis validation

### Subsequent bounded staged execution

The owner-authorized [staged transport cycle](cap-7c-staged-transport-cost.md)
retains chunk-local statement reuse with about 65% lower diagnostic caller
allocations. After the user stopped guest time synchronization,
four observed preflights and ten complete C5 windows have no detected backward
UTC step or collector error. Both formerly incomplete large staged cells pass
output/provenance/receipt checks. They cost about 4.8–4.9 times native elapsed
time in their single pairs while substantially reducing maximum writer hold
and eligible control wait. Processed URL-to-host/COALESCE passes three pairs
with essentially equal elapsed time and 5–8% more staged allocations.

This validates a concrete availability/total-cost tradeoff, not a new universal
time promise. Staged maintenance/cadence delay, swap pressure, one unattributed
protocol rebuild, broader matrix and C6 activation remain explicit limits.
The finite cycle is complete; historical C5/G6 NOT_ACCEPTED and the native
default remain unchanged. Original analysis validation below describes the
read-only review, not the subsequent implementation or capacity acceptance.

This review reads current code, retained evidence and primary external sources.
It makes no production changes and runs no new load windows. Derived evidence
excludes ERROR forks and preserves metric scopes. Repository verification/PMD
were fresh at reviewed HEAD. `make docs` and `git diff --check` pass for this
documentation change. Those checks are not capacity acceptance.
