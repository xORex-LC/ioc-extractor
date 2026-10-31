# Standalone diagnostics consumer

JDK 21 consumer of `io.github.xorex-lc:ioc-platform-diagnostics-api`. The
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
