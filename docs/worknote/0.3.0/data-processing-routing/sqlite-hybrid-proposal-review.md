# SQLite hybrid proposals: applicability and remaining work

Date: 2026-10-12. Analysis of the owner's supplied proposals and attached cleanup
notes against HEAD `46cb4ab3b1347c3fa713e9748e2f28c63bcacac3` and its dirty worktree
containing the direct projection, compact journal and immutable receipt experiments.
This is a proposal review, not another implementation cycle, backend activation,
schema migration or resource-budget acceptance. Native remains the default;
PostgreSQL is outside release 0.3.0. Workload rates and a replacement budget are
still unknown. No new performance experiment was run for this review.

## Result

The proposals describe useful mechanisms, but several are already present.
Reimplementing priority admission, delta publication, metadata activation or
incremental cleanup would add competing owners without addressing the remaining
cost. The strongest next candidates are narrower:

1. Attribute control latency before writer admission, then investigate whether
   direct projection ACK/error needs to share prepared-control admission.
2. Measure eligible physical cleanup debt and progress by family; improve the
   existing cleanup owner if its measured service rate is insufficient.
3. Investigate ordinary receipt cursor/query cost without first adding another
   recovery representation. Evaluate immutable payloads over their actual use
   frequency, including normal document reuse.

Bulk SQL, index changes and sealed transfer descriptors remain conditional
experiments after phase attribution. New immutable generations, LSM-like
segments, generic dispatch, asynchronous ACK and multiple canonical files are
not justified by the current evidence. This review does not prescribe a new
unconditional sequence of optimization stages.

The [hybrid assessment](sqlite-hybrid-storage-assessment.md) retains the common
publication/conflict/recovery contract. The completed
[ACK](projection-ack-experiment.md), [journal](typed-journal-experiment.md) and
[receipt](receipt-payload-experiment.md) comparisons retain their original
measurement identities and both favorable and unfavorable outcomes.

## Disposition of the supplied approaches

| Supplied approach | Current implementation | Disposition |
|---|---|---|
| Shorten or separate control preparation | Direct updates and all privately prepared non-promotion operations share `controlPreparation` | High-value narrow candidate; establish lock attribution and preserve reserved workspace admission |
| Priorities with fairness | `JdbcWriterAdmission` has five classes and 100 ms aging | Reuse and measure; no second writer queue or assumption of transaction preemption |
| Separate ACK and persisted errors asynchronously | Production success and failure already take different paths; successful ACK is durable coverage | Keep contract; separate qualification probes, not durability guarantees |
| Immutable staging and metadata activation | Hidden typed chunks, sealed manifest, committed-unit marker and snapshot reads | Already the staged mechanism; optimize its preparation/transfer cost |
| Avoid copying unchanged data / immutable segments | Selective hydration and final deltas already avoid a full canonical copy | Measure residual duplicate work; defer a new segment engine |
| Bulk SQL and prepared batches | Hydration already batches; transfer reuses statements but executes each row | Conditional SQL/batch experiment under exact transport and resource guards |
| Defer secondary indexes | Potentially suitable only for isolated scratch state; live version indexes also enforce integrity and support readers | Audit exact indexes and capture assumptions first; no runtime drop/rebuild of canonical indexes |
| `PRAGMA optimize` | No application call found in the inspected JDBC source | Small conditional planner-statistics experiment on populated state; not a universal speedup |
| `WITHOUT ROWID` | Protocol fact/chunk/dependency/epoch and selection tables already use it | Limited new opportunity; typed version cleanup currently requires `rowid` |
| Header/body/recovery separation | Receipt headers, lazy row sources and import commit evidence already exist | Optimize actual consumers and counts/cursors before adding a materialized recovery index |
| Incremental/adaptive GC | Durable family rotation and bounded scanned-address pages already exist | Add debt/service accounting first; adaptation is conditional and time bounds are cooperative |
| Watermark reclamation | Attempt fences, committed state, latest versions, anchors and parent retirement govern eligibility | Keep those proofs; a global stamp cutoff cannot replace them |
| Separate logical retirement and physical reclamation | Present in staged versions and immutable receipt parent retirement | Retain and qualify under continued arrivals; do not duplicate it |
| Generation/file-level reclamation | Not implemented; units can retain live facts or recovery identities independently | Defer until finer cleanup demonstrably cannot meet a declared envelope |
| GC versus checkpoint versus vacuum | They address different physical layers | Account separately; never lower durability or equate file size with eligible debt |

## Control latency: the important boundary is before the writer

[JdbcStagedPublication](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcStagedPublication.java)
holds a fair `controlPreparation` lock around the entire non-promotion execution:
workspace admission, schema initialization, selective hydration, native mutation,
sealed transfer, conflicts/rebuilds, final publication and disposal. The direct
projection method also acquires this lock despite creating no private workspace.

This is not only protection for a shared Java collection.
[JdbcDocumentPreparationWorkspaceFactory](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcDocumentPreparationWorkspaceFactory.java)
reserves promotion/control/document memory headroom and permits only one private
control workspace when canonical headroom is enabled. Simply allowing two
prepared controls to overlap can exhaust admission or borrow resources needed
for promotion. Waiting for another preparation while holding canonical writer
ownership is also explicitly forbidden.

A narrower candidate is to keep prepared-control resource admission serialized,
while direct projection operations enter the existing writer admission without
waiting for an unrelated private preparation. Direct state is connection-local;
mutation, epoch checks, begin/append/seal/publish and commit already share one
canonical transaction. This is a source-supported candidate, not a proof that
the lock can be removed safely. Overlap can increase prepared-operation conflicts
and rebuild work. Preserve one-slot resource bounds and verify dependencies,
lost-update protection and finite progress before accepting it.

Measure command eligibility, preparation-lock wait/hold, workspace admission,
pool checkout, hydration/preparation, writer admission, transaction/commit and
postcommit work separately with monotonic time. Correlate bounded per-operation
summaries with rebuild reason and transferred effects; do not log every IOC.
Current aggregate writer metrics cannot attribute time spent before their queue.

The receipt series records control request maxima up to 599 ms with CONTROL
writer holds at most 10 ms and waits at most 22 ms. Its pending-only probe still
calls `recordFailure` when no mutation will apply. Thus these tails are not pure
ACK latency, and the lock's causal contribution remains unmeasured. Retain that
evidence; add separately named ACK-only, failure-only and existing combined
samples rather than silently changing the meaning of earlier measurements.

### Existing scheduling and ACK guarantees

[JdbcWriterAdmission](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcWriterAdmission.java)
already orders CONTROL, EXPIRY, EXPORT_SLOTS, PROMOTION and MAINTENANCE. Requests
waiting at least 100 ms outrank fresh requests and retain enqueue order. Its
tests force aged maintenance ahead of fresh control. This prevents indefinite
overtaking at transaction boundaries; it does not reserve a measured fraction
of I/O service or establish a maximum wait. In-flight transactions are not
preempted. A weighted/deadline policy is justified only if this owner proves
insufficient, and must also account for the preparation gate before it.

[GenerationOwnedArtifactProjection](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/lifecycle/GenerationOwnedArtifactProjection.java)
installs a snapshot and durably acknowledges its installed generation on success.
It persists generation-scoped failure state on failure. ACK is not command
acceptance; it is coverage already installed and acknowledged. It also clears
the old error under the existing CAS policy. Moving a failure write into a queue
can reorder it against a later generation/ACK unless the original expected
generation and policy remain authoritative. There is no evidence requiring a
new durable queue; the combined qualification probe is not the production
success protocol.

### Required forced-order checks for a preparation change

- Pause maintenance after hydration, apply direct ACK, then resume it: reject or
  rebuild stale overlapping state without losing the ACK.
- Reverse that order and interleave required-generation advance, stale ACK and
  stale error: preserve the shared generation CAS rules.
- Hold the only private control workspace: direct operations must not consume
  another workspace; promotion keeps its reserved headroom.
- Sustain controls while maintenance is pending: measure finite publication and
  cleanup progress, rebuild counts and complete control latency.
- Interrupt at every wait boundary and inject failures: release each owner once,
  preserve cancellation/fencing and test receipt recovery after commit.

## Large publications: optimize the existing delta protocol

Metadata activation is already implemented. Typed effects are durably transferred
while hidden; seal checks the declared chunk/effect manifest, and publication
validates fences, pins, epochs and fresh safe UTC before committing visibility.
Version views reject pending and obsolete fences. It is not a full-table
generation replacement, and introducing one would not eliminate required work.
Partial updates include renewals, aliases, source/field origins, slots, revisions,
receipts and lifecycle history. Their visibility must remain aligned.

[JdbcPublicationHydrator](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcPublicationHydrator.java)
copies selected committed rows, not an entire canonical database.
[JdbcPublicationTransfer](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcPublicationTransfer.java)
walks final captured changes, not a full-table diff. Existing versions already
share unchanged canonical facts across units. Some unchanged receipt payloads
are intentionally hydrated for native count/completion checks; some observations
require new evidence even if public IOC fields do not change. These cases must
be attributed separately before calling them redundant copies.

There are concrete residual candidates:

- Transfer performs two walks over one sealed snapshot. Both materialize effects
  and compute addresses/framed sizes; only the second inserts them. A persisted
  sealed chunk descriptor could share that work but adds private disk, framing
  versioning and integrity obligations. Measure the two walks first.
- Hydration already uses prepared batches bounded by 256 rows and encoded bytes.
  Typed insertion already caches prepared statements within a chunk, but still
  calls `executeUpdate` for each row. Test driver batching at this exact boundary,
  preserving order, generated transport evidence and rollback/replay semantics.
  A batch does not release writer ownership within its transaction.
- `INSERT ... SELECT` could avoid Java row materialization for compatible typed
  copies. Across these databases it requires a connection-owned read attachment
  or another explicit source arrangement. Capture epochs and selected facts in
  one source snapshot; qualify names, field/row byte checks, NULL/BLOB values,
  cancellation, quotas, checksum framing and cleanup. Do not bypass the native
  business planner or turn a bounded chunk into an unbounded writer transaction.
- Generic version creation copies native lookup indexes in addition to attempt,
  address and key indexes. Audit actual query plans, constraints, foreign-key
  requirements and B-tree page cost for each family. Prefix overlap alone does
  not prove an index is dispensable. The immutable receipt trial demonstrates
  that fewer indexes/metadata can coexist with slower reads.

Delayed index creation is most plausible in fresh private scratch state, before
capture or readers need the indexes. Dropping indexes from shared canonical
version tables affects old visible data, concurrent reads, replay integrity and
cleanup; later rebuilding them is itself heavy writer work. Reusable native
schema reconciliation and identity checks must still pass.

SQLite supports set-based inserts; this is a mechanism possibility, not a
measured project gain. [INSERT](https://www.sqlite.org/lang_insert.html).
For planning experiments, retain actual pinned-driver plans and timed results;
an indexed SCAN can be appropriate for a full receipt/export scan, and EXPLAIN
QUERY PLAN does not report runtime contribution. [EXPLAIN QUERY PLAN](https://www.sqlite.org/eqp.html).

### Statistics and WITHOUT ROWID

`PRAGMA optimize` can update selected planner statistics; SQLite recommends it
after schema changes and periodically for long-lived connections. It can write
and alter plans, and it provides no absolute timing guarantee. Admit it through
the existing maintenance/connection owner, not on every Hikari connection return
or latency-sensitive query. Test populated distributions and compare identical
statistics policies before crediting another optimization. [ANALYZE guidance](https://www.sqlite.org/lang_analyze.html).

Protocol facts/chunks/dependencies/epochs, selection tables and cleanup cursors
already use `WITHOUT ROWID`. Generic typed versions currently depend on `rowid`
for scanned-address pagination and deletion; protocol units/attempts use integer
AUTOINCREMENT identities. Converting them is not a DDL suffix change. Wider
composite primary keys also enlarge secondary index locators. SQLite identifies
small rows with non-integer/composite keys as promising; single INTEGER PRIMARY
KEY and large rows generally favor rowid tables. This needs an isolated physical
format/cleanup experiment, not universal adoption. [WITHOUT ROWID](https://www.sqlite.org/withoutrowid.html).

## Receipts: identify the consumer before adding a projection

The tested immutable format keeps the typed business values. It is not dictionary
compression or a binary body that must be decompressed. Its extra visibility
checks include the logical parent, artifact marker and a newer retirement
barrier. The retained primary report's `receipt_families_after.readPlans` shows
additional correlated indexed probes for those checks. They are plausible read
cost, not a measured breakdown of the observed 57.9% document scan regression.

Headers and lazy bodies already exist in
[JdbcConfirmationReceiptStore](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcConfirmationReceiptStore.java)
and [JdbcReceiptRowSource](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcReceiptRowSource.java).
`findComplete` filters one current-policy COMPLETE header, reads artifact markers,
validates counts and returns repeatable row sources. It does not eagerly decode
all payload values. `hasReusableReceipt` nevertheless invokes this complete lookup,
so it incurs structural/count checks rather than just an indexed existence check.
Changing that must explicitly preserve the supported integrity and fallback
behavior; do not silently redefine the port's reusable-receipt guarantee.

Full reading is not limited to exceptional crash recovery.
[IngestionService](../../../../core/ioc-application/src/main/java/com/iocextractor/application/ingest/IngestionService.java)
normally tries receipt replay for claimed documents with reusable content/policy.
[ConfirmationReceiptReplayService](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/lifecycle/ConfirmationReceiptReplayService.java)
feeds all stored artifact confirmations into the canonical writer, preserving
configured fields, occurrence positions and current-observation lifecycle effects.
A header cannot replace those values. Real repeat-content frequency is unknown.
Managed import has a different receipt: promotion checks committed evidence
before opening the stage, and finalization consumes result issues/warnings.
The ordinary receipt trial does not optimize those children.

A strong reader candidate precedes any new `receipt_recovery_index`:

1. `ReceiptCursor.next` executes one positions query for every receipt row, in
   both formats. Investigate two ordered streaming cursors on the same owned
   snapshot, merging positions by ordinal with one row's bounded fields in memory.
2. Separate count validation, row scan, position scan, conversion and digest time.
   Retain empty-position rows, ordinal gaps, duplicate IOC keys, SQL NULL and all
   expected totals. The measured scan includes digest construction and is not
   a timing of the complete replay/confirmation operation.
3. Only if parent visibility dominates, consider a receipt-specific query that
   validates parent/artifact/retirement state once per fixed snapshot and retains
   each child's committed-unit/fence checks. Keep it inside the common read
   facade; reading raw hidden tables without these proofs is invalid.

Covering indexes are a conditional query-specific option; their byte and write
cost must be counted. The supplied integer-ID/state recovery-index example does
not map directly to our text receipt IDs, source/policy lookup and existing
headers. Duplicating those facts adds publication and retirement consistency
work. [SQLite covering indexes](https://www.sqlite.org/queryplanner.html#covidx).

Evaluate formats as resource vectors over a declared period: CPU, allocations,
read/write bytes, occupied storage and recovery/retirement latency separately.
Do not add unlike units into one score. Use actual write, reusable-document read,
crash read and retirement frequencies. Faster retirement and less storage may
justify slower reads, but the current three-fork results and unknown frequencies
do not establish that choice. Do not automatically extend the format to import
results, history or active recovery state.

## Cleanup: existing bounds, missing sustainability evidence

[JdbcPublicationMaintenance](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcPublicationMaintenance.java)
already rotates families durably. Version pages scan at most the configured
address limit, not merely that many successful deletions. Journal cleanup removes
bounded child rows from one attempt per visit; headers have separate reference
checks. Cursor progress and deletion share a transaction. The current ceiling is
256 scanned addresses; qualification uses 128. This mechanism needs measurement
and possibly targeted refinement, not replacement with generic incremental GC.

One receipt selected for logical retirement can still have thousands of children
in VERSIONED mode. A parent-count limit is not a physical-effect limit. Immutable
payloads remove that child tombstone/hydration fan-out for their admitted families;
other retention families keep their existing behavior. Also, equal round-robin
family visits do not give equal useful cleanup throughput: retained prefixes,
empty units and reference checks consume work without reclaiming rows.

The proposed DELETE of `publication_attempt` is illustrative only. A low stamp
can still be the current committed attempt needed by typed versions, anchors or
recovery. The existing journal reaper deletes an attempt only when it is obsolete
and its permitted children have drained. A global watermark cannot replace
fence/state/reference/parent eligibility. Attempt stamp is allocated before
publication, whereas `publication_sequence` records committed order; neither
alone describes all per-key/scoped dependencies.

### Required accounting

Track separately by physical family:

- Protected current/retained/in-flight facts and required recovery identities.
- Eligible obsolete/cancelled/retired rows and estimated bytes, their oldest age,
  creation rate and reclamation rate.
- Scanned versus removed rows, page duration including queue/commit, writer
  service received, wraps/no-progress reasons and catch-up time.
- Reusable freelist pages, live/index pages, allocated file bytes and WAL
  outstanding frames. File size alone is not cleanup debt.
- Reader snapshot duration and any logical references that outlive one SQL
  transaction; workspace/quota headroom must remain available for cleanup.

Current page/health interfaces do not provide this complete debt model. Computing
it by full-table COUNT scans on every request would create another workload;
use bounded indexed diagnostics and measured summaries before choosing persistent
counters. Keep counters consistent with cancellation, retirement, cleanup and
crash recovery if they become admission authority.

For eligible debt, `dB/dt = lambda_eligible - mu_reclaimed` is a useful approximation.
If long-run rates are equal, there is no catch-up reserve; if arrivals exceed
available reclamation, backlog grows. Compare bytes and work as well as row counts,
and consider bursts, reader protection and competing writer classes. Priority
does not create writer capacity. Reduce new intake under headroom pressure while
preserving cleanup's resources; do not lower protected-data guarantees to improve
the measured rate.

A monotonic time budget may stop between bounded atomic steps and commit the
completed page. It cannot guarantee that one SQL statement or COMMIT finishes
within 20 ms. Adapt row/byte ceilings only after measured service rates, with
hysteresis and hard bounds; no example 500-row/20-ms value is an adopted limit.
Retain the existing cursor transaction and anti-starvation behavior. The supplied
LIMIT-in-subquery technique is portable to the pinned SQLite without depending
on the optional direct DELETE LIMIT extension. [DELETE syntax](https://www.sqlite.org/lang_delete.html).

### A retained growth category the proposals do not close

The existing [C6 boundary](cap-7c-c6-operations.md) retains document/import promotion
identities and pins. Header cleanup only removes unreferenced control units;
receipt expiry alone cannot authorize deleting publication idempotency evidence.
Therefore zero obsolete-version or receipt-payload debt does not prove a steady
total database size. A durable terminal acknowledgement/retention contract, or an
explicit accepted retained footprint, is required before compacting those pins.
Do not silently delete them using an age or publication watermark.

### Checkpoint and file reclamation

The attachment correctly distinguishes logical retirement, application physical
GC, WAL checkpoint and vacuum. A SQL snapshot protects pages in SQLite; a logical
consumer which closes/reopens connections needs a separate lifetime proof if it
expects older state. Neither application GC nor checkpoint can discard protected
state merely to hit a size target. [WAL behavior](https://www.sqlite.org/wal.html).

PASSIVE checkpoint does not wait for readers/writers to finish; RESTART/TRUNCATE
can wait and block writers. Moving checkpoint work off COMMIT is conditional on
proving its owner/cadence and recording WAL growth; never disable autocheckpoint
without a working backstop or lower FULL durability for this comparison.
`incremental_vacuum` requires the corresponding auto-vacuum layout; enabling it
on an existing NONE database requires rebuilding it. Freed pages may already be
useful reusable capacity without shrinking the file. [SQLite pragmas](https://www.sqlite.org/pragma.html).

Separate segment/file reclamation adds references, compaction and backup/restore
requirements. ATTACH is potentially useful with a read-only preparation source
and one writable target; it does not justify splitting one canonical atomic unit
across files. WAL does not provide crash-atomic commit across multiple updated
attached files. The existing service/dataframe split has deliberately distinct
authorities and receipt recovery, not such a joint transaction.
[ATTACH guarantees](https://www.sqlite.org/lang_attach.html).

## Finite decision and exit conditions

The next candidate, if authorized, should be one bounded control-admission
experiment: retain the current direct ACK and compact journal, leave receipt
representation and bulk layout unchanged, add stage attribution, then compare
the existing gate with the narrow direct-operation bypass under forced ordering
and the same mixed workload. Preserve pure and combined probe identities and
equal preconditioning. Stop if complete control latency does not improve or
publication progress/rebuild/storage cost worsens without an accepted tradeoff.

Separately required before staged activation: demonstrate continued cleanup and
catch-up on populated state with controlled readers, and define the treatment of
retained promotion pins. This is an operational qualification obligation, not a
promise to reach an arbitrary old time budget. The product owner still needs to
choose supported workload/disk/recovery boundaries and the receipt format.

If the immutable format is desirable after lifecycle-cost evaluation, optimize
the existing reader first. Bulk SQL/index/descriptor experiments follow only
when their attributed share is material. Each comparison changes one mechanism
and retains whole-service semantics, exact source/binary/input identities,
failed windows, writer availability, memory/disk and cleanup evidence. No new
framework, Maven module or canonical database family is required for these
bounded candidates.

## Analysis validation

- Read the supplied cleanup attachment, current source, all three experiment
  reports, retained receipt query plans and the C6 operational boundaries.
- Verified SQLite mechanics against the official pages linked near each claim;
  recommendations distinguish source facts, existing measurements and hypotheses.
- At the start, `make context` reported the reviewed HEAD, dirty tree and fresh
  passed verify/PMD evidence. Those are existing implementation gates, not new
  performance acceptance or execution of the proposals in this review.
- This change adds analysis documentation and its bundle index entry only.
  `make docs` passed with 2,195 checked links and zero errors;
  `git diff --check` passed. No production code, policy, resource limit, schema
  or existing evidence is changed.
