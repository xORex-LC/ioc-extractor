# Staged publication operational rehearsal

Staged publication remains an isolated SQLite candidate. This procedure describes
its local operational qualification and a future activation boundary; the default
application does not expose a selector. Resource qualification and acceptance of
[ADR 0041](../ADR/0041-sqlite-staged-publication-experiment.md) remain prerequisites
for adding that selector or registering a production upgrade. Do not edit installed
database formats to enable the candidate.

## Prepare a coordinated recovery point

1. Stop intake and external producers. Drain document/import registration and
   delivery finalization, ingest runs, immutable export and publish work under the
   native executable and its pinned configuration. Resolve failed owned work; do
   not delete it to make the drain check pass.
2. Stop the service and every process that can access its databases or owned
   directories, including CLI/export/health processes. Confirm bounded termination
   of owned workers. Candidate file leases do not fence an older native binary.
3. Record the executable checksum/commit, effective configuration, driver version,
   service/dataframe schema versions and paths. Copy both stopped databases with
   any surviving journal/WAL files, service-owned processing/snapshot/workspace
   state, mutable projections and immutable slices/manifests. Include external
   configured paths in the same recovery point. Preserve permissions and checksums.
4. Keep this recovery point immutable. Rehearse restoring it into an isolated
   directory before trying conversion. A database-only copy does not prove recovery
   of owned files or delivery coordination.

The current automatic deployment backup in `deploy-local-root.sh` contains
`var/db` only. It is insufficient for this new coordinated conversion boundary.
Use a verified full stopped recovery point for rehearsal; extending the installed
deployment transaction is required before a production selector can be activated.

## Convert and recover in isolation

The candidate's owning scope checks service drain and native format/catalog pins,
then converts the dataframe atomically to isolated format 13. Allocation high-water
marks and literal typed facts survive. Conversion can require substantial disk and
offline writer time; qualify those costs on the intended dataset. It has no automatic
fallback to native storage.

On reopen, keep intake/export disabled while bounded startup recovery pages cancel
abandoned hidden units and release abandoned logical ownership. Continue until the
scope reports ready. Confirm safe UTC, configuration pins, storage integrity and
physical cleanup progress. Then recover deliveries through the existing service
ledger and canonical receipts. A committed delivery must finalize without its
CSV/stage; an uncommitted delivery must retain compatible pinned input.

Exercise periodic cleanup pages and restart midway through their persisted progress.
Check canonical fields, IDs/lifecycles, aliases, origins/provenance, revisions,
generations, slots and receipts before and after cleanup. Record WAL, disk, retained
promotion pins, connections/readers and writer latency as separate resource evidence.
Deterministic operational tests do not establish those capacity budgets.

## Roll back the rehearsal

Stop all candidate work and close its scope before closing the database pools.
Restore both databases, owned files, executable and configuration together from the
immutable recovery point. Verify file checksums before starting the native binary.
The old format migrator must accept the restored native schema; it must reject the
converted schema. Lowering `user_version` or restoring one database alone is not
rollback. Work accepted after the backup is absent after restoration and requires
explicit reconciliation of external transport state.

## Executable evidence

Run the deterministic local suite through the standard facade:

```bash
make test-one MODULE=adapters/adapter-store-jdbc TEST=StagedPublicationOperationsIT
make verify
make pmd-analysis
make pmd-watchlist
make docs
```

The suite owns temporary databases/directories and bounded workers. It does not
touch an installed service or establish SMB, power-loss, whole-service capacity
or production activation evidence. Implementation details live in
[staged canonical publication](../dev/staged-canonical-publication.md).
