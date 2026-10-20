# Logging library consumer

A standalone Java 21 Maven project, outside the reactor. Publication tooling
copies it to a temporary directory and extracts the three complete Java examples
from the [logging guide](../../../docs/guides/logging-library.md). The consumer
checks native scalar values, omission, explicit masks, Throwable delivery and
MDC restoration through its own Logback provider. The shared classes must come
from a JAR.

The published library alone supplies SLF4J API. Sources/Javadoc classifiers and
the consumer-selected Logback dependency exclude their transitive SLF4J API so
they cannot hide a missing main dependency. A second repository supplies public
third-party dependencies; all four library artifacts must still resolve from
the selected library repository and match the retained hashes.

Use `make library-consumer LIBRARY_COMPONENT=logging LIBRARY_BUNDLE=<bundle>`.
It compiles/runs these actual examples with an empty Maven cache, then removes
compiled output and checks two deliberate failures offline with that warmed
cache. `exclude-slf4j` must fail compilation for missing SLF4J types;
`unavailable-slf4j` must fail dependency resolution. A successful negative build
or an unrelated error is rejected. The temporary project/cache are removed on
success and failure; no daemon or asynchronous worker is started here.

This is local packaging evidence until the same consumer downloads a protected
release from Central and GitHub Packages separately. It does not qualify a
second production service or another provider. The companion
[Boot consumer](../logging-boot-consumer/README.md) checks the application recipe.
