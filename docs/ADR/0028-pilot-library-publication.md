# ADR 0028: Publish the concurrency pilot as an independent library

Status: Accepted, 2026-09-07. Repository qualification remains pending.

## Context

`platform-concurrency` already has production consumers in the application,
ingest adapter and composition root. A planned Java/Spring Boot feeds collector
may reuse it, but its scheduling semantics have not been decided. Distribution
must not turn IOC orchestration, durable recovery or Spring configuration into
part of the library API. The owner has verified the GitHub-based Central
namespace and selected two release destinations.

## Decision

Publish only `io.github.xorex-lc:ioc-platform-concurrency`. Keep its existing
Java packages and its existing place in the Maven reactor. Other modules retain
`com.iocextractor`; this is an explicit artifact-specific exception in report
membership checks, not permission to add arbitrary external report dependencies.
The library requires Java 21 and has no runtime dependencies. Its flattened
consumer POM has no parent, unresolved properties or reactor dependencies.
The root parent and other platform modules are not published by this workflow.

The admitted public surface is the twelve types documented in the
[module reference](../../platform/platform-concurrency/README.md): asynchronous
bounded per-key FIFO, synchronous per-key exclusion, admission and telemetry.
Keys are process-local; no deduplication, coalescing, persistence, global queue
bound or distributed coordination is promised. Future feeds policy requires a
separate consumer decision. Public signatures and observable documented behavior
must be reviewed for compatibility before each release. During 0.x, breaking
changes require a new minor version and migration notes; patches preserve the
contract. Java package relocation would be a breaking change.

Maven Central is primary. GitHub Packages mirrors the exact POM, main JAR,
sources JAR, Javadoc JAR and signatures. The pilot follows the product release
version and annotated `vX.Y.Z` / `vX.Y.Z-rc.N` tag, selected from the default
branch. Independent version cadence can be admitted later. Snapshot publication
is deferred; local snapshot packaging does not admit a snapshot repository.

Build and verify once without publishing credentials. Retain a manifest with
source commit and artifact SHA-256 values. Sign once in the `LIBRARY PUBLISHING`
environment, retain the signed bundle before writes, and use Central's
`USER_MANAGED` validation stage by default. Publishing is a separate explicit
workflow operation. Both destinations receive those same files. Existing
conflicting bytes stop publication; retries preserve the original signed bundle
and deployment ID, skip matching files and repair only missing destination files.
No release is overwritten, rebuilt or re-signed to repair a partial publication.

The independent consumer lives outside the reactor and runs from a temporary
project with empty settings and cache. It resolves the primary artifacts from
one selected repository and exercises public behavior. Each public repository
must be qualified separately. Local file-repository success is packaging evidence,
not proof of published coordinates or a real second service integration.

## Consequences and alternatives

Keeping the library in this reactor preserves the existing quality gates and
ownership. A separate repository or parent publication is unnecessary for this
pilot. Flattening removes the build inheritance that consumers must not need.
A Central-only release would be simpler, but the owner selected a second channel;
retained immutable artifacts and explicit partial-publication recovery are the
cost of that choice. GitHub Maven downloads require authentication, so Central
remains the public default.

Publication mechanics and recovery are documented in the
[operator guide](../guides/library-publication.md). Live signing, repository
authorization and downloads remain a required first-release qualification.
