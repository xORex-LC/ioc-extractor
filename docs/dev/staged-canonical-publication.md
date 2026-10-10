# Staged canonical publication

This is an isolated SQLite adapter candidate, composed explicitly by
`JdbcStagedCanonicalBackend`. The default application still uses native canonical
transactions. The candidate runs the same native mutation kernel in a private
workspace, transfers typed effects in short transactions, then publishes their
visibility and canonical receipt together. Activation and existing-data migration
require separate operational qualification.

## Runtime flow

```text
pinned command -> selective committed snapshot -> private native mutation
  -> bounded hidden chunks -> fence/epoch/deadline validation -> atomic publication
  -> canonical receipt -> service-ledger finalization
```

The shared document workspace factory admits canonical preparation against its
memory, cache and disk budgets. Admission is nonblocking when another workspace
would exhaust the budget. Hydration selects affected keys, aliases and dependent
graphs; slot reconciliation additionally streams minimal lifecycle membership
headers. It does not copy or diff the whole database.

Capture triggers run only on private native tables. They preserve initial and
final typed values, NULL and cascaded tombstones. An explicit mutation journal
preserves renewals even when timestamps have not changed. Two passes over one
sealed snapshot describe and transfer at most 256 effects / 1 MiB per chunk.

## Boundaries

All physical mechanics remain in `adapter-store-jdbc`. Application ports,
canonical identity, rank, merge, mutation and sparse-slot policies are reused.
The dataframe database owns canonical visibility, ID reservations, publication
fences, epochs and receipts. The service database continues to own ordered
delivery and retry coordination. There is no new queue or mutation-policy engine.

## Invariants

1. Only versions whose unit and fence are committed participate in reads. The
   latest committed address wins, including tombstones. Matching, active rows,
   projections, snapshots, TTL, history, slots and receipt recovery share this
   predicate. Existing readers keep one SQLite snapshot across publication.
2. Ordinary confirmation publishes per artifact; import publishes all artifacts
   and its receipt together. Hidden chunks never advance visible revisions or
   generations. Private preparation does not hold canonical writer admission.
3. Fresh safe UTC at publication is confirmation authority. An expired selected
   lifecycle, including equality at its deadline, requires a rebuild with new
   non-reusable IDs. Rebuild preserves the admitted command, rank and policy;
   reserved ranges remain burned. Three attempts bound one execution.
4. Immutable pins reject configuration or command drift. Initialization refuses
   populated native tables; reopening requires the same configuration fingerprint.
   Native writers must never write into the candidate's read-only views.

## Failure and recovery

| Boundary | Behavior |
|---|---|
| Private mutation, quota or hidden transfer failure | Roll back private work; cancel hidden effects and persist ownership failure; no partial visibility |
| Changed dependency or expired base | Rebuild from a fresh committed snapshot with the same pins, within the execution budget |
| Stale logical owner | Reject its chunks and publication |
| Publication committed before delivery finalization | Recover from the canonical receipt without reading the input stage |
| Receipt absent or incompatible pins | Fail closed; do not reinterpret the admitted delivery |

Controls reuse native expiry, history, projection, receipt-retention and slot
operations in private selective snapshots. Their epochs invalidate stale
promotion work. Their fixed cycle time remains literal; promotion timestamps and
renewal deadlines use publication UTC.

## Extension and executable sources

Add a native family to the persisted catalog, its selective hydration and typed
capture, then compare every transported table with the native oracle. Extend
ports through the existing application contracts rather than exposing private
workspace connections.

- [Candidate composition](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcStagedCanonicalBackend.java).
- [Native import oracle and TCK](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java).
- [Physical publication checks](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/SqlitePublicationExperimentIT.java).

Update this document when visibility, publication time, resource ownership,
recovery or activation boundaries change.

## Related documents

- Proposed decision: [ADR 0041](../ADR/0041-sqlite-staged-publication-experiment.md).
- Native storage: [storage](storage.md).
- Module reference: [adapter-store-jdbc](../../adapters/adapter-store-jdbc/README.md).
