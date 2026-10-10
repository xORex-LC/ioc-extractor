# CAP-7C C3: native kernel integration

Status: candidate implementation integrated; final reactor/analyzer qualification
is in progress. No runtime activation or existing-data migration is included.
C2 is closed; C3 awaits its final gates, while C4–C6 and G6 remain open.

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

## Native port composition slice

The isolated `JdbcStagedCanonicalBackend` now composes ordinary confirmation,
cross-artifact import, matching/active/stream/snapshot readers, expiry, native
slot reconciliation, projection CAS and receipt/history retention. Preparation
uses selective hydration and the shared workspace admission/quota owner. The
canonical-base and private TEMP caches are bounded; pooled read policy is
restored after closing the owned snapshot. No default bootstrap switch exists.

The import TCK runs on this candidate. The latest focused command executes 33
tests with no failures/errors/skips. Additional native-table oracles cover
ordinary multi-artifact receipts, import COALESCE diagnostics, fixed-cycle
controls, slot export, history and receipt cascades. Fault regression proves
hidden-chunk cancellation and burned IDs. A deadline-equality regression rebuilds
a no-op import at fresh publication UTC without resurrecting the old lifecycle.
Version/journal cleanup, remaining resource-bound tests and the complete reactor
gates are still in progress; this slice does not close C3.

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

## Bounded cleanup and resource qualification slice

The candidate now bounds physical cleanup's scanned prefix as well as deletion
count, with persisted family/attempt cursors. Pending versions are retained;
latest tombstones remain until all other versions of their address disappear.
Terminal transfer journals can drain in 256-row transactions while typed data,
receipt recovery and header deadline anchors remain available. Header/pin
compaction and maintenance scheduling are explicitly deferred to C6.

Hydration performs SQLite byte-size checks before driver materialization.
Native private/committed-base/stage caches and TEMP pages respect the shared
workspace budget. Native duplicate validation no longer increases a stricter
private TEMP limit. Candidate catalog checks precede ID initialization;
reopening rejects policy/catalog drift.

Both inherited import and lifecycle TCKs run on the staged backend. The lifecycle
TCK now checks one published timestamp/deadline across a multi-row confirmation
using a clock that ticks on each read, and no additional clock sampling on replay.
Counting all preliminary reads as confirmations was specific to the former
single-transaction implementation and contradicted the approved publication-UTC
decision. Test suite inventory, tags, coverage floors and ratchets are unchanged.

Resource/fault regressions cover weighted nonblocking admission, released permits
after pin failure, thread confinement, pooled policy restoration, oversized base
fields, three-epoch-invalidation exhaustion, cancellation/retry and rejecting
changed pins before ownership without cancelling existing pending state.

The module integration command completed 381 tests with one opt-in load-profile
skip and no failures/errors before the final cleanup/cache refinements. Failed
development runs remain in `.dev/c3-*.log`: clock-call assertions, insufficient
concurrent fixture headroom, an incorrect exhaustion expectation and a fixture
type error were resolved without retries or gate relaxation. Final complete
verification and analyzer review are recorded below once executed.

The second integration slice implements a persisted typed catalog, immutable
versions and one committed visibility predicate. Two streaming passes over the
same sealed native snapshot describe and transfer at most 256 rows / 1 MiB per
physical chunk. Digest framing distinguishes SQL NULL, integer, text and binary
values; no table-wide diff or timestamp-based renewal guess is introduced.

`sharedTypedStorePublishesCapturedNativeFanOutAndRecoversWithoutInputStage`
executes the actual import writer, transports fan-out changes and COALESCE
warnings, proves hidden chunks and a pinned old reader, and compares every typed
family with the corrected native writer at publication UTC. Receipt-only replay
works after deleting the stage. This test qualifies typed transport; complete
native port composition, control operations and cleanup still require their own
port and resource checks recorded above.

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
   adapter. C4 owns the complete crash/concurrency matrix, C5 the paired
   whole-service capacity qualification, and C6 migration/operations/activation.

The proposed [ADR 0041](../../../ADR/0041-sqlite-staged-publication-experiment.md)
and [transaction design](cap-7c-transaction-design.md) remain the protocol owners.
No installed service or operator configuration changes in this slice.
