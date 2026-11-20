# ioc-platform-diagnostics-api

## Purpose and boundary

Reusable Java 21 diagnostics library: structured occurrences, values with
problems, pure failure decisions, bounded detail with exact counts, and
synchronous delivery. Maven identity:
`io.github.xorex-lc:ioc-platform-diagnostics-api`.

Consumers own codes, messages, context vocabulary, redaction, exceptions and the
checkpoint that enforces a failure decision. The library owns no logger,
transaction, worker, durable store or wire format. It supplies no Spring starter.
IOC-specific metadata and rendering live in
[platform-diagnostics](../platform-diagnostics/README.md); logging adapts
through [platform-diagnostics-logging](../platform-diagnostics-logging/README.md).

## Structure

| Location | Responsibility and starting points |
|---|---|
| [diagnostics](src/main/java/com/iocextractor/platform/diagnostics/README.md) | `DiagnosticCode`, severity/impact, occurrence, factory and builder |
| [result](src/main/java/com/iocextractor/platform/diagnostics/result/README.md) | `Result`, notification, policies, bounded collectors, batch and summary |
| [sink](src/main/java/com/iocextractor/platform/diagnostics/sink/README.md) | Synchronous delivery port and no-op, collecting and resilient implementations |
| [Tests](src/test/java/com/iocextractor/platform/diagnostics/README.md) | Consumer-owned codes and deterministic value, policy, counting and delivery checks |
| [pom.xml](pom.xml) | Standalone consumer POM, license resource and publication attachments |

Public Javadoc and each package's `package-info.java` own exact API guarantees;
the package references map the eighteen public types to their responsibilities.

## Dependencies and publication

There are no production dependencies outside the JDK, including other reactor
modules. IOC catalogs/product exceptions, Spring and logging frameworks must stay
outside this API. JUnit and AssertJ are inherited test dependencies. Bootstrap's
[ArchitectureTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
enforces the API package's JDK-only dependency boundary during reactor tests.

This is a plain library JAR. Its flattened consumer POM removes the build parent;
the main JAR includes `META-INF/LICENSE`. The `library-publication` profile adds
sources and Javadoc JARs with strict doclint and warnings treated as failures.
Current source versions follow the reactor's shared version. Independent version
ownership is accepted by [ADR 0048](../../docs/ADR/0048-independent-library-versions-and-reuse.md);
Maven/CI activation remains pending in [TOOL-9](../../docs/KNOWN-ISSUES.md#tool-9).

The [permanent component release record](../../.github/release-notes/ioc-platform-diagnostics-api-0.3.0-rc.1.md)
retains the immutable `0.3.0-rc.1` source, signed hashes and separate repository
qualification. Those historical checks do not qualify a later worktree or rebuild.
Use the [English consumer guide](../../docs/guides/libraries/diagnostics.md) or
[Russian consumer guide](../../docs/guides/ru/libraries/diagnostics.md) for a released
dependency declaration and three executable examples.

## Entry points and contracts

Create a `DiagnosticFactory` with a caller-owned `Clock`, implement
`DiagnosticCode`, then attach occurrences to `Result<T>` or a task-owned
notification. Severity and impact are independent: the built-in policies stop
on ERROR/FATAL or only FATAL; impact identifies ownership and budget treatment.
A decision changes no control flow until the caller enforces it.

For high-cardinality processing, collect stage detail in
`BoundedDiagnosticCollector`, merge its `batch()` into `BoundedNotification`,
and keep the exact `DiagnosticSummary` alongside retained samples. Use the same
reserved marker code throughout aggregation. `diagnostics()` is a display view
with a synthetic marker; it must not be re-ingested as real occurrences.

Budgets constrain retained ELEMENT/RUN occurrences, not total memory, context
size or lifetime delivery calls. Every OPERATION occurrence remains retained.
Compaction preserves built-in stopping signals, but can change sample order and
lose specific reasons. Apply policies needing every code before compaction.

Builders, accumulators and collecting sinks belong to one task and are not
thread-safe. Collection snapshots are shallow: code objects, context values,
result values and causes remain shared. Keep them stable after publication.
Diagnostic inspection can expose raw context; rendering/redaction belongs at the
consumer boundary. A fallible delivery adapter should use
`ResilientDiagnosticSink`; it contains runtime failures synchronously, without
retry, rollback or a latency bound.

## Verification and related docs

From the repository root:

```bash
make test-module MODULE=platform/platform-diagnostics-api
make docs
make verify
```

The [test reference](src/test/java/com/iocextractor/platform/diagnostics/README.md)
maps behavior coverage and its limits. For local packaging, use
`make library-bundle LIBRARY_COMPONENT=diagnostics` with a new `LIBRARY_BUNDLE`
directory, then `make library-consumer` with the same selector and directory.
The [standalone consumer](../../tools/fixtures/diagnostics-consumer/README.md) extracts
all three actual guide sources, checks EN/RU Java/XML parity, and builds outside
the checkout with a fresh cache. Follow the
[publication guide](../../docs/guides/library-publication.md#local-packaging-qualification)
for complete commands and separate remote qualification; local packaging does
not authorize publication.

[ADR 0044](../../docs/ADR/0044-reusable-diagnostics-contract.md) owns the extraction and
compatibility decision. [Observability](../../docs/dev/observability.md) explains the
service integration, and [platform-etl](../platform-etl/README.md) owns
runner integration. Update affected references, public Javadoc, meaningful tests
and paired consumer guides when a contract changes.
