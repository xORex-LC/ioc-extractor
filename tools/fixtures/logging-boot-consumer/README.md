# Boot logging recipe consumer

A separate Java 21 Maven project with its own empty cache. Tooling copies the
actual plain Java and [Boot guide](../../../docs/guides/libraries/logging-slf4j-boot.md)
Java/YAML/XML blocks, plus the neutral `BootRecipeMain` driver from bootstrap's
test resources. No IOC runtime class directory, IOC JAR or IOC configuration is
used. The packaged logging library supplies SLF4J API; this consumer explicitly
chooses Boot 4.0.8 and Logback 1.5.38 as its backend.

The driver loads the configuration through Boot, restores source event/MDC state
and writes five records to a temporary destination. The consumer parses actual
bytes with strict duplicate-key detection and checks metadata, native scalars,
omission, masking and Throwable/type-only errors. Supported input must produce
no encoder warning or error. The driver closes Logback in `finally`; the outer
tool owns the temporary project/cache/output and removes them on failure too.

Run it together with the [plain consumer](../logging-consumer/README.md) through
`make library-consumer LIBRARY_COMPONENT=logging LIBRARY_BUNDLE=<bundle>`.
All four library downloads must match the bundle and selected repository origin.
This qualifies the synchronous mutable-event recipe from packaged JARs. The
in-checkout `LoggingBootRecipeIT` owns the six unsupported-input scenarios.
Neither fixture qualifies async encoder transports, a deployment, collector
ingestion, Elasticsearch mappings or load.

Executable Java/XML/YAML blocks must match the Russian versions; the publication
tool rejects translation drift before compilation. The unchanged independent
assertions/driver remain separate from the guide sources. The
[permanent release record](../../../.github/release-notes/ioc-platform-logging-slf4j-0.3.0-rc.1.md)
retains successful original Central/GitHub qualification for `0.3.0-rc.1`.
Run `make library-publication-test` for extraction/parity guards.

## Source map and ownership

| Source | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Standalone Java 21 compiler, packaged dependencies/repositories and executable entry point |
| [src/main/java/LibraryConsumer.java](src/main/java/LibraryConsumer.java) | Independent consumer assertions and calls into prepared guide examples |

The harness supplies `examples.logging` guide classes, `CollectorEcsEncoder`, `qualification.BootRecipeMain`. Generated guide sources live only in the
temporary copy; the tracked consumer remains an independent contract witness.

The POM owns Boot/Logback and Jackson for byte-level assertions. The harness adds
plain logging examples, the Boot guide encoder, neutral driver and resources
under `src/main/resources/recipe`. Independent checks inspect the five emitted
JSON lines and status file with strict duplicate detection. Backend ownership and
unsupported-input assertions remain in the guide/IOC recipe IT rather than in
the reusable SLF4J library.

The [fixture overview](../README.md) owns preparation, caches, routing, cleanup
and common evidence limits. The [tool test reference](../../tests/README.md)
distinguishes offline harness contracts from actual packaged consumption.
