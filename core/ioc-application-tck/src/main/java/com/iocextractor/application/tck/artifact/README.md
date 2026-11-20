# com.iocextractor.application.tck.artifact

## Responsibility and sources

[CanonicalIdentityStoreContractTest](CanonicalIdentityStoreContractTest.java) is the
reusable baseline for `ArtifactIdentityStore`. [package-info.java](package-info.java)
marks the identity/mutation storage contract namespace; this package currently
contains only the identity-store base.

## Fixture and exercised behavior

Implement `createIdentityStore()` with a clean store containing an empty `masks`
artifact with `mask` and `source` columns. The baseline checks initial formula
registration, exact replay, same-epoch formula drift rejection and acceptance of a
formula change from epoch 1 to 2. It observes persisted hash/epoch through the port.

[JdbcArtifactIdentityStoreIT](../../../../../../../../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcArtifactIdentityStoreIT.java)
creates a real SQLite fixture and adds diagnostic, backfill, alias/collision and
rollback checks. The base does not establish safe schema migration or collision
handling for an arbitrary adapter merely by accepting an epoch bump.

## Boundary and related docs

Only application identity values/port and JUnit/AssertJ are needed here. The
consumer owns schema setup and datasource cleanup. Formula activation/backfill
mechanics belong to [storage](../../../../../../../../../../docs/dev/storage.md) and the implementing adapter;
[TCK setup](../../../../../../../../README.md) explains inherited execution.
