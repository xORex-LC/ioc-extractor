# Standalone diagnostics consumer

Java 21 consumer of `io.github.xorex-lc:ioc-platform-diagnostics-api`. The
publication tool copies this project outside the checkout, supplies empty Maven
settings and a fresh cache, and compares the resolved POM, main, sources and
Javadoc JARs with the bundle manifest.

The publication tool extracts all three Java examples from the
[consumer guide](../../../docs/guides/libraries/diagnostics.md), requires identical
Java/XML blocks in its Russian translation, and compiles/runs those actual sources.
The executable also retains independent checks of results and pure
failure decisions, exact stage/run counts under a one-sample budget, and delivery
failure callbacks. Codes belong to this consumer; no IOC catalog, exception,
Spring or logging dependency is used. This qualifies packaging and API usage;
integration into another production service remains future evidence.
Running this fixture without the publication tool's source preparation is unsupported.

From the repository root, run:

```bash
make library-consumer LIBRARY_COMPONENT=diagnostics LIBRARY_BUNDLE=.dev/diagnostics-bundle
```

Set `LIBRARY_REPOSITORY=central` or `github` only for a published bundle.

## Source map and ownership

| Source | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Standalone Java 21 compiler, packaged dependencies/repositories and executable entry point |
| [LibraryConsumer.java](LibraryConsumer.java) | Independent consumer assertions and calls into prepared guide examples |

The harness supplies `DiagnosticResultExample`, `DiagnosticBudgetExample`, `DiagnosticDeliveryExample`. Generated guide sources live only in the
temporary copy; the tracked consumer remains an independent contract witness.

Independent checks use a fixed clock and consumer-owned codes. They distinguish
retained samples from exact total/suppressed counts, preserve rejecting signals
across stage/run aggregation, and exercise resilient delivery callbacks. No IOC
catalog or business storage is loaded; cross-module pipeline integration remains
with the application's own suites.

The [fixture overview](../README.md) owns preparation, caches, routing, cleanup
and common evidence limits. The [tool test reference](../../tests/README.md)
distinguishes offline harness contracts from actual packaged consumption.
