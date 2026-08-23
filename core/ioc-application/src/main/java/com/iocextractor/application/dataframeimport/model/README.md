# com.iocextractor.application.dataframeimport.model

## Purpose

Framework-free value objects for delivery identity, tri-state cells and the
closed policy vocabulary of managed dataframe import.

## Structure

| Files | Purpose |
|---|---|
| `ImportCell.java` | Preserves `ABSENT`, `NULL` and `VALUE` until merge |
| `Import*Policy.java` | Closed merge, routing, duplicate, failure, formula and slot policies |
| `Import*Mode.java` | Processing, transport, artifact-role and record-separator selectors |
| `ImportPolicyToken.java` | Stable framework-free token contract |

## Dependencies

**Depends on:** Java only. **Must not depend on:** Spring, CSV, JDBC, SMB,
filesystem adapters or bootstrap configuration.
