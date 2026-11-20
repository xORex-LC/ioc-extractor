# com.iocextractor.application.tck.lifecycle

## Responsibility and sources

[CanonicalRecordLifecycleContractTest](CanonicalRecordLifecycleContractTest.java)
checks canonical validity through writer, active reader and expiry ports.
[package-info.java](package-info.java) records the package responsibility.
Application contracts, JDK values and JUnit/AssertJ are the only required surfaces.

## Fixture and exercised behavior

Implement `createFixture(LifecycleTimeSource, RecordValidityPolicy)` with three
ports over one clean active lifecycle store. Configure the synthetic `masks`
artifact with `id`, `value` and `source`, and apply the supplied clock/policy at the
canonical write boundary. The consumer owns schema/activation setup and cleanup.

The base checks create/renew behavior, matching-alias renewal despite a different
supplied row key, inactive membership at exact deadline equality, reconfirmation
with new identities, observation replay, bounded expiry and ID non-reuse.
Renewal preserves insert-driven revision; expiry advances projection work without
advancing that revision. Bounded expiry reports a remaining backlog and eventually
clears the nearest deadline.

One multi-row confirmation must publish one effective UTC time and corresponding
deadline for all rows. Receipt replay preserves that result and does not sample
write time again. The base does not assert an exact number of earlier clock reads
during claim/private preparation.

The test source calls the canonical row's `id` a public ID. In this fixture it is
the non-reusable canonical storage identity. It does **not** describe the reusable,
sparse `(profile, artifact)` slots assigned to immutable exports. SQL/migration,
physical races and export-slot reconciliation require separate adapter evidence.

## Consumer and related docs

[JdbcCanonicalRecordLifecycleContractIT](../../../../../../../../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalRecordLifecycleContractIT.java)
configures real SQLite writer/reader/expiry ports and a canonical ID allocator.
See [canonical lifecycle](../../../../../../../../../../docs/dev/canonical-record-lifecycle.md) for the capability
and [TCK setup](../../../../../../../../README.md) for inherited-test execution.
