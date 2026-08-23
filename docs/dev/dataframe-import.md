# Managed dataframe import

Managed dataframe import accepts a pinned CSV delivery through an exact,
versioned source contract and prepares a sealed disk-backed stage for later
atomic promotion into canonical truth. The implemented foundation does not yet
watch import directories, claim source files, or mutate canonical records;
those runtime boundaries are deliberately kept outside recognition and staging.

## Runtime flow

The currently executable application boundary starts with an already immutable
snapshot:

```text
pinned snapshot
  -> exact-one contract recognition
  -> strict streaming decode and row mapping
  -> per-delivery SQLite workspace
  -> deterministic duplicate finalization
  -> checkpoint, integrity check, atomic seal and SHA-256 pin
  -> verified read-only stage
```

The service database independently stores delivery occurrence identity, one
global sequence, compare-and-set state transitions, retry timing and immutable
snapshot/contract/stage checkpoints. No worker currently connects source
detection to this state machine; `ioc.dataframe-import.enabled` therefore does
not activate operator intake yet.

`as-is` mapping is implemented: only declared transforms run and cells retain
`ABSENT`, explicit `NULL`, or `VALUE`. A `processed` contract fails closed until
the separate framework-free preparation strategy is connected; it is never
silently treated as `as-is`.

## Boundaries

- `ioc-application` owns contracts, recognition, tri-state mapping, staging
  orchestration and storage-neutral ports. It contains no CSV, JDBC or Spring
  dependency.
- `adapter-csv` owns strict charset decoding, dialect construction, resource
  limits and Commons CSV streaming. It also exposes the existing CSV transform
  family through the import transform port.
- `adapter-store-jdbc` owns service schema v9 and the private per-delivery
  SQLite workspace schema. Workspace files are rebuildable evidence, not
  canonical truth.
- `ioc-application-tck` owns the reusable delivery-ledger state-machine
  contract. The JDBC implementation runs that contract against SQLite.
- `ioc-app` currently compiles the disabled-by-default contract catalog. P5
  will own runtime assembly and source intake; P6 will own canonical promotion.

## Invariants

1. **A delivery is an occurrence, not a content identity.** A global monotonic
   sequence orders occurrences; equal bytes may be admitted again under a new
   delivery ID.
2. **Recognition is exact-one.** Only source-allowlisted contracts participate.
   File names, catalog order and best-score heuristics never break a tie.
3. **Rows stream off heap.** The parser invokes a synchronous callback and the
   workspace commits bounded batches. CSV field/record/row/column limits are
   checked before unbounded staging growth.
4. **One physical row is one logical atomic unit.** A mapping error in any
   primary or related branch rejects every branch of that row and stores only
   row number, artifact and stable diagnostic code.
5. **Duplicate policy is explicit.** `coalesce` is commutative: `ABSENT` may
   combine with one concrete state, while conflicting values, null/value pairs
   and requested slots reject one logical group. `keep-first` intentionally
   retains the smallest physical row number.
6. **A sealed stage is immutable evidence.** Contract and snapshot pins, plan
   hash, counts, SQLite integrity and file digest must agree before a reader may
   consume it. Canonical promotion must open it read-only.
7. **Ledger and workspace have different authority.** The service ledger owns
   orchestration progress; the workspace owns rebuildable bulk data; only the
   future dataframe transaction and its receipt may establish business truth.

## Failures and recovery

| Situation | Behavior | Recovery authority |
|---|---|---|
| malformed bytes, dialect/header mismatch, zero/ambiguous recognition or hard parser limit | critical delivery failure before a sealed stage | pinned snapshot plus ledger state |
| invalid value, formula-dangerous text or missing row key | safe row rejection; valid rows may remain staged | contract row-failure policy is applied before promotion |
| crash during a batched workspace build | `.building.db` is never accepted as sealed | explicit rebuild from the pinned snapshot |
| digest, metadata or integrity mismatch | fail closed; stage is not readable for promotion | ledger checkpoint plus pinned snapshot/contract |
| shared workspace reaches pause/high watermark | new admission pauses; owned work remains durable | aggregate capacity state and later reconcile |
| crash at any pre-promotion ledger state | CAS state and checkpoint survive restart | `findRecoverable` ordered by global sequence |

The ledger has no sleeps, executor-order authority, or in-memory retry truth.
Events are unnecessary at this boundary: later source and drain events remain
latency hints over the durable head query.

## How to extend

- A new structural CSV contract belongs in the compiled catalog and must use
  registered aliases/transforms and named canonical key definitions.
- A new parser or wire format implements the application reader port in its own
  integration-family adapter; it must retain strict decoding, streaming and the
  same resource bounds.
- P5 source ownership must yield an immutable snapshot before calling staging
  and must not bypass global ledger ordering.
- P6 promotion must consume only a verified sealed stage, apply row-failure
  policy before mutation and commit all accepted artifact branches plus its
  dataframe receipt in one transaction.

## Sources of truth

- Decision and invariants: [ADR-0024](../ADR/0024-managed-dataframe-import.md).
- Application contracts: `application.dataframeimport` and
  `application.port.out.dataframeimport` in `ioc-application`.
- CSV boundary: `adapter-csv` package README and reader contract tests.
- Ledger/workspace: `adapter-store-jdbc` package README, import ledger TCK and
  workspace integration tests.
- Runtime configuration catalog: `IocProperties.DataframeImport` and
  `DataframeImportConfiguration`.

## When to update this document

- source ownership or the immutable-snapshot boundary becomes executable;
- processed-mode preparation becomes available;
- the sealed-stage schema or recovery authority changes;
- canonical promotion, receipt or row-failure checkpoint becomes executable;
- operator-visible configuration, status, health or reporting is added.

## Related documents

- [storage.md](storage.md) — canonical/service database roles and schema
  evolution.
- [processing.md](processing.md) — ordinary processing and artifact
  preparation services reused by processed mode.
- [ingestion.md](ingestion.md) — existing whole-file ingest ownership and
  recovery boundaries.
- [event-coordination.md](event-coordination.md) — events-as-hints doctrine.
- [ADR-0015](../ADR/0015-retire-legacy-csv-lookup-storage.md) — legacy CSV
  lookup/seed retirement preserved by managed import.
