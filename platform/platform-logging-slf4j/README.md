# ioc-platform-logging-slf4j

## Purpose and boundary

Java 21 typed SLF4J records, restoring MDC scopes, a small scalar ECS profile and
explicit masking helpers. Maven identity:
`io.github.xorex-lc:ioc-platform-logging-slf4j`.
Services declare their own field/action catalogs, choose a provider and encoder,
and own disclosure, resources and delivery qualification.

Main code depends only on the JDK and SLF4J API. IOC catalogs, ETL/diagnostic
integrations, Spring, backend configuration and JSON encoding stay outside this
library. No provider, starter, worker, retry/fallback logger or automatic context
propagation is installed. The IOC domain/application do not depend on this facade.

## Structure

| Location | Responsibility |
|---|---|
| [logging](src/main/java/com/iocextractor/platform/logging/README.md) | Field/type declarations, scalar normalization, event factories/emission and restoring MDC scopes |
| [ecs](src/main/java/com/iocextractor/platform/logging/ecs/README.md) | Five shared scalar descriptors, outcome vocabulary and encoder ownership |
| [redaction](src/main/java/com/iocextractor/platform/logging/redaction/README.md) | Constant masking and selected lexical URL-component transformation |
| [Tests](src/test/java/com/iocextractor/platform/logging/README.md) | Public contracts, provider faults, async snapshots, architecture and exact guide examples |
| [pom.xml](pom.xml) | Dependency contract, flattened consumer POM, license and publication attachments |

The package references map eight public types and internal helpers; public
Javadoc and each `package-info.java` own exact member/package contracts.

## Dependencies and publication

The sole main dependency is required compile-scoped `org.slf4j:slf4j-api`.
The consumer supplies its provider. Logback classic/core, ArchUnit, Jupiter and
AssertJ support tests; they are not library runtime dependencies. Parent dependency
management owns versions, while the consumer guide names the qualified baseline.
Local `LoggingArchitectureTest` and bootstrap architecture rules enforce the
JDK/SLF4J-only boundary.

The flattened consumer POM removes the reactor parent and retains the required
SLF4J dependency. Main/source artifacts include the MIT license. The
`library-publication` profile adds source and strict Javadoc attachments, with
warnings treated as failures. Current source versions follow the shared reactor;
independent-version activation remains pending as
[TOOL-9](../../docs/KNOWN-ISSUES.md#tool-9) under
[ADR 0048](../../docs/ADR/0048-independent-library-versions-and-reuse.md).

The [permanent release record](../../.github/release-notes/ioc-platform-logging-slf4j-0.3.0-rc.1.md)
retains immutable `0.3.0-rc.1` source, signed files and separate plain/Boot
repository qualification. Those historical checks do not qualify another
worktree, rebuild, provider or deployed service.

## Entry points and limits

Declare `LogFieldSpec` values, create an event through `LogEvents`, add fields
and call `log`. Field identity within an event is its key; the declared type
survives null omission. Unsupported representations and conflicting types fail
even at disabled levels. Null omits an event-local value and leaves ambient MDC
visible. A non-null local value temporarily hides only its exact same-key MDC
collision during emission.

`MdcScope` restores touched keys on the same thread, with nested scopes closed
in LIFO order. Acquire the scope before populating it inside the resource body.
No worker context is inherited by this API; reconstruct it explicitly. Provider
failures propagate and can prevent complete restoration. Event builders/scopes
are mutable and not thread-safe.

Generic fields validate scalar representation, not complete ECS semantics or
catalog ownership. Shared helpers add nonblank action, outcome vocabulary and
nonnegative nanosecond-duration checks. Masks apply to selected STRING values;
they do not scrub messages, Throwable, unrelated fields or ambient context.
Successful `log` return is not proof that JSON bytes were written or consumed.

IOC's [platform-observability](../platform-observability/README.md) binds
local catalogs, MDC and ETL observation to these mechanics. The
[diagnostics bridge](../platform-diagnostics-logging/README.md) owns its
severity-dependent context formatter. Bootstrap owns the backend and encoder;
[observability](../../docs/dev/observability.md) explains the complete service flow.

## Verification and related docs

From the repository root:

```bash
make test-module MODULE=platform/platform-logging-slf4j
make docs
make verify
```

Start with the [English guide](../../docs/guides/libraries/logging-slf4j.md) or
[Russian guide](../../docs/guides/ru/libraries/logging-slf4j.md). Module tests compile
and execute all three actual Java examples. The separate
[Boot ECS recipe](../../docs/guides/libraries/logging-slf4j-boot.md)
([Russian](../../docs/guides/ru/libraries/logging-slf4j-boot.md)) is application code,
qualified by bootstrap's `LoggingBootRecipeIT` for synchronous mutable Logback
events. Library async snapshot tests do not extend that recipe's supported path.
The [test reference](src/test/java/com/iocextractor/platform/logging/README.md)
maps these different levels of evidence.

For packaged checks, follow the
[publication procedure](../../docs/guides/library-publication.md#local-packaging-qualification):
prepare a new `LIBRARY_BUNDLE` with `make library-bundle LIBRARY_COMPONENT=logging`,
then `make library-consumer` with the same selector/directory. The
[plain consumer](../../tools/fixtures/logging-consumer/README.md) and
[Boot consumer](../../tools/fixtures/logging-boot-consumer/README.md) execute actual
guide sources against JARs in separate empty caches; tooling checks EN/RU
Java/XML/YAML parity, hashes/origins and missing/unavailable-SLF4J negatives.
Local API tests are separate from new remote repository qualification.
[ADR 0045](../../docs/ADR/0045-reusable-slf4j-logging-contract.md) owns the accepted
boundary. Update public Javadoc, owning references, meaningful tests and paired
guides with contract changes; preserve historical release evidence.
