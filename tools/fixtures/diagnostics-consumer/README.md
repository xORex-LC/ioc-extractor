# Standalone diagnostics consumer

JDK 21 consumer of `io.github.xorex-lc:ioc-platform-diagnostics-api`. The
publication tool copies this project outside the checkout, supplies empty Maven
settings and a fresh cache, and compares the resolved POM, main, sources and
Javadoc JARs with the bundle manifest.

The executable checks all three examples in the
[consumer guide](../../../docs/guides/diagnostics-library.md): results and pure
failure decisions, exact stage/run counts under a one-sample budget, and delivery
failure callbacks. Codes belong to this consumer; no IOC catalog, exception,
Spring or logging dependency is used. This qualifies packaging and API usage;
integration into another production service remains future evidence.

From the repository root, run:

```bash
make library-consumer LIBRARY_COMPONENT=diagnostics LIBRARY_BUNDLE=.dev/diagnostics-bundle
```

Set `LIBRARY_REPOSITORY=central` or `github` only for a published bundle.
