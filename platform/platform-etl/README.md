# ioc-platform-etl

## Purpose and boundary

Framework-free Pipes-and-Filters contracts and sequential execution for the
application's extraction pipeline. The kernel carries payloads, correlation
metadata and diagnostics without knowing IOC types, concrete stages, storage,
CLI or logging implementations. The application composes the stages and owns
their effects, resources and recovery.

This is an internal reactor JAR, `com.iocextractor:ioc-platform-etl`, using the
shared product version. It has not been admitted as an independently published
library. Generic payload contracts still depend on IOC's diagnostics catalog
and exception layer. The external-reuse review remains deferred as
[ARCH-5](../../docs/KNOWN-ISSUES.md#arch-5); documenting existing limits neither fixes
them nor admits a new library.

## Structure

| Location | Responsibility |
|---|---|
| [Production package](src/main/java/com/iocextractor/platform/etl/README.md) | Envelope/metadata, typed stage composition, runner, outcome and observation hooks |
| [Tests](src/test/java/com/iocextractor/platform/etl/README.md) | Envelope transitions, diagnostic counts, segment continuation and failure handling |
| [pom.xml](pom.xml) | Internal module identity and diagnostics/error dependencies |

The package reference maps all local types and execution boundaries. Public
Javadoc and `package-info.java` carry the precise operation/package contracts.

## Dependencies and direction

Production depends on `ioc-platform-diagnostics-api`, `ioc-platform-diagnostics`
and `ioc-platform-errors`, with versions managed by the parent. Their production
closure is JDK-only. JUnit Jupiter and AssertJ support tests. Keep domain,
application, adapters, bootstrap, Spring and logging implementations outside this
module. Framework-free does not mean independent of product diagnostics.

[ArchitectureTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
enforces the ETL package's inward dependency boundary. The outer
[observability module](../platform-observability/README.md) implements
`PipelineObserver`; the
[diagnostic logging bridge](../platform-diagnostics-logging/README.md)
implements the shared diagnostic output port. Neither is required by the runner.

## Entry points and ownership

`Pipeline.start().then(...)` connects declared payload types. The direct
constructor copies a wildcard stage list without checking type adjacency;
callers using it own the type chain. Copying the list does not copy stage state
or resources. Every run executes sequentially on its calling thread; sharing a
pipeline/runner across calls requires safe stages and collaborators.

`run` returns an envelope; `runWithOutcome` also exposes exact diagnostic counts.
Stages append occurrences or bounded batches while preserving the existing detail
prefix and exact summary. Newly retained occurrences are delivered before policy
evaluation. Input diagnostics seed the accumulator without being redelivered;
the synthetic suppression marker is not an observed occurrence.

The ELEMENT/RUN budget limits retained detail, not payloads, context bytes,
lifetime delivery calls or total heap. OPERATION occurrences bypass it. Policies
inspect compacted retained detail plus any marker, not full history. Policy runs
after stages: initial FATAL does not prevent the first stage, and an empty chain
has no checkpoint. The runner owns no transaction, rollback, retry, timeout or
cancellation protocol. Completion can therefore fail after stage effects.

`runPreparation` defers terminal suppression delivery on success. Its owner must
continue with the returned envelope or call `finishPreparation` once; the latter
only delivers a retained marker. It does not cancel work, close resources, open
a scope or deduplicate finalization. Each segment owns its own run scope, not
the interval between separately invoked segments. See the
[package reference](src/main/java/com/iocextractor/platform/etl/README.md)
for observer and exception boundaries.

## IOC integration and verification

[IocExtractionService](../../core/ioc-application/src/main/java/com/iocextractor/application/service/IocExtractionService.java)
composes the typed extraction chain and, for workspace-backed preparation,
splits it before the known write stage. Its owned preparation controls promotion,
workspace disposal and closing. That application arrangement establishes the
prepare→checkpoint→commit boundary; the generic runner alone does not make an
arbitrary segment side-effect-free. Read
[processing](../../docs/dev/processing.md) and [ingestion](../../docs/dev/ingestion.md)
for the complete service flow, and [observability](../../docs/dev/observability.md)
for logging/correlation adapters.

From the repository root:

```bash
make test-module MODULE=platform/platform-etl
make docs
make verify
```

The [test reference](src/test/java/com/iocextractor/platform/etl/README.md)
maps local coverage and its limits. Update owning references and meaningful
consumer tests with contract changes; preserve the explicit ARCH-5 limits until
a separate reviewed change resolves them.
