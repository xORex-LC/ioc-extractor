# CAP-0–CAP-1 execution evidence

Status: implementation and scoped qualification complete, 2026-10-04.
Repository gate freshness is owned by `make context` and must match the
delivery HEAD. Scope is CAP-0, CAP-1A and CAP-1B from the
[capacity plan](data-processing-capacity-plan.md). Adopted resource targets
remain independent acceptance criteria. CAP-2–CAP-6 are outside this change.
The installed service has not been replaced by the qualification executable.

## Changes and ownership

`c7314427` changes ordinary request matching and staged import alias planning
to request-driven complete-key probes using SQLite `CROSS JOIN`. Artifact,
definition, hash and canonical material all participate in indexed lookup.
No index migration or snapshot-wide bulk prematching is introduced.

`46eea3af` scopes matcher/mutation resources to the caller-owned connection and
transaction. One session serves an ordinary artifact transaction; import
promotion opens one session per affected artifact. Sessions retain at most 32
prepared statements, clear bindings and batches between leases, close before
commit and never commit or close the caller's connection. Singleton requests
use complete-key direct lookup; general requests use one lazily created TEMP
request table, with 256-request chunks and 256-key batches. Newly
inserted/restarted rows reuse their immutable row-local key material.

Alias delete/reinsert remains unchanged. Staged import planning finishes before
promotion, and ordinary confirmation continues to see its own writes. Import
planning/final-cell decoding and lifecycle archival remain separate owners.
No global statement or IOC-content cache is added.

## Contracts and functional checks

Real JDBC regressions cover zero/one/multiple candidates, multiple keys and
requests, stable ordering, equal hashes with unequal material, exact expiry,
artifact/definition isolation, changed aliases visible within the transaction,
independent connections, chunk boundaries, rollback and fresh retry. Resource
scope tests exercise bounded eviction, thread/reentrant ownership, clearing
failure and preservation of close failures. Interrupted admission never runs
the cancelled work and permits a later fresh attempt.

The final resource regressions also verify that a full pool never evicts a live
lease, a released lease cannot interfere with its next borrower, mutation
sessions require a caller-owned transaction, and closed/cross-thread session
access cannot mutate state. Missing canonical rows and NULL, nonpositive or
misordered lifecycle metadata fail closed. Focused suites execute seven
statement-scope cases and fourteen matcher/mutation cases.

The JDBC integration module passed 251 executed test cases and one explicit load
skip. The new resource test increases the fast suite universe from 220 to 221
and the deterministic universe from 287 to 288; no test/coverage/analyzer
threshold is lowered. Exact-HEAD verify/PMD results belong to the repository's
gate evidence rather than to an earlier frozen runtime manifest.

### CAP-1B-SQL-TRUST analyzer review

The resource refactor changes eight existing SpotBugs finding identities and
adds five query call sites, producing 125 raw findings instead of 120. All
thirteen candidates are reviewed individually in the accepted-findings XML;
the exact method/signature/hash/bytecode selectors replace the eight stale
identities. No analyzer rule, scope or threshold changes.

| Query owner | Reviewed SQL boundary |
|---|---|
| Match session: singleton/readStaged | Validated and quoted artifact identifier; complete key, artifact and deadline values bound; staged values bound on insertion |
| Mutation engine/session: loadForImport/loadStored | Row-reader projection uses validated schema and fixed internal columns; canonical ID bound |
| Mutation session: findStored/requireRowId | Prebuilt query uses quoted schema identifier and fixed columns; row key bound |
| Mutation session: insertActive | Validated schema/internal columns quoted; all public, identity, source and lifecycle values bound |
| Mutation session: updatePublicRow | Changed names derive only from immutable schema iteration and are quoted; public values including NULL and canonical ID bound |
| Mutation session: renewLifecycleOnly | Quoted schema identifier; timestamps, ID and active deadline predicate bound |
| Source recorder and mutation session: record/recordSource | Schema-derived source table revalidated on quoting; source, ID and timestamps bound |
| Ordered field store: load/upsert | Scope chooses one of two constant adapter-owned templates; identity, field and origin values bound |

The retained statement scope changes resource ownership without allowing IOC
values into SQL grammar. The existing `sql-schema-map-binding-change` review
trigger remains applicable. The first full gate exposed a nine-branch coverage
regression and this undisposed analyzer delta; the added resource/failure cases
and explicit review address them without relaxing coverage ratchets. Final gate
status must still be checked against delivery HEAD.

### Resource watchlist and final validation

The PMD watchlist reports 40 `CloseResource`, four `PreserveStackTrace` and
zero `NcssCount` findings. Fourteen ownership findings touch changed JDBC files:

| Ownership boundary | Disposition |
|---|---|
| Match/mutation sessions and ordered-field store: twelve borrowed statements | Each statement is accessed through a try-with-resources lease. The lease clears parameters/batches; the owning bounded scope closes physical statements on eviction or session close. Closing the statement after each row would defeat the reviewed reuse contract. |
| Import writer: one borrowed mutation session | The enclosing try-with-resources registry owns every per-artifact session and closes them before the caller commits. |
| Mutation-session registry: one close-loop finding | The loop explicitly closes each session, continues after a close failure and preserves suppressed failures. The caller's connection remains caller-owned. |

These are analyzer limitations at explicit ownership boundaries, verified by
the resource/failure regressions above. No watchlist suppression or baseline is
introduced. The existing stack-trace findings are unchanged by CAP-1B.

The adopted PMD policy remains at zero blocking and 20/20 advisory findings.
Review of the import writer's affected findings confirms preserved detach/
restore failure handling, branch planning and ordered mutation accounting.
CPD remains at 24/24 groups; the affected lifecycle-reader/writer schema helpers
are existing duplication, with no new session-resource duplication.

Final deterministic verification passes all 288 suites, report-integrity checks
and coverage ratchets. Aggregate coverage is 91.01% line / 82.90% branch;
SpotBugs reports 125 individually accepted findings and zero visible findings.
Documentation links and tools/packaging contracts pass. The delivery check must
confirm both `verify.fresh=true` and `pmd.fresh=true` at the committed HEAD;
offline suites do not replace the provisioned SMB evidence below.

## Measurement protocol

[Raw measurements and identities](qualification/capacity/cap-0-1-20261004.json)
pin frozen CAP-1A/CAP-1B production classes/libraries, byte-equal common probes
and policies, physical input hashes, driver/JDK versions, JVM limits, schema
versions, samples and oracle digests. Both primary profiles use five alternating
before/after pairs for documents and processed imports: 40 fresh JVM forks in
all. Each fork starts with equivalent empty stores and fixed lifecycle active.
No primary sample attaches an agent or JFR. Preparation/publication comparisons
check complete fields, keys, IDs, origins, diagnostics, revisions and receipts.

These paired inputs use original-view routing, one section and four IOC types.
The mostly unique 100k import actually contains 100k physical CSV rows.
They differ from the stand's cleanup policy and six-type/400-section document.
The initial 10k comparison overlapped PMD for its first 16 seconds and is
excluded. Earlier single-fork sanity samples are not primary improvement or
budget evidence. Diagnostic runs interrupted by WSL relocation left truncated
files and are excluded; ten complete diagnostic forks replace them.

## Primary comparison

Medians of five paired forks. Caller allocations exclude background threads;
RSS is sampled process memory, including startup, rather than live heap or
cgroup charge. Allocations use decimal units; RSS uses KiB.

| Workload | Local processing CAP-1A → CAP-1B | Caller allocations before → after | RSS before → after |
|---|---:|---:|---:|
| Document, 10k occurrences | 8.892 → 3.327 s | 2.590 → 0.713 GB | 393,288 → 381,780 KiB |
| Processed import, 10k rows | 4.020 → 3.315 s | 1.273 → 0.598 GB | 397,224 → 395,300 KiB |
| Document, 100k occurrences | 84.899 → 27.602 s | 25.390 → 6.538 GB | 794,884 → 806,008 KiB |
| Processed import, 100k rows | 32.027 → 26.253 s | 12.386 → 5.656 GB | 390,168 → 378,952 KiB |

Every pair has equal semantic signatures and byte-equal configuration. Resource
reuse gives a measured time/allocation benefit without a paired end-to-end
regression. Document peak heap/RSS does not improve; materialized preparation
and downstream projection/export state remain later-stage concerns.

## Exact-driver access paths and phase scaling

The packaged matcher uses SQLite 3.53.2 / sqlite-jdbc 3.53.2.0. A private
mechanism fixture holds 1k, 10k or 100k unrelated aliases. Quantum-1 progress
callbacks cover the actual planner call, including setup/disposal. They are a
fine-grained diagnostic work screen, not a primary latency sample.

| Request shape | CAP-1A callbacks at each size | CAP-1B callbacks at each size |
|---|---:|---:|
| Singleton hit | 216 | 81 |
| Singleton miss | 228 | 66 |
| Two-key request | 287 | 301 |
| Several requests including empty/missing | 351 | 363 |

All sizes remain below 10,000 callbacks and satisfy
`W100k <= 2 * W1k + 5,000`. General-call work is slightly higher because resource
ownership includes deterministic disposal; reuse inside mutation transactions
is qualified by the primary allocation results. Actual plans constrain all four
alias-key terms and canonical primary-key lookup, with requests driving the
staged join. Read-only plan cross-checks also pass on coherent public-path 10k
import and 100k stand states; their VM counters are not presented as business
workload costs. The 10k state contains imported masks; the 100k state contains
all five artifacts. Both have schema version 12.

Five separate instrumented/JFR document forks per size measure actual
canonical ownership acquisition through commit. Sum of hold durations has a
median of 1.970 s at 10k and 22.660 s at 100k. Final rows grow from 27,500 to
275,000; time growth is 11.50x for 10x rows, passing the 15x G1A scaling screen.
This instrumented phase measure is separate from primary elapsed time.
Maximum single transaction hold reaches 9.629 s at 100k, above the adopted 5 s
ownership budget. Contended control-operation latency is not qualified here.

## Provisioned stand-policy SMB check

An isolated production Boot daemon runs the actual stand policy with private
SQLite/cwd, its own SMB namespace and two-CPU affinity. A bootable JAR is pinned
against all frozen production class/library bytes and root Spring factories.
The service's operator-provided configuration, encryption and cadence are
preserved. SMB credentials are read privately and are excluded from evidence.
There is no private cgroup CPU/memory quota: affinity is not cgroup admission.

The small physical URL/IP fixture passes all five final-field/key oracles,
including scheme, port, path, query, fragment, defang, domain/subdomain and hash
cases. Five physical AS_IS imports preserve case, URL paths and original
source values, deduplicate correctly and finish with SUCCEEDED deliveries and
COMMITTED canonical receipts. The IP case distinguishes score 20 from 21,
keeps the first duplicate source/description and preserves sparse requested
slots 900001 and 900003; removed duplicate slot 900002 is not reassigned.

The original physical 100k seed-43 fixture contains 89,917 unique occurrences,
10,083 duplicates, 17,516 defanged values and 400 BIB sections. Complete database
and published-CSV oracles pass for masks 14,986; IP 14,987; hashes 44,958;
blacklist 29,973; aggregate 89,917. All three publication profiles finish
SUCCEEDED with matching covered revisions, manifest/CSV hashes and markers.

The complete SMB handoff-to-all-publications/readback cycle takes **31.867 s**.
Pipeline stages sum to about **23.632 s**, including **17.714 s** in write,
which includes mutable projection after commit. Peak RSS is **717,572 KiB**
(about 701 MiB), above the adopted 512 MiB target. One cold stand cycle does not
establish a service median, maximum or million-row acceptance.

Concurrent use of the old service and qualification daemon exhausted the
local SMB server connection allowance during early attempts. Passing runs
pause the idle installed service for qualification and restart it in `finally`.
The successful runs have all health components UP, bounded worker termination
and removal of only the harness-created remote namespace. Earlier boot,
source-key oracle and connection-capacity failures remain recorded locally.
There is no controlled speedup ratio to the historical 36m55s stalled run.

## Local evidence retention

The initial harness retained every per-fork SQLite store and repeated CSV,
accumulating about 11 GiB in CAP workspaces. This was an execution defect.
All three tools now discard owned databases, WAL/SHM, stage snapshots and
projections after semantic checks on success and failure. Inputs, configuration,
logs and measurements remain. Complete oracle signatures are gzip-compressed;
equal signatures share one archived file through hard links. Diagnostic/JFR
files remain only in explicitly requested diagnostic runs. The phase harness
retains digests/counts instead of every decoded large signature in memory.

Full generated-state retention requires `--retain-state`. Stand state manifests
normally record facts without copying databases; selected coherent backups are
compressed. Cleanup refuses unowned directories, skips symlinks and protects
source/Git/frozen-runtime directories. A new processing fork or stand run needs
at least 1 GiB free. Forced OS termination cannot execute `finally`; incomplete
workspaces retain failure evidence and require explicit cleanup.

Obsolete generated state in 75 private benchmark workspaces was removed and
full signatures compressed and duplicate archives shared, releasing about
15.9 GiB. One compressed 100k stand
canonical/service pair and one 10k processed-import pair remain for follow-up;
production/stand databases and Git worktrees are preserved. Frozen production
runtime identities match primary evidence. Twenty-eight offline harness
contracts and real 40-document/40-import JVM forks pass; completed small forks
retain roughly 60/80 KiB of evidence with no SQLite state.

## Gate disposition and remaining capacity work

| Gate | Result |
|---|---|
| G0 | Independent all-five document/AS_IS import oracles, pinned empty/public-path states, driver/executable identities, phase/ownership anchors and bounded cleanup qualified |
| G1A functional/complexity | JDBC semantic/rollback tests and exact-driver full-key work/plan screens pass |
| G1A service/scaling | All five live publications and full-output checks pass; canonical median growth 11.50x at 10x rows passes |
| G1B | Resource reuse qualified by five-pair time/allocation benefit; general matching semantics preserved |
| Whole-service resource acceptance | Open: 100k document RSS exceeds 512 MiB and maximum writer hold exceeds 5 s; no 1m, cgroup/live-heap or contention acceptance claimed |

CAP-2 owns the mutable-projection install/ack race. CAP-3 owns section attribution
and diagnostic construction. CAP-4 owns bounded preparation; CAP-5 owns writer
admission, fair scheduling and transaction occupancy. CAP-6 owns repeated
whole-service/SMB/million-row acceptance. This change addresses the demonstrated
lookup multiplier and repeated resource setup without claiming those remaining
gates are closed.
