# Application pipeline model

## Purpose and boundary

Own IOC completion status and metadata keys; the reusable pipeline mechanism belongs to `platform-etl`.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [CompletionStatus](CompletionStatus.java), [PipelineMetaAttributes](PipelineMetaAttributes.java) | Completion independent of adapter exit codes and typed metadata-key vocabulary. |
| [payload/README.md](payload/README.md), [stage/README.md](stage/README.md) | Stage values and operations. |

## Contracts and maintenance

The service/factory owns stage order. Stages do not call neighboring stage implementations. Workspace, lifecycle and registration metadata must be propagated with the envelope; source scratch and row cursors remain owned by the invocation.

Diagnostic sample limits bound retained detail, not severity totals. Exact summary counts still reach the rejecting checkpoint when samples are suppressed. A diagnostic normally travels in a stage outcome or its typed failure, not both. A post-commit operational/projection observation cannot retroactively become a precommit rejection.

[Capability internals](../../../../../../../../../docs/dev/processing.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
