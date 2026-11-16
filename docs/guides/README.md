# Operator and integration guides

<a id="гайды-guides"></a>

Practical instructions for using, operating or extending the service. Internal
flows belong in [capability docs](../README.md#developer-capabilities); system-wide
rules belong in root policies. Each guide owns its configuration/actions and
links to sources of truth rather than duplicating implementation inventories.

English guides have Russian counterparts under ru/. Both versions change with
the contract. README indexes are English-only in both directory trees.

| Guide | Russian | Purpose |
|---|---|---|
| [configuration](configuration.md) | [RU](ru/configuration.md) | Supported configuration and operator choices |
| [deployment](deployment.md) | [RU](ru/deployment.md) | Installation, upgrade, configuration reconciliation and rollback |
| [daemon-operations](daemon-operations.md) | [RU](ru/daemon-operations.md) | Intake, health, failed work, stopping and backup |
| [bulk-resource-sizing](bulk-resource-sizing.md) | [RU](ru/bulk-resource-sizing.md) | Independent resource budgets and qualification limits |
| [canonical-record-lifecycle](canonical-record-lifecycle.md) | [RU](ru/canonical-record-lifecycle.md) | Fresh validity preset and explicit existing-DB activation |
| [ioc-aggregate](ioc-aggregate.md) | [RU](ru/ioc-aggregate.md) | Aggregate feed, population, identity and rollback |
| [dataframe-import](dataframe-import.md) | [RU](ru/dataframe-import.md) | Managed local/SMB CSV contracts, preview, status and replay |
| [ioc-processing-routes](ioc-processing-routes.md) | [RU](ru/ioc-processing-routes.md) | Document/processed-import plans and branch selection |
| [remote-storage-sync](remote-storage-sync.md) | [RU](ru/remote-storage-sync.md) | SMB fetch/publish setup, ownership and recovery |
| [library-publication](library-publication.md) | [RU](ru/library-publication.md) | Bundle preparation, protected publication and immutable recovery |

## Reusable libraries

Start with the [library catalog](libraries/README.md), or the
[Russian guide index](ru/libraries/README.md). It covers concurrency, diagnostics,
SLF4J logging and Boot ECS, configuration inspection and its restricted-context
recipe, JDK control events and the explicit Spring adapter.
[Development](libraries/development.md) ([Russian](ru/libraries/development.md))
owns reuse/API/version changes; publication is the separate procedure above.

## Compatibility paths

Older `*-library*.md` paths are navigation pages to maintained guides under
libraries/. Retain incoming historical links and useful anchors; keep normative
content at its owning destination. A redirect page is not another contract copy.
