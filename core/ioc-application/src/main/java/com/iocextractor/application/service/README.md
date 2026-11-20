# Extraction invocation services

## Purpose and boundary

Own the extraction boundary, production pipeline assembly and private workspace lifetime without importing adapters or choosing external configuration.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [IocExtractionService](IocExtractionService.java) | Synchronous extraction plus owned sealed preparation and single-use promotion. |
| [IocExtractionServiceFactory](IocExtractionServiceFactory.java) | Bind one run's preparers/projection and required document plan to shared core collaborators. |

## Contracts and maintenance

The factory requires a workspace factory and an admitted document-plan factory. The production path uses streamed source stages and routed preparation. The public explicit runner/pipeline constructor is a separate composition seam without workspace support; `prepare` rejects that mode rather than inventing a spill fallback.

A workspace owns its pinned source, scratch and sealed rows. Preparation returns an `AutoCloseable` single-use handle; keep it alive until promotion finishes, close on cancellation/failure and publish it safely before handing it to another worker. Preparation does not reserve final IDs. Promotion crosses the policy checkpoint and may leave durable effects; cancellation after promotion has started cannot be treated as rollback. Never reuse a consumed handle or share it concurrently.

The service attaches caller-supplied run/source metadata, optional lifecycle/ordering context, and converts the final diagnostic summary into `CompletionStatus`. Exit codes belong to the driving CLI. Runtime/Error cleanup in the owned extraction path preserves primary failures where implemented; do not infer a universal post-effect rollback guarantee from resource close.

[Capability internals](../../../../../../../../../docs/dev/processing.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
