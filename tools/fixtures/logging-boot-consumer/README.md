# Boot logging recipe consumer

A separate Java 21 Maven project with its own empty cache. Tooling copies the
actual plain Java and [Boot guide](../../../docs/guides/logging-library-boot.md)
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
