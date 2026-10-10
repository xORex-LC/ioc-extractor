# CAP-7C C6 SQL analyzer review

Evidence identity: `CAP-7C-C6-SQL`. Scope: exact SpotBugs identities touched by
drained native adoption and publication-header reclamation. This review adds no
class/package/rule exclusion and changes no PMD/CPD or coverage ratchet.

| Finding | SQL sources and value boundary | Disposition |
|---|---|---|
| `JdbcStagedCanonicalMigration.copyTable` | Table/column names come from the inspected native catalog. Every name passes `JdbcSql.quote` / `DataframeColumn.requireSqlIdentifier`; length and SELECT expressions are fixed adapter templates. Native fields are read and copied as values, never SQL fragments. | False positive; exact method/type/hash/occurrence |
| `JdbcPrivateIdReservations.initializeInMigration` | Artifact name passes identifier validation before any derived history/sequence name is quoted. High-water templates are adapter-owned; artifact, epoch, next value and timestamps are prepared parameters. The same initialization transition is reused within the caller's transaction. | False positive; exact method/type/hash/occurrence |
| `JdbcPublicationHeaderCleanup.references` | Version-family names come from the validated stored catalog; other tables and column names are fixed adapter call-site literals. Both identifiers are validated and quoted. Unit ID is always bound as a parameter. | False positive; exact method/type/hash/occurrence |
| Existing `JdbcPublicationProtocol.initialize` | One new pending-state index is a closed DDL literal. No operator or data string supplies SQL. The existing reviewed finding needs a new bytecode/source anchor, with the same security disposition. | Re-anchor existing acceptance |
| Existing typed-store initialization | `initializeEmpty` delegates to shared empty/populated initialization. View/table names still pass the same identifier grammar and quoting. It executes only adapter-owned DDL templates, never IOC fields. | Update existing method/hash/anchor acceptance after method movement |

`StagedPublicationOperationsIT` exercises the typed copy and reclamation on real
SQLite, including source text containing SQL-like punctuation. A native catalog
column with an invalid identifier is rejected without changing the format or
native facts; removing that unsupported shape permits literal source copying.
Other checks cover rollback on oversized rows, canonical receipt preservation,
allocation high-water marks and bounded maintenance with retained references.

Re-review if the identifier grammar/quoting, catalog admission, migration sources,
DDL templates or parameter-binding boundaries change. Do not broaden these exact
acceptances to unrelated dynamic SQL.

Early focused raw analysis also reported an intentional generic RuntimeException rethrow
in maintenance failure handling. It was removed by recording the outcome in
`finally`, which preserves propagation of the original failure and marks health
degraded for runtime and fatal failures alike. No acceptance is added for it.
