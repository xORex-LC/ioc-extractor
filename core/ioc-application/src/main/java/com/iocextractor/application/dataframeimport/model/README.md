# com.iocextractor.application.dataframeimport.model

## Purpose

Framework-free value objects for delivery identity, tri-state cells and the
closed policy vocabulary of managed dataframe import.

## Structure

| Files | Purpose |
|---|---|
| `ImportCell.java` | Preserves `ABSENT`, `NULL` and `VALUE` until merge |
| `DelimitedDialect.java` | Validated parser-independent delimiter grammar pinned by a compiled contract |
| `DelimitedInputLimits.java`, `ImportWorkspaceLimits.java` | Parser, staging and aggregate capacity bounds |
| `ImportLogicalRow.java`, `ImportArtifactBranch.java` | One row-atomic primary/related fan-out with canonical key material |
| `ImportRejectedLogicalRow.java`, `ImportRowIssue.java` | Bounded safe row rejection without imported values |
| `ImportWorkspaceCapacity.java` | Hysteretic shared staging-capacity state |
| `Import*Policy.java` | Closed merge, routing, duplicate, failure, formula and slot policies |
| `Import*Mode.java` | Processing, transport, artifact-role and record-separator selectors |
| `ImportPolicyToken.java` | Stable framework-free token contract |

## Dependencies

**Depends on:** Java only. **Must not depend on:** Spring, CSV, JDBC, SMB,
filesystem adapters or bootstrap configuration.
