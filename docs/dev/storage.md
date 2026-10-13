# Canonical SQLite storage

Storage owns business truth, durable coordination facts, schema evolution and
local transaction boundaries. Application ports define the contracts;
`adapter-store-jdbc` implements them with SQLite, Spring JDBC and HikariCP.
Bootstrap owns datasource composition and shutdown. Storage does not choose
processing rules, CSV mapping, transport or scheduling cadence.

## Runtime flow

```text
owned document workspace / sealed import workspace
  -> validate prepared rows and final keys
  -> reserve non-reusable IDs
  -> Native canonical transaction
  -> durable receipt and projection work
  -> mutable CSV / immutable export snapshot
  -> service-ledger finalization and delivery
```

Ordinary document confirmation commits **one artifact at a time**. There is no
cross-artifact rollback for an extraction run. Managed import commits all accepted
artifact branches and its delivery receipt in **one dataframe transaction**.
Neither path exposes uncommitted rows. The production backend is Native SQLite;
there is no alternate publication backend or automatic execution dispatcher.
The decision is recorded in [ADR 0042](../ADR/0042-native-sqlite-production-storage.md).

## Ownership boundaries

| Store | Authority |
|---|---|
| Dataframe DB | Public rows, record/match identities, aliases, provenance, ordered field origins, lifecycle/history, canonical receipts, ID allocators, projection generations and reusable export slots |
| Service DB | Ingest/run, document admission, import delivery, export progress, remote fetch and publish ledgers |
| Private document workspace | Immutable source pin, decoded/refanged text, extraction scratch and sealed repeatable prepared rows |
| Private import workspace | Pinned CSV snapshot, logical rows, tri-state cells, branches, keys, warnings and rejections |
| CSV and immutable slices | Generated projections and delivery snapshots; never runtime lookup inputs |

The service and dataframe databases do not share a transaction. Application
recovery reconciles their local durable checkpoints. Moving canonical receipt,
lifecycle or slot authority into the service DB would break that protocol.
Private preparation and managed-import staging remain part of Native; they are
not versioned canonical publication or another storage backend.

## Invariants

1. **SQLite is truth.** Oneshot accumulates the same canonical data as daemon.
   Removing a CSV does not remove a canonical record.
2. **Identity is computed after preparation.** Versioned record keys use final
   public fields; named match keys provide alternative active-only aliases.
   Digest lookup must also compare complete canonical material. Provenance and
   mutable-field precedence are separate from identity.
3. **IDs are separate contracts.** Compatibility writes retain schema-aware
   ascending ID allocation. Lifecycle writes reserve durable canonical IDs and
   lifecycle IDs before mutation; failed ranges remain burned. Immutable export
   uses reusable sparse slots scoped by `(profile, artifact)`, not canonical IDs.
4. **Checkpoint precedes side effects.** Rejected preparation does not reserve
   IDs or mutate business rows. The lifecycle writer rejects duplicate final
   keys before reservation, including retained-observation mapping collisions.
5. **One owned transaction samples one effective UTC.** Native obtains write
   ownership before reading lifecycle time. Every active query uses
   `_valid_until_epoch_ms > asOf`; equality is expired. This is the accepted
   Native contract, not a fresh wall-clock sample at transaction completion.
6. **Revision and projection generation differ.** Public insertion or declared
   public-field mutation advances revision; metadata-only origin/provenance
   changes and TTL renewal do not. Expiry advances mutable projection work,
   without producing an insertion-driven immutable slice.
7. **Receipt completeness is structural.** A document receipt becomes COMPLETE
   only after every expected artifact marker, including zero-row artifacts,
   and every typed row total agree. Matching source/policy pins and retention
   are required before reuse. Import receipts own delivery idempotency.
8. **Schema changes fail closed.** Public reconciliation is additive; destructive
   changes and type drift require an explicit migration. Identity backfill
   checks every pending artifact for collisions before changing durable rows.

## Native mutation and resource ownership

`JdbcCanonicalMatchPlanner` uses indexed complete-key singleton lookup or a
bounded request-first matching session. Only active canonical lifecycles match;
retained history cannot revive a record. TEMP requests and prepared statements
belong to the transaction, not a global cache. A mutation session handles
insert, renewal, restart, update, clear and no-op through the same policy.
Ordinary confirmation retains one session per artifact; import retains one per
affected artifact. Each session owns at most 32 statements, resets bindings and
closes before commit. The writer owns the connection, reservations, revision
aggregation and receipt publication.

Writer admission distinguishes control, promotion, expiry, export-slot and
maintenance operations with bounded fairness. It orders waiting work but cannot
preempt an already-running SQLite transaction. A giant atomic promotion can
therefore delay controls; increasing pool size does not create multiple SQLite
writers. Actual measurement scopes and observed limits are in
[native capacity](native-capacity.md).

The document factory admits one lease against aggregate memory, root-wide disk
reservations and per-workspace quotas. It pins source bytes and policy identity,
uses file leases and refuses symlinks or foreign workspace roots. Inactive pins
are reclaimed by retention; a live lease cannot be removed. Decoded/refanged
text uses disk-backed pages, and source scratch is removed before sealing.
The global disk reducer preserves complete final identity, first-key order and
whole-row winner selection across all artifacts. Repeatable row cursors feed
writers and receipts without a document-wide winner list.

Row decoding validates the versioned bounded payload before allocation. ASCII
fields avoid temporary per-field decoder/buffer copies; non-ASCII uses one strict
UTF-8 decoder per row. ABSENT, NULL, field positions and corrupt-input rejection
are preserved. Receipt batches flush after 128 rows or 1 MiB estimated bindings;
an individually larger binding flushes alone. Before ID reservation, duplicate
key validation uses a disk TEMP index with a 64-KiB cache and a 256-MiB cap.
Receipt replay borrows the owning connection where required, avoiding nested
pool acquisition even with one connection. Two ordered cursors merge receipt
rows and field positions in one snapshot; missing, duplicate or orphan positions
fail closed without retaining the complete receipt in memory.

## Lifecycle, projections and export

Lifecycle activation is explicit and one-way. `disabled` preserves compatibility
behavior. `fixed + existing-records: expire` closes legacy records in bounded
keyset batches, records history and schedules projection work; intake/export
remain behind readiness until activation finishes. Upgrade never silently
renews existing rows.

Native confirmation atomically commits public rows, aliases, provenance, field
origins, observation markers, revisions, projection work and typed receipt
payload for its artifact. A live observation renews the same lifecycle; an
expired observation archives/deletes the old row and creates a new non-reusable
identity. Safe UTC has a durable non-decreasing high-water mark: small skew is
clamped, material/prolonged rollback fails closed.

Expiry uses one immutable cycle time and indexed bounded archive/delete pages.
History, source summaries and complete receipts have independent retention.
A five-second nearest-deadline backstop is read-only when nothing is due.
Retention and projection have separate backstops; successful empty checks are
silent. No job or timer is allocated per IOC.

Mutable CSV streaming reads rows and generation in one snapshot, writes a
neighboring temporary file and installs it with `ATOMIC_MOVE`. The application
serializes installation/acknowledgement per artifact. Acknowledgement advances
only installed coverage; newer work remains pending. Neither CSV construction
nor a snapshot reader holds canonical writer admission. Safe-time sampling
finishes before the read lease opens.

Immutable export reconciles reusable slots under writer admission, then pins one
read snapshot for coverage and all artifact rows. Survivors retain their slots;
vanished lifecycle slots become coalesced free ranges; new lifecycles take the
lowest available positive holes. Preferred import slots use the same registry
and owning import transaction. Generation validation catches races between
reconciliation and snapshot opening. Id-less artifacts do not allocate slots.
See [artifact export](artifact-export.md) and
[canonical lifecycle](canonical-record-lifecycle.md).

## Schema and recovery

Versioned migrations use `user_version`, followed by configured artifact-schema
reconciliation. Native dataframe format 12 and service format 14 remain the
supported current formats. The normal migrator rejects newer/experimental
formats; it does not reinterpret or lower their version.

The migration chain preserves these ownership distinctions: dataframe v4 owns
lifecycle/history/receipts, v5/v8 slots and sparse free ranges, v6 the bounded
reconciliation checkpoint, v7 named match identities, v9 import receipts,
v10 ordered registrations/field origins, and v11 ordered receipt positions/origin history and v12 accepted import warnings. Service v9 owns managed deliveries, v10 document
admission, v11 import observation reservations v12 document-policy pins, v13 export-profile single flight and v14 bounded document execution.
A document or import retry resumes its original rank; an unreserved legacy
import occurrence cannot silently acquire a different rank after restart.

| Failure boundary | Result and recovery |
|---|---|
| Schema/identity drift | Startup fails before business mutation; correct configuration or perform an explicit migration |
| Canonical transaction | Roll back that artifact or complete import delivery; reserved IDs remain burned |
| Commit before projection/finalization | Canonical truth remains; run/delivery ledgers reconcile from the canonical receipt/checkpoint |
| Source/workspace removed after commit | A compatible complete receipt is sufficient; do not reapply the delivery |
| Projection rename before acknowledgement | Required generation remains pending; startup/periodic recovery repeats installation |
| Interrupted expiry | Committed pages remain closed; reconcile resumes from truth without resurrection |
| Unsafe UTC | Confirmation fails closed; readiness reflects clock degradation without reducing high-water |
| CSV lost or corrupt | Regenerate from canonical storage |
| Unsealed import workspace | Not promotion evidence; rebuild only from the pinned snapshot and delivery checkpoint |

Rollback after schema evolution restores coordinated stopped backups of both
DBs, owned files, configuration and binary. Copying only the dataframe DB or
lowering `user_version` is unsupported.

## Extension and sources of truth

Extend application ports rather than exposing SQL types inward. A new artifact
normally needs configured columns, record/match keys and optional field policy;
reconciliation supplies its typed storage families. A new database family needs
an adapter and the same port contracts, not a new lifecycle or merge engine.

- Composition: [AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java).
- Native ordinary writer: [JdbcCanonicalLifecycleWriter](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalLifecycleWriter.java).
- Native import writer and contract: [JdbcCanonicalImportWriter](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java), [contract suite](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java).
- Lifecycle TCK binding: [JdbcCanonicalRecordLifecycleContractIT](../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalRecordLifecycleContractIT.java).
- Production Spring flow: [NativeDeliveryLifecycleIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/NativeDeliveryLifecycleIT.java).
- Module reference: [adapter-store-jdbc](../../adapters/adapter-store-jdbc/README.md).

Update this document when commit scope, identity/UTC authority, receipt recovery,
resource ownership, schema or projection/slot visibility changes.

Related capabilities: [processing](processing.md), [ingestion](ingestion.md),
[dataframe import](dataframe-import.md), [observability](observability.md).
