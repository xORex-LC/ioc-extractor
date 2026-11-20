# Observability integration tests

| Suite | Responsibility |
|---|---|
| [LogbackConfigurationIT.java](LogbackConfigurationIT.java) | Production Logback resource, profile/file routing and ECS settings |
| [LoggingMigrationIT.java](LoggingMigrationIT.java) | Real IOC producers through the production encoder and byte-writing appender |
| [LoggingBootRecipeIT.java](LoggingBootRecipeIT.java) | Compile/run documented library recipes on a classpath without IOC service classes |

All are Failsafe integration contracts. Recipe execution uses temporary sources,
outputs and forked JVMs; the fixture includes supported scalar/nested ECS values,
omission/redaction and deliberately unsupported input/collision scenarios.
Appender failure is asserted through status/output evidence instead of being
silently swallowed as an expected successful event.

The recipe classpath uses packaged library and third-party JARs from this build.
That checks IOC isolation and documented examples; it is not a cold external
repository installation. [Fixture resources](../../../../resources/consumer/logging/README.md)
map the driver and expected application-start record. Logging taxonomy/privacy
policy remains in [logging internals](../../../../../../../../docs/dev/observability.md).

Run from the repository root:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=LoggingMigrationIT
make test-one MODULE=bootstrap/ioc-app TEST=LoggingBootRecipeIT
```

Full local Failsafe coverage uses `make test-integration-module MODULE=bootstrap/ioc-app`;
full reactor acceptance uses `make verify`.
