# CAP-7C C3: native kernel integration

Status: started, transactional private mutation journal implemented. The complete
versioned runtime adapter is not integrated or activated. C2's finite typed
experiment is closed; C3, C4–C6 and G6 remain open.

## First integration slice (2026-10-10)

`JdbcCanonicalImportWriter` and `JdbcCanonicalLifecycleWriter` share an internal
`JdbcCanonicalPreparationContext`: dataframe-owned ID reservations and a
transactional effect-sink factory. Their public constructors use the existing
canonical datasource and a no-op sink. No bootstrap switch, schema migration,
dependency or alternative mutation engine is introduced.

The factory receives the native mutation transaction's borrowed connection.
`JdbcPrivateMutationJournal` is opened only in the private preparation database.
It records actual artifact, canonical-row and lifecycle identities plus the
explicit renewal decision from the kernel/import plan. Recording failure aborts
the native transaction; journal DDL and entries roll back together with canonical
facts in that private database. Independently reserved authoritative ID ranges
remain burned.

The journal pins one unit and its supplied preparation fingerprint. Changed
unit/pins or a different lifecycle for an existing row fail closed. Repeated
contributions fold renewal with logical OR; a later non-renewal cannot erase an
earlier renewal. An indexed ordinal cursor streams at most 256 entries per page.
The caller owns the sealed read snapshot, private workspace, quota and disposal;
the journal closes its prepared statement, never the borrowed connection.

A renewal can leave every native row timestamp unchanged when preparation uses
the same safe UTC as the previous confirmation. Comparing old/new timestamps
would lose it. The explicit journal flag survives this case and allows the typed
experiment to publish the native outcome at fresh publication UTC. The journal
is not a canonical receipt, publication header, complete version payload or
replacement for epoch/fence validation.

## Executable checks

The existing Failsafe suites retain their discovery/tags and add these checks:

- `privateJournalPreservesSameInstantRenewalThroughTypedPublication`: real import
  planner/kernel, unchanged native row timestamps, persisted explicit renewal,
  corrected native-writer oracle across all transported tables, fresh-UTC TTL and
  receipt-only replay after deleting the input stage.
- `privatePreparationBurnsReservationsOnlyInTheAuthoritativeDatabase`: injected
  BEFORE_COMMIT failure rolls back private facts and journal, keeps authoritative
  allocator gaps, and retries with fresh identities.
- `ordinaryPerArtifactPublicationCompletesAndReplaysTheRealTypedConfirmationReceipt`:
  the first native ordinary artifact records its real outcome transactionally;
  the existing two-artifact receipt/publication oracle remains intact.
- `privateMutationJournalFoldsRenewalsAndStreamsBoundedIndexedPagesWithPinnedIdentity`:
  257 entries, a 256-entry page plus tail/end, folded renewal, unchanged pins,
  rejected identity changes and invalid bounds.
- `privateMutationJournalRollsBackEffectsAndRejectsCrossThreadResourceUse`: private
  rollback, bounded worker termination and borrowed-connection ownership.

Focused commands:

```bash
make test-one MODULE=adapters/adapter-store-jdbc TEST=JdbcCanonicalImportWriterContractIT
make test-one MODULE=adapters/adapter-store-jdbc TEST=SqlitePublicationExperimentIT
```

The focused runs executed 28 import-contract and 30 physical-protocol tests,
with zero failures/errors/skips. They retain the same suite universe.

Full completion checks are `make verify`, `make pmd-analysis`,
`make pmd-watchlist` and `make docs`; `make context` identifies exact-HEAD
freshness. A passed journal slice is not acceptance of the complete C3 backend.

## Remaining C3 work in dependency order

The physical protocol is now shared production-source code in
`JdbcPublicationProtocol`; C2 tests execute that implementation instead of a
second test-only transition engine. `JdbcPrivateChangeCapture` installs typed
address capture on private native tables after hydration. Its triggers retain
the initial committed value and the final native value/tombstone, including
cascaded children and moved composite keys. `JdbcPrivateChanges` streams indexed
256-address pages and omits reverted updates and transient insert/delete pairs.
No whole-table copy or comparison is used by capture.

Exact-driver checks distinguish SQLite JDBC update APIs: native kernel
`PreparedStatement.executeUpdate()` preserves affected-row counts under capture;
plain `Statement.executeUpdate(sql)` includes trigger work. Cardinality checks
must keep using prepared statements. The deterministic tests cover UPSERT,
constraint rejection, cascade deletion, NULL, key movement, rollback and final
contribution folding. This is capture qualification, not complete C3 acceptance.

1. Selectively hydrate the committed native base for affected rows/aliases,
   origins, sources and slot scopes in a bounded private workspace. Keep the
   same registered observation, source/rank/policy pins and authoritative
   reservations through expiry/conflict rebuild. C2's whole-table fixture copy
   or diff is forbidden on the runtime path.
2. Stream actual typed row/child/tombstone effects from the private native
   snapshot into immutable, fenced versions with bounded bytes and chunk
   digests. The journal identifies confirmations and renewals; it does not yet
   enumerate every changed alias, origin, source, slot or receipt address.
3. Integrate durable logical owner, epochs, three-attempt rebuild budget and
   fresh publication UTC with ordinary per-artifact and import cross-artifact
   entry points. Deadline equality requires rebuilding with a new lifecycle;
   stale private effects cannot renew an expired row.
4. Connect matching, active/snapshot/projection/export reads, deadline queries,
   expiry, slot reconciliation, history and receipt retention to the same
   committed-version visibility and indexed access. Cleanup must respect
   reader snapshots, active anchors and retained receipts.
5. Run the corrected native semantic oracle and port/TCK tests over the complete
   adapter. Then C4 owns the complete crash/concurrency matrix, C5 the paired
   whole-service capacity qualification, and C6 migration/operations/activation.

The proposed [ADR 0041](../../../ADR/0041-sqlite-staged-publication-experiment.md)
and [transaction design](cap-7c-transaction-design.md) remain the protocol owners.
No installed service or operator configuration changes in this slice.
