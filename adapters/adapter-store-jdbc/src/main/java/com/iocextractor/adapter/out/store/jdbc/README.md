# JDBC storage package

This package implements application storage ports for Native SQLite. It owns
connection-scoped SQL, migrations, canonical matching/mutation, lifecycle,
provenance, typed receipts, export-slot reconciliation, private document/import
workspaces and service ledgers. Bootstrap composes the pools and shared writer
admission; policy and recovery orchestration remain in application.

Mutation/matching sessions and bounded statement scopes are thread-confined and
close before the caller commits. Private workspaces are preparation evidence;
canonical idempotency belongs to committed dataframe receipts. A CSV projection
or a service-ledger transition cannot substitute for canonical authority.

See the [module reference](../../../../../../../../../README.md),
[storage capability](../../../../../../../../../../../docs/dev/storage.md) and
[canonical lifecycle](../../../../../../../../../../../docs/dev/canonical-record-lifecycle.md).
