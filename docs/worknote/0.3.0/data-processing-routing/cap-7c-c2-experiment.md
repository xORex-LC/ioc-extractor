# CAP-7C C2: physical SQLite publication experiment

Status: physical primitive implemented in test scope; C2 canonical-port and
mutation-kernel qualification remains open. C3 production integration has not
started. This is neither a capacity acceptance nor an activated schema.

## Implementation

`SqlitePublicationExperiment` and `SqlitePublicationExperimentIT` live in the
existing JDBC adapter's test source set. They use the pinned sqlite-jdbc
3.53.2.0 driver, the production `SqliteDataSourceFactory`/PRAGMA policy, a
temporary file-backed SQLite database and `JdbcWriterAdmission`. Experimental
tables are explicitly created only in that database. Its `user_version` remains
zero; dataframe v12, service v14, bootstrap wiring and production writers are
unchanged. No dependency, Maven module or PostgreSQL path was added.

The primitive has no matching, merging, classification or slot-allocation
business logic. Addresses and payloads are deliberately opaque. The experiment
does **not** substitute these payloads for typed canonical storage.

- A logical unit has immutable identity/pins, an attempt fence, declared chunk
  and contribution counts, a minimum permitted deadline and a sealed state.
- Each attempt receives a durable monotonic stamp. Hidden chunks fold pending
  contributions and record an ordered SHA-256 journal with length-framed UTF-8
  values; `NULL` and empty strings have distinct digest encodings.
- Chunk replay checks its original digest and does not apply it again. Replaying
  an older chunk cannot overwrite a value produced by a later one. Changed
  replay, gaps, undeclared scopes and exceeded contribution/byte bounds fail
  and roll back the entire chunk.
- A chunk is limited to 256 contributions and 1 MiB of framed values in this
  probe. These are experiment bounds, not new operator configuration or an
  accepted production capacity.
- A rebuild advances fence/stamp, keeps business pins and can declare a changed
  contribution manifest. Facts from the previous fence remain invisible.
- Publication checks SEALED, scope epochs and strict deadline inequality,
  advances touched scope epochs, allocates publication sequence and writes its
  receipt/header in one caller-owned transaction. An empty unit may publish an
  outcome receipt. A cancelled unit cannot publish.
- Readers select only committed current-fence versions. Own pending reads use
  a separate connection over one pinned committed base; a pending tombstone
  overrides the base rather than revealing its old value.

The receipt here is an **opaque experiment header**, not `import_commit` or a
complete `CanonicalImportResult`. Reopening it without preparation input proves
physical persistence/idempotency only. The real canonical receipt and delivery
finalization remain part of C2/C3 port qualification.

## Findings incorporated into proposed ADR 0041

First, ordering a key's versions by joined publication sequence requires sorting
its retained history. The candidate now indexes `(family, scope, key, attempt
stamp DESC)`. Scope epochs must match at attempt creation/rebuild and again at
publication; future epoch pins are rejected. If another attempt publishes the
same scope first, the older attempt must rebuild with a newer stamp. This makes
index order agree with commit order for a shared address without an N-fact
sequence update at publication. Final publication sequence still records the
actual global publication order.

Second, multiple accepted inputs can contribute to one source summary or other
pending fact. Disallowing repeated addresses would force another mutation
accumulator ahead of the existing kernel. Instead, only the chunk journal and
committed versions are immutable; pending contributions fold until seal.
Retrying a journalled chunk never applies its contribution twice.

The indexed lookup excludes artifact-wide scans and a temporary ORDER BY
B-tree on the retained exact-driver query plan. This does not bound the number
of cancelled/pending versions it may skip, nor qualify active/nearest-deadline
queries. Retention and typed lifecycle indexes remain explicit gates.

## Deterministic checks

The new Failsafe suite covers:

1. Pending updates retain old committed values; pending inserts and receipt
   headers are absent. Row/alias/source/origin/revision/slot/warning families
   become visible together. A pinned reader retains its old snapshot.
2. Changed business pins and stale attempts cannot rebuild, append, publish or
   cancel the current attempt.
3. Incomplete manifests, gaps, changed chunk replay, undeclared scopes and
   excess declared/byte contributions roll back.
4. Equality at the minimum deadline rejects publication; semantic epoch changes
   require rebuilding. Failed publication does not consume a committed sequence.
5. An earlier-created unit rebuilt after a later publication gets the newest
   visible value; tombstones cannot reveal historical values.
6. Future/stale initial epoch pins are rejected.
7. Three contributions to one source address fold; replaying the first after
   the second preserves the second, and publication retains the third.
8. Slot-namespace changes invalidate a plan; an unrelated namespace does not.
9. Sixty-four retained committed versions plus abandoned pending state preserve
   latest-version/tombstone behavior.
10. Own pending updates, clears and inherited values overlay one pinned base.
11. Injected failure after the visibility/header writes rolls back; reopening
    the database permits idempotent header replay without preparation input.
12. Empty outcome publication works; cancelled facts remain invisible.
13. An owned worker streams 4,096 facts in sixteen chunks. A timed CONTROL
    admission completes between the first and second chunks. Query-plan checks
    require indexed fact search with no full fact scan or ORDER BY sort.

Run through the normal facade:

```bash
make test-one MODULE=adapters/adapter-store-jdbc TEST=SqlitePublicationExperimentIT
```

The focused run executed 13 tests with zero failures/errors/skips; Failsafe
reported 1.210 seconds for the suite. This is functional probe execution time,
not a throughput or writer-occupancy result. Final repository evidence comes
from `make verify`, `make pmd-analysis`, `make docs` and exact-HEAD `make context`.

The suite has a containing timeout, timed latches/futures, unconditional worker
release/cancellation and an asserted bounded shutdown. The same change reviews
the source inventory: one additional deterministic integration class,
222 fast / 78 integration / 5 external / 295 deterministic classes. No coverage
floor, analyzer exclusion or production baseline is relaxed.

## Remaining C2 gate before C3

The physical probe alone does not meet the C2 gate. Next work must connect typed
facts to the real shared mutation kernel and existing canonical ports, covering
ordinary confirmation and complete fan-out import. It must exercise real row,
alias, registration-origin, provenance, slot and receipt outcomes against the
corrected current adapter's independent oracles.

In particular, qualify safe-clock ownership and indexed deadline anchors;
do not use the probe's caller-supplied millisecond value as production time
authority. Qualify durable logical promotion ownership, the three-attempt
rebuild policy, cancellation/retention and finite progress when expiry or slot
work invalidates preparation. Coarse scope epochs are conservative, not evidence
of adequate mixed-load progress. A passed 4,096-fact probe is not a 100k/1m
whole-service measurement, a five-second writer qualification, a process-kill
qualification, a service-ledger recovery test or a migration/rollback test.

C3–C6 and G6 therefore remain open. Installed service state is unchanged.
