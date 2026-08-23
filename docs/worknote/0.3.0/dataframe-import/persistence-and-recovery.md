---
title: "DATA-IMPORT-01 — persistence and recovery design"
version: "0.3.0"
status: "Approved architecture baseline"
document_type: "Persistence and recovery design"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — persistence and recovery design

## 1. Authority split

Three durable stores have different authority and must not be collapsed:

| Store | Authority |
|---|---|
| private snapshot and sealed staging files | exact input bytes and reproducible prepared write set |
| service DB | claim occurrence, global order, retry/recovery and operational state |
| dataframe DB | canonical/lifecycle data, active match aliases, slot assignments, public revisions and proof that promotion committed |

The service and dataframe databases are separate WAL files. There is no
cross-database transaction. Correctness therefore uses a forward-only saga with
an `import_commit` claim check in the same dataframe transaction as business
changes.

## 2. Service schema v9

Proposed logical tables are shown below; migration SQL and naming are finalized
during implementation.

### 2.1 `import_delivery`

```sql
CREATE TABLE import_delivery (
  sequence_no          INTEGER PRIMARY KEY AUTOINCREMENT,
  delivery_id          TEXT NOT NULL UNIQUE,
  source_id            TEXT NOT NULL,
  candidate_token      TEXT NOT NULL,
  replay_of             TEXT,
  state                 TEXT NOT NULL,
  terminal_outcome      TEXT,
  version               INTEGER NOT NULL DEFAULT 0,
  snapshot_locator      TEXT,
  snapshot_sha256       TEXT,
  snapshot_size         INTEGER,
  stage_locator         TEXT,
  stage_sha256          TEXT,
  contract_id           TEXT,
  contract_version      INTEGER,
  contract_fingerprint  TEXT,
  attempt_count         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at_ms    INTEGER,
  last_error_code       TEXT,
  accepted_rows         INTEGER,
  rejected_rows         INTEGER,
  public_mutations      INTEGER,
  created_at_ms         INTEGER NOT NULL,
  updated_at_ms         INTEGER NOT NULL,
  terminal_at_ms        INTEGER,
  purge_after_ms        INTEGER
);
```

Required indexes:

- unique active `(source_id, candidate_token)` occurrence guard;
- minimum nonterminal `sequence_no` head lookup;
- `(state, next_attempt_at_ms, sequence_no)` due work;
- source/status/age health summaries;
- terminal purge deadline.

Locators are private internal values and never returned by status/health.
The first transaction that promotes a stable candidate to `DETECTED` is the
durable claim reservation and allocates `sequence_no`. Transport ownership may
then retry in `CLAIMING` without changing that sequence. Bounded terminal
quarantine is the only way an unclaimable head releases later deliveries.

### 2.2 Transition audit

`import_delivery_transition` is append-only compact evidence:

```text
delivery_id, ordinal, from_state, to_state, safe_code, occurred_at_ms
```

It does not store raw exception messages, paths, digests or IOC values. The
current row remains the recovery authority; the transition table supports
diagnosis and retention-bounded audit.

### 2.3 CAS state changes

Every worker transition is compare-and-set:

```sql
UPDATE import_delivery
SET state = :next, version = version + 1, updated_at_ms = :now
WHERE delivery_id = :id AND state = :expected AND version = :version;
```

Zero updated rows means another recovery/drain decision won and the caller
reloads state. A capacity pause does not increment `attempt_count`; an actual
failed processing attempt does.

## 3. State machine

```text
DETECTED
  -> CLAIMING
  -> CLAIMED
  -> SNAPSHOT_PINNED
  -> CONTRACT_PINNED
  -> STAGING
  -> STAGED
  -> PROMOTING
  -> CANONICAL_COMMITTED
  -> FINALIZING
  -> TERMINAL
```

`TERMINAL` carries exactly one outcome:

- `SUCCEEDED` — no rejected logical rows;
- `COMPLETED_WITH_ERRORS` — accepted set committed and some rows rejected;
- `REJECTED` — no canonical mutations were committed.

`PROMOTING` is intentionally recoverable by querying the dataframe receipt. A
process must never infer rollback merely because the service row has not moved
past it.

## 4. Per-delivery staging database

Staging is adapter-owned scratch state. It is not business truth and may be
rebuilt from the pinned snapshot before promotion.

### 4.1 Core metadata

```sql
stage_meta(
  delivery_id PRIMARY KEY,
  schema_version,
  snapshot_sha256,
  snapshot_size,
  contract_id,
  contract_version,
  contract_fingerprint,
  source_row_count,
  logical_row_count,
  accepted_count,
  rejected_count,
  plan_hash,
  sealed_at_ms
)
```

### 4.2 Rows and branches

```text
stage_input_row
  source row number, logical group, validation status, safe error count

stage_branch
  logical group, branch ordinal, artifact, primary flag, requested slot,
  proposed record key, status

stage_cell
  branch ID, target column, presence (0/1), nullable value

stage_match_key
  branch ID, definition ID, canonical key hash and collision-check material

stage_row_error
  logical group, row number, branch/artifact, diagnostic code, safe detail
```

Tri-state encoding is:

```text
presence=0                         ABSENT
presence=1 and value IS NULL       NULL
presence=1 and value IS NOT NULL   VALUE
```

Artifact-specific wide temporary tables may replace the normalized cell table
where benchmarks prove a material query-plan benefit. That is an adapter detail
only if the same semantic invariants and generated schema validation remain.

### 4.3 Staging durability

- streaming parser inserts bounded batches;
- scratch transactions may be chunked;
- hard limits are checked before and during growth;
- after preparation, indexes and counts are finalized;
- the connection is closed/checkpointed, file and directory are forced, and a
  stage digest is recorded;
- promotion opens the sealed database read-only and treats mutation/digest
  mismatch as fatal consistency failure;
- cleanup occurs only after terminal publication or proven safe rebuild.

## 5. Dataframe schema v7

### 5.1 Match definitions and aliases

```sql
canonical_match_definition(
  artifact,
  definition_id,
  definition_fingerprint,
  identity_epoch,
  activated_at_ms,
  PRIMARY KEY(artifact, definition_id)
)

canonical_match_alias(
  artifact,
  definition_id,
  key_hash,
  key_canonical,
  lifecycle_id,
  canonical_row_id,
  PRIMARY KEY(artifact, definition_id, key_hash, key_canonical, lifecycle_id)
)
```

The lookup index starts with `(artifact, definition_id, key_hash)`. Equality is
confirmed using canonical material, not a digest alone. Active status is always
verified by joining the artifact table at the transaction's `asOf`; aliases do
not cache truth about expiry.

The identity migration:

1. closes intake and obtains lifecycle write ownership;
2. registers the new identity epoch and definitions;
3. computes proposed record keys and aliases for all canonical lifecycles in a
   shadow table;
4. detects row-key or definition collisions;
5. aborts without mutation on any collision;
6. replaces internal row keys and publishes aliases in one dataframe
   transaction;
7. runs invariant and active-query plan checks before readiness.

Canonical IDs, lifecycle IDs, public values, revisions and export-slot
assignments do not change.

### 5.2 Import promotion receipt

```sql
CREATE TABLE import_commit (
  delivery_id           TEXT PRIMARY KEY,
  sequence_no           INTEGER NOT NULL UNIQUE,
  observation_id        TEXT NOT NULL UNIQUE,
  snapshot_sha256       TEXT NOT NULL,
  snapshot_size         INTEGER NOT NULL,
  contract_id           TEXT NOT NULL,
  contract_version      INTEGER NOT NULL,
  contract_fingerprint  TEXT NOT NULL,
  outcome               TEXT NOT NULL,
  effective_as_of_ms    INTEGER NOT NULL,
  accepted_rows         INTEGER NOT NULL,
  rejected_rows         INTEGER NOT NULL,
  public_mutations      INTEGER NOT NULL,
  committed_at_ms       INTEGER NOT NULL,
  purge_after_ms        INTEGER NOT NULL
);
```

`import_row_rejection` may retain safe codes needed to reconstruct a report
until finalization. It is bounded and cascades with receipt retention. Complete
row values remain only in protected files/canonical tables.

The receipt key is the delivery occurrence, not file bytes. A byte-identical
later delivery receives another ID and may renew TTL.

### 5.3 Slot free ranges

Replace enumerated `export_slot_free` rows with:

```sql
export_slot_free_range(
  profile,
  artifact,
  range_start CHECK(range_start > 0),
  range_end CHECK(range_end >= range_start),
  released_at_ms,
  PRIMARY KEY(profile, artifact, range_start)
)
```

The migration coalesces existing adjacent free rows into ranges without
changing assignments or `next_slot`. Operations split/merge ranges in the same
transaction. A request for slot `1_000_000_000` creates at most one preceding
range, not one billion rows.

### 5.4 Observation and revision facts

The existing observation/receipt vocabulary is extended to distinguish:

- inserted lifecycle;
- public update;
- explicit public clear;
- TTL confirmation only;
- normalized no-op;
- rejected logical row.

One delivery advances each affected artifact revision/projection generation at
most once. Only insert/update/clear changes public mutation state. TTL/no-op
facts still support idempotency and audit but do not nudge immutable export.

## 6. Connection-scoped mutation engine

Refactor `JdbcCanonicalLifecycleWriter` into:

```text
JdbcCanonicalMutationEngine
  resolve/validate active matches
  archive expired same-key lifecycle
  insert/update/confirm canonical row
  maintain aliases and provenance
  update observation and lifecycle facts
  aggregate artifact mutation outcome
```

The engine receives an existing `Connection`, sampled time and pre-reserved ID
ranges. The ordinary per-artifact writer and the import writer call it through
different orchestration contracts. This preserves one lifecycle implementation
without allowing CSV/staging types into the ordinary pipeline.

Bulk import operations use set-based temporary/result tables and batched SQL;
the engine's single-row form remains suitable for ordinary small batches. SQL
plans must use staging scans plus indexed canonical/alias lookup, never a scan
of the whole canonical table for each staged row.

## 7. Promotion transaction in detail

### 7.1 Before write ownership

1. select the minimum nonterminal sequence and ensure it is due;
2. verify pinned contract, snapshot and sealed stage fingerprints;
3. if `import_commit` exists, skip mutation and enter forward finalization;
4. compute worst-case new canonical/lifecycle counts;
5. reserve ID ranges using independent non-reusable allocators;
6. open sealed staging read-only.

Reservation holes after validation conflict or crash are accepted by the
existing monotonic identity contract.

### 7.2 Under one dataframe transaction

1. obtain the shared lifecycle write-ownership row (`BEGIN IMMEDIATE`
   semantics through the existing transaction abstraction);
2. sample one UTC `asOf`;
3. recheck absence of a receipt;
4. build candidate matches from active aliases;
5. mark zero/exact-one/multi results;
6. validate stable identifying values and field merge;
7. propagate branch failure to logical-row status;
8. enforce delivery failure policy;
9. archive expired collisions before new inserts;
10. apply accepted canonical insert/update/clear/confirmation and provenance;
11. maintain aliases from final values;
12. reconcile survivor, requested and fallback slots using free ranges;
13. aggregate mutations and update revision/projection/deadline state;
14. persist receipt and safe rejection evidence;
15. commit.

No source file, report, service DB or staging write occurs inside this
transaction.

## 8. Crash recovery matrix

| Crash point | Durable evidence | Recovery action |
|---|---|---|
| before ownership claim | producer object only | next full detection retries |
| after source rename, before ledger claim complete | object in service namespace | source reconcile adopts or quarantines by candidate token |
| during local materialization | private `.part` | verify ledger; resume safely or delete incomplete part and recopy claimed object |
| after snapshot publish, before ledger update | immutable file plus claim metadata | reconcile digest/size and advance `SNAPSHOT_PINNED` |
| during staging | snapshot plus incomplete stage | delete/rebuild stage; never touch canonical data |
| after stage seal, before ledger update | sealed file and metadata | verify digest and adopt `STAGED` |
| during promotion before commit | no receipt | SQLite rollback; retry same staged delivery |
| after dataframe commit, before service update | `import_commit` exists | never reapply; advance to `CANONICAL_COMMITTED` |
| during projection | durable generation behind | convergence backstop retries |
| during report write | committed receipt and report temp | regenerate same safe report and atomically publish |
| after terminal directory publish, before ledger terminal | terminal unit exists | verify unit and mark terminal |
| remote archive/quarantine delayed | local terminal unit plus pending disposition | retry remote action without business mutation |

Unknown contradictions, digest mismatch, duplicate sequence receipt or missing
snapshot after an uncommitted claim are `DOWN`/fail-closed conditions, not
automatic guesses.

## 9. Retention

Nonterminal snapshots, stages and receipts are pinned. Terminal unit and receipt
retention use the existing `max-age|max-count|delete|archive` policy vocabulary:

- `SUCCEEDED`: 30-day default;
- `COMPLETED_WITH_ERRORS` and `REJECTED`: 90-day default.

Cleanup is bounded and independently scheduled. A receipt is not removed while
service/finalization state could still need it. Source and report are selected
as one delivery directory so retention cannot orphan one half.

## 10. Transaction and filesystem caveats

- SQLite has one writer; pool size is not write parallelism.
- Writer admission improves fairness but cannot replace database ownership.
- The attached staging DB is read-only because WAL transactions spanning
  multiple written database files do not provide the required crash-atomic
  guarantee.
- A read-only immutable SQLite URI is used only after the stage is physically
  sealed and never mutated again.
- Filesystem `rename` is not assumed atomic across filesystems. Strict local
  mode requires source/processing directories on the same supporting
  filesystem; any copy-based mode is a separately explicit verified protocol.
